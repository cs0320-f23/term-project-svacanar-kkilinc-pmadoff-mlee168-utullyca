



/*
 * Copyright (c) 2022, United States Government, as represented by the
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




package gov.nasa.race.cesium

import akka.actor.Actor.Receive
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.{JsonProducer, JsonWriter}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.NoConfig
import gov.nasa.race.core.{BusEvent, ParentActor, PipedRaceDataClient}
import gov.nasa.race.earth.SmokeAvailable
import gov.nasa.race.http._
import gov.nasa.race.ui._
import gov.nasa.race.util.StringUtils
import scalatags.Text

import java.net.InetSocketAddress
import java.io.File
import scala.collection.mutable

// define default contour rendering settings

// TODO: change the name heree
class DefaultContourRenderingSM (conf: Config) {
  val strokeWidth = conf.getDoubleOrElse("stroke-width", 1.5)
  val strokeColor = conf.getStringOrElse( "stroke-color", "grey")
  val smokeColor = conf.getStringOrElse( "smoke-color", "black")
  val cloudColor = conf.getStringOrElse( "cloud-color", "white")
  val alpha = conf.getDoubleOrElse("alpha", 0.5)

  def toJsObject = s"""{ strokeWidth: $strokeWidth, strokeColor: Cesium.Color.fromCssColorString('$strokeColor'), smokeColor:Cesium.Color.fromCssColorString('$smokeColor'), cloudColor:Cesium.Color.fromCssColorString('$cloudColor'), alpha:$alpha }"""//s"""{ strokeWidth: $strokeWidth, strokeColor: Cesium.Color.fromCssColorString('$strokeColor'), fillColors:$jsFillColors, alpha:$alpha }"""
}

object SmokeLayerService {
  val jsModule = "ui_cesium_smoke.js"
  val icon = "smoke-icon.svg"
}

import SmokeLayerService._


trait SmokeLayerService extends CesiumService with FileServerRoute with PushWSRaceRoute with CachedFileAssetRoute with PipedRaceDataClient with JsonProducer{
  // service definition
  case class Layer (sla: SmokeAvailable, scType: String) {
    // case class that takes in an available object and the type - used to push data through the route server
    val urlName = f"$scType-${sla.satellite}-${sla.date.format_yMd_Hms_z}" // url where the file will be available
    val json = sla.toJsonWithUrl(s"smoke-data/$urlName")
    var file: Option[File] = None
    if (scType == "smoke") { // loads different file from smoke available depending on the type
      file = Some(sla.smokeFile)
    }
    if (scType == "cloud") {
      file = Some(sla.cloudFile)
    }
  }
  /**
   * Example JSON:
   * {
  "smokeUrlName": "smoke-[SATELLITE_ID]-[FORMATTED_DATE_TIME]",
  "cloudUrlName": "cloud-[SATELLITE_ID]-[FORMATTED_DATE_TIME]",
  "uniqueId": "[SATELLITE_ID]-[FORMATTED_DATE_TIME]",
  "json": "[GENERATED_JSON_FROM_SMOKEAVAILABLE]"
   }
   **/
  case class SmokeCloudLayer(sla: SmokeAvailable) {
    // case class that takes in an available object - used to push data through the websocket
    val smokeUrlName = f"smoke-${sla.satellite}-${sla.date.format_yMd_Hms_z}"
    val cloudUrlName = f"cloud-${sla.satellite}-${sla.date.format_yMd_Hms_z}"
    val uniqueId = f"${sla.satellite}-${sla.date.format_yMd_Hms_z}" // used to distinctly identify data
    val json = sla.toJsonWithTwoUrls(s"smoke-data/$smokeUrlName", s"smoke-data/$cloudUrlName", uniqueId) //makes for the satellietAvailable object
  }

  protected val layers: mutable.LinkedHashMap[String,Layer] = mutable.LinkedHashMap.empty // urlName -> Layer
  protected val smokeCloudLayers: mutable.LinkedHashMap[String,SmokeCloudLayer] = mutable.LinkedHashMap.empty // urlName -> SmokecloudLayer

  //--- obtaining and updating smoke fields
  override def receiveData: Receive = receiveSmokeData orElse super.receiveData

  def receiveSmokeData: Receive = { // action for recieving bus message with new data
    // Create 3 new layers
    case BusEvent(_,sla:SmokeAvailable,_) =>
      // create layers
      val cloudL = Layer(sla, "cloud") // create the cloud layer
      val smokeL = Layer(sla, "smoke") // create the smoke layer
      val smokeCloudSl = SmokeCloudLayer(sla) // create the smoke and cloud layer
      // add layers
      addLayer(cloudL)
      addLayer(smokeL)
      addSmokeCloudLayer(smokeCloudSl)
      //the server maintains a list of active WebSocket connections. Whenever the server needs to push data, it iterates over this list and sends data to each connected WebSocket client
      push( TextMessage(smokeCloudSl.json)) // the server maintains a list of active WebSocket connections. Whenever the server needs to push data, it iterates over this list and sends data to each connected WebSocket client
  }

  // add new layer functions
  // WATCH OUT - these can be used concurrently so we have to sync
  def addLayer(sl: Layer): Unit = synchronized { layers += (sl.urlName -> sl) }  //  Adds an entry to the layers LinkedHashMap, with the key being sl.urlName and the value being sl.
  def currentSmokeLayerValues: Seq[Layer] = synchronized { layers.values.toSeq }
  // WATCH OUT - these can be used concurrently so we have to sync
  def addSmokeCloudLayer(sl: SmokeCloudLayer): Unit = synchronized { smokeCloudLayers += (sl.uniqueId -> sl) }
  def currentSmokeCloudLayerValues: Seq[SmokeCloudLayer] = synchronized { smokeCloudLayers.values.toSeq }


  //If a request does not match any of the routes, Akka HTTP will automatically respond with a 404 Not Found status. You can also explicitly define a "catch-all" route to handle unmatched routes with custom logic if desired.
  def smokeRoute: Route = {
    // the function smokeRoute is defined as handling only GET requests, as indicated by the get directive.
    get {
      // Handles routes starting with "smoke-data/"
      pathPrefix("smoke-data" ~ Slash) { // This directive captures the starting segment of the URL path. It is used to group multiple routes that share a common path prefix.
        // Extract the remaining part of the URL
        extractUnmatchedPath { p =>  // This directive is used to capture the rest of the URL path after the prefix. It puts the unmatched portion into a variable (p in this case).
          val pathName = p.toString()
          // Try to find the Layer object corresponding to this path
          layers.get(pathName) match {
            // If a Layer object is found, complete the request with the file content
            case Some(sl) => completeWithFileContent(sl.file.get)
            // If not found, return a 404 status
            case None => complete(StatusCodes.NotFound, pathName)
          }
        }
      } ~ // This symbol is used to concatenate multiple routes. When a request comes in, Akka HTTP will try each of these routes in the order they are defined until it finds a match.
        // Handles routes starting with "smoke-cloud/"
        pathPrefix("smoke-cloud" ~ Slash) {
          // Extract the remaining part of the URL
          extractUnmatchedPath { p =>
            val pathName = s"smoke-cloud/$p"
            // Complete the request by sending the file content to the client
            complete(ResponseData.forPathName(pathName, getFileAssetContent(pathName)))
          }
        } ~
        // Serves the JavaScript module to the client
        fileAssetPath(jsModule) ~
        // Serves the icon to the client
        fileAssetPath(icon)
    }
  }


  override def route: Route = smokeRoute ~ super.route

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection(ctx, queue)
    initializeSmokeConnection(ctx,queue)
  }

  def initializeSmokeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = synchronized {
    // adds smoke and cloud layer objects to the websocket as messages
    val remoteAddr = ctx.remoteAddress
    currentSmokeCloudLayerValues.foreach( sl => pushTo(remoteAddr, queue, TextMessage(sl.json))) // pushes object to the UI
  }

  //--- document content generated by js module

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ Seq(
    extModule("ui_cesium_smoke.js")
  )

  //--- client config
  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + smokeLayerConfig(requestUri,remoteAddr)

  def smokeLayerConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    // defines the config sent to the js module
    val cfg = config.getConfig("smokelayer")
    val defaultContourRenderingSM = new DefaultContourRenderingSM(cfg.getConfigOrElse("contour.render", NoConfig))

    s"""
    export const smokelayer = {
      contourRender: ${defaultContourRenderingSM.toJsObject},
      followLatest: ${cfg.getBooleanOrElse("follow-latest", false)}
    };"""
  }
}

/**
 * a single page application that processes smoke and cloud segmentation images
 */
class CesiumSmokeApp(val parent: ParentActor, val config: Config) extends DocumentRoute with SmokeLayerService with ImageryLayerService