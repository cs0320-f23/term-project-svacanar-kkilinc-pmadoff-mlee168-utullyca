/*
 * Copyright (c) 2020, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gov.nasa.race.http

import akka.Done
import akka.actor.{ActorRef, Cancellable}
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.model.{HttpHeader, StatusCodes}
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import gov.nasa.race.actor.FilteringPublisher
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.SubscribingRaceActor

import java.lang.Thread.sleep
import scala.collection.mutable.{Map => MutMap}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * object that (optionally) translates incoming web socket Messages to BusEvent payloads
  * use this to de-serialize incoming messages
  */
trait WsMessageReader {
  def read (m: Message): Option[Any]
}

/**
  * default WsMessageReader that only processes strict message content, punblished either as String or Array[Byte]
  */
object DefaultWsMessageReader extends WsMessageReader {
  def read (m: Message): Option[Any] = m match {
    case tm: TextMessage.Strict => Some(tm.text)
    case bm: BinaryMessage.Strict => Some(bm.data.toArray[Byte])
    case _ => None  // ignore streams for now
  }
}

/**
  * writer that (optionally) translates BusEvent payloads to web socket Messages
  * use this to serialize outgoing messages
  */
trait WsMessageWriter {
  def write (o: Any): Option[Message]
}

/**
  * default WsMessageWriter that translates BusEvent payloads to strict TextMessages
  * TODO - we should support byte arrays as BinaryMessages here
  */
object DefaultWsMessageWriter extends WsMessageWriter {
  def write (o: Any): Option[Message] = {
    Some( TextMessage.Strict(o.toString))
  }
}

/**
  * this message can also be sent by external actors
  */
case class WsConnectRequest (uri: String)


/**
  * actor trait that connects to a websocket server, sends messages received from the bus to it and publishes
  * websocket messages it gets from the server
  *
  * note that concrete types have to call one of the connect(..) methods explicitly (e.g. during
  * onStartRaceActor) - this trait does not have any assumption as to when clients/subtypes are ready
  */
trait WSAdapterActor extends FilteringPublisher with SubscribingRaceActor
                                       with SourceQueueOwner with SSLContextUser {
  //--- internal messages
  case class WsConnectSuccess (uri: String, queue: SourceQueueWithComplete[Message])
  case class WsConnectFailure (uri: String, cause: String)
  case class WsClosed (uri: String)
  case class WsCheckConnection (uri: String, dur: FiniteDuration)

  implicit val materializer: Materializer = Materializer.matFromSystem(context.system) // ?? do we want a shared materializer
  implicit val ec = scala.concurrent.ExecutionContext.global

  val http = Http(context.system)

  // watch out - these are NOT thread safe
  protected var reader: WsMessageReader = createReader
  protected var writer: WsMessageWriter = createWriter

  protected var connection: Option[WsConnectSuccess] = None
  @inline final def isConnected: Boolean = connection.isDefined
  @inline final def isConnectedTo (uri: String): Boolean = connection.isDefined && connection.get.uri == uri

  protected val connectionChecks: MutMap[String,Cancellable] = MutMap.empty

  //--- end init

  protected def getWsUri: Option[String] = config.getOptionalString("ws-uri")

  // default behavior is to check for configured readers/writers or otherwise just pass data as strings

  protected def createReader = getConfigurableOrElse[WsMessageReader]("reader")(DefaultWsMessageReader)
  protected def createWriter = getConfigurableOrElse[WsMessageWriter]("writer")(DefaultWsMessageWriter)

  def setReader (newReader: WsMessageReader): Unit = reader = newReader
  def setWriter (newWriter: WsMessageWriter): Unit = writer = newWriter

  // override if we need to reset or drop connection if there is a send failure
  protected def handleSendFailure(msg: String): Unit = {
    warning(msg)
  }

  def cancelConnectionCheckFor (uri: String): Unit = {
    connectionChecks.get(uri) match {
      case Some(cancellable) =>
        cancellable.cancel()
        connectionChecks -= uri
      case None => // nothing scheduled
    }
  }

  /**
    * override if messages should be filtered or translated
    */
  protected def processIncomingMessage (msg: Message): Unit = {
    info(s"received incoming message: $msg")
    reader.read(msg).foreach(publishFiltered)
  }

  protected def processOutgoingMessage (o: Any): Unit = {
    if (isConnected){
      val q = connection.get.queue
      writer.synchronized {
        writer.write(o) match {
          case Some(msg) =>
            q.offer(msg).onComplete {
              case Success(_) => // all good (TODO should we check for Enqueued here?)
                info(s"message sent: $msg")

              case Failure(_) =>
                handleSendFailure(s"message send failed: $msg")
            }
          case None =>
            info(s"ignore outgoing $o")
        }
      }
    }
  }

  //--- connection/disconnection notifications - override in subclasses
  def onConnect(): Unit = {}
  def onDisconnect(): Unit = {}
  def onConnectFailed (uri: String, cause: String): Unit = {}
  def onConnectTimeout(uri: String, dur: FiniteDuration): Unit = {}


  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    connectionChecks.foreach( _._2.cancel())
    connectionChecks.clear()

    disconnect()

    super.onTerminateRaceActor(originator)
  }

  def handleWsMessage: Receive = {
    //case BusEvent(sel,msg,sender) => processOutgoingMessage(msg)

    //--- internal messages
    case m:WsConnectSuccess => handleWsConnectSuccess(m)
    case m:WsConnectFailure => handleWsConnectFailure(m)
    case m:WsClosed => handleWsClosed(m)
    case WsConnectRequest(uri) => connect(uri)
    case m:WsCheckConnection => handleWsCheckConnection(m)
  }

  def handleWsConnectSuccess(msg: WsConnectSuccess): Unit = {
    if (!isConnected) {
      cancelConnectionCheckFor(msg.uri)

      info(s"websocket connected: ${msg.uri}")
      // set this before the connection notification
      connection = Some(msg)
      onConnect()

    } else { // this request did loose out to another one, cancel it
      info(s"ignoring fallback connection to ${msg.uri}")
      msg.queue.complete()
    }
  }

  def handleWsConnectFailure (msg: WsConnectFailure): Unit = {
    if (!isConnected) {
      warning(s"websocket connect to ${msg.uri} failed: ${msg.cause}")
      cancelConnectionCheckFor(msg.uri)
      onConnectFailed(msg.uri, msg.cause)
    } else {
      info(s"ignoring websock connection failure to ${msg.uri}")
    }
  }

  def handleWsClosed (msg: WsClosed): Unit = {
    if (isConnectedTo(msg.uri)) {
      info(s"connection to ${msg.uri} closed")
      onDisconnect()
      connection = None // set this *after* notification
    } // else this wasn't the selected request - ignore
  }

  def handleWsCheckConnection (msg: WsCheckConnection): Unit = {
    if (!isConnectedTo(msg.uri)) {
      warning(s"websocket connect to ${msg.uri} timed out in ${msg.dur}")
      onConnectTimeout(msg.uri, msg.dur)
    }
  }

  /**
    * convenience method that use a configured or sub-type provided URI
    */
  def connect(): Unit = {
    if (!isConnected) {
      getWsUri match {
        case Some(uri) => connect(uri)
        case None => warning("connect request ignored - no web socket uri")
      }
    } else warning(s"connect request ignored - already connected to ${connection.get.uri}")
  }

  /**
    * subtypes should also override onConnectionDeadline(uri)
    */
  def connectWithCheck (uri: String, dur: FiniteDuration): Unit = {
    info(s"trying to connect to $uri with timeout $dur")
    connect(uri)
    connectionChecks += (uri -> scheduler.scheduleOnce(dur,self,WsCheckConnection(uri,dur)))
  }

  /**
    * this is the main method of WsAdapterActor that sends the WebSocketRequest
    *
    * it has to be called explicitly by concrete types, e.g. from their onStartRaceActor
    */
  def connect (uri: String): Unit = {
    if (isConnected) {
      warning(s"connect request to $uri ignored - already connected to ${connection.get.uri}")
      return
    }

    val inbound: Sink[Message,Future[Done]] = Sink.foreach( processIncomingMessage)
    val outbound: Source[Message, SourceQueueWithComplete[Message]] = createSourceQueue
    val flow: Flow[Message,Message,(Future[Done],SourceQueueWithComplete[Message])] = Flow.fromSinkAndSourceCoupledMat(inbound,outbound)(Keep.both)

    val webSocketRequest = getWebSocketRequest(uri)
    val connectionContext = getConnectionContext(uri)

    info(s"trying to connect to $uri ...")
    val (future: Future[WebSocketUpgradeResponse], (closed: Future[Done],qMat: SourceQueueWithComplete[Message])) =
      http.singleWebSocketRequest(webSocketRequest,flow,connectionContext=connectionContext)

    closed.onComplete( _ => self ! WsClosed(uri))

    future.onComplete {
      case Success(wsUpgradeResponse) =>
        wsUpgradeResponse match {
          case ValidUpgrade(response, _) =>
            if (response.status == StatusCodes.SwitchingProtocols) { // success
              self ! WsConnectSuccess(uri,qMat)
            } else {
              self ! WsConnectFailure(uri,s"response status: ${response.status}")
            }
          case InvalidUpgradeResponse(response,cause) =>
            self ! WsConnectFailure(uri,cause)
        }
      case Failure(x) =>
        self ! WsConnectFailure(uri,x.getMessage)
    }
  }

  def getWebSocketRequest (wsUri: String) : WebSocketRequest = {
    var xhdrs = Seq.empty[HttpHeader]
    for (
      uid <- config.getOptionalVaultableString("uid");
      pw <- config.getOptionalVaultableString("pw")
    ) {
      xhdrs = Seq(Authorization(BasicHttpCredentials(uid,pw)))
    }

    WebSocketRequest(wsUri,extraHeaders=xhdrs)
  }

  def getConnectionContext (wsUri: String): ConnectionContext = {
    if (wsUri.startsWith("wss://")) {
      getSSLContext("client-keystore") match {
        case Some(sslContext) =>
          info(s"using SSL config from client-keystore for $wsUri")
          ConnectionContext.httpsClient(sslContext)
        case None => // nothing to set, we go with the default (or what is configured for Akka)
          info(s"using default connection context for $wsUri")
          http.defaultClientHttpsContext
      }
    } else {
      http.defaultClientHttpsContext
    }
  }

  def disconnect(): Unit = {
    if (isConnected) {
      info(s"disconnecting ${connection.get.uri}")
      connection.get.queue.complete()  // this should trigger the closed future and result in a WsClose
      // TODO - should we clear 'connection' here or defer it to the handleWsClose (in which case we have to call onDisconnect notifications here)
    }
  }
}
