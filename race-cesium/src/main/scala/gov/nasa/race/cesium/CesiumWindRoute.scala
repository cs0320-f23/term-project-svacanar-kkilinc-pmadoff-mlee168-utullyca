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
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.JsonProducer
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.{BusEvent, ParentActor, PipedRaceDataClient, RaceDataClient}
import gov.nasa.race.earth.WindFieldAvailable
import gov.nasa.race.http._
import gov.nasa.race.ifSome
import gov.nasa.race.ui._
import gov.nasa.race.uom.DateTime
import scalatags.Text

import java.io.File
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, SeqMap, SortedMap}


/**
  * a CesiumRoute that displays wind fields
  *
  * this is strongly based on https://github.com/RaymanNg/3D-Wind-Field, using shader resources to compute and animate
  * particles that are put in a wind field that is derived from a client-side NetCDF structure.
  * See also https://cesium.com/blog/2019/04/29/gpu-powered-wind/
  *
  * Ultimately, this structure will be computed by this route and transmitted as a JSON object in order to remove
  * client side netcdfjs dependencies and offload client computation
  */
trait CesiumWindRoute extends CesiumRoute with FileServerRoute with PushWSRaceRoute with CachedFileAssetRoute with JsonProducer with PipedRaceDataClient {

  // we only keep the newest wf for each area/forecast time
  case class WindFieldEntry (date: DateTime, areas: SortedMap[String,WindFieldAvailable])

  val windFieldEntries: ArrayBuffer[WindFieldEntry] // sorted by forecastDate

  //--- obtaining and updating wind fields

  override def receiveData: Receive = receiveWindFieldData orElse super.receiveData

  def receiveWindFieldData: Receive = {
    case BusEvent(_,wf:WindFieldAvailable,_) =>
      if (addWindField(wf)) push(windFieldMessage(wf))

  }

  def addWindField(wf:WindFieldAvailable): Boolean = {
    val date = wf.forecastDate
    var i = 0
    while (i < windFieldEntries.length) {
      val e = windFieldEntries(i)
      if (e.date == date) {
        ifSome(e.areas.get(wf.area)) { prevWf=>
          if (prevWf.baseDate > wf.baseDate) return false // we already had a newer one
        }
        e.areas += (wf.area -> wf) // replace or add
        return true

      } else if (e.date > date) {
        windFieldEntries.insert(i, WindFieldEntry(date, SortedMap( (wf.area -> wf))))
        return true
      }
      i += 1
    }

    windFieldEntries += WindFieldEntry(date, SortedMap( (wf.area -> wf)))
    true
  }

  def windFieldMessage(e: WindFieldAvailable): TextMessage.Strict = {
    val msg = s"""{"windField":{}}"""
    TextMessage.Strict(msg)
  }

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection(ctx, queue)
    initializeWindConnection(ctx,queue)
  }

  def initializeWindConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    val remoteAddr = ctx.remoteAddress
    windFieldEntries.foreach{ we=>
      we.areas.foreach( e=> pushTo( remoteAddr, queue, windFieldMessage(e._2)))
    }
  }

  //--- routes

  def windRoute: Route = {
    get {
        pathPrefix("wind-data") {
          extractUnmatchedPath { p =>
            val file = new File(s"$windDir/$p")
            completeWithFileContent(file)
          }
        } ~
        pathPrefix("wind-particles" ~ Slash) { // this is the client side shader code, not the dynamic wind data
          extractUnmatchedPath { p =>
            val pathName = s"wind-particles/$p"
            complete( ResponseData.forPathName(pathName, getFileAssetContent(pathName)))
          }
        } ~
        path("proxy") {  // TODO this is going away
          completeProxied
        } ~
        fileAssetPath("ui_cesium_wind.js") ~
        fileAssetPath("wind-icon.svg")
    }
  }

  override def route: Route = windRoute ~ super.route

  //--- document content (generated by js module)

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ Seq(
    extModule("wind-particles/windUtils.js"),
    extModule("wind-particles/particleSystem.js"),
    extModule("wind-particles/particlesComputing.js"),
    extModule("wind-particles/particlesRendering.js"),
    extModule("wind-particles/customPrimitive.js"),
    extModule("ui_cesium_wind.js")
  )

  //--- client config
}

/**
  * a single page application that processes track channels
  */
class CesiumWindApp (val parent: ParentActor, val config: Config) extends DocumentRoute with CesiumWindRoute with ImageryLayerRoute