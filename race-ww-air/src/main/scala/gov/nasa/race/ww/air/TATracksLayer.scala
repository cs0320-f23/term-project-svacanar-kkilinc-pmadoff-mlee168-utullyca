/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.ww.air

import java.awt.{Color, Point}

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.air.TATrack.Status
import gov.nasa.race.air.{TATrack, Tracon}
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.GreatCircle
import gov.nasa.race.ifSome
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{IdAndNamePanel, StaticSelectionPanel}
import gov.nasa.race.uom.Angle
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.ww.{RaceLayerPickable, RaceView}
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.render._
import gov.nasa.race.ww._
import gov.nasa.worldwind.avlist.AVKey


class TraconSymbol(val tracon: Tracon, val layer: TATracksLayer) extends PointPlacemark(tracon.pos) with RaceLayerPickable {
  var showDisplayName = false
  var attrs = new PointPlacemarkAttributes

  //setValue( AVKey.DISPLAY_NAME, tracon.id)
  setLabelText(tracon.id)
  setAltitudeMode(WorldWind.RELATIVE_TO_GROUND)
  attrs.setImage(null)
  attrs.setLabelColor(layer.traconLabelColor)
  attrs.setLineColor(layer.traconLabelColor)
  attrs.setUsePointAsDefaultImage(true)
  attrs.setScale(7d)
  setAttributes(attrs)

  override def layerItem: AnyRef = tracon
}


/**
  * a layer to display TRACONs and related TATracks
  */
class TATracksLayer (raceView: RaceView,config: Config) extends FlightLayer3D[TATrack](raceView,config){

  //--- configured values

  val traconLabelColor = toABGRString(config.getColorOrElse("tracon-color", Color.white))
  var traconLabelThreshold = config.getDoubleOrElse("tracon-label-altitude", Meters(2200000.0).toMeters)
  val gotoAltitude = Feet(config.getDoubleOrElse("goto-altitude", 5000000d)) // feet above ground

  val traconGrid =  createGrid

  var selTracon: Option[Tracon] = None

  showTraconSymbols

  override def defaultPlaneImg = Images.getArrowImage(color)

  def createGrid = {
    val gridRings = config.getIntOrElse("tracon-rings", 5)
    val gridRingDist = NauticalMiles(config.getIntOrElse("tracon-ring-inc", 50)) // in nm
    val gridLineColor = config.getColorOrElse("tracon-grid-color", Color.white)
    val gridLineAlpha = config.getFloatOrElse("tracon-grid-alpha", 0.5f)
    val gridFillColor = config.getColorOrElse("tracon-bg-color", Color.black)
    val gridFillAlpha = config.getFloatOrElse("tracon-bg-alpha", 0.4f)

    new PolarGrid(Tracon.NoTracon.pos, Angle.Angle0, gridRingDist, gridRings,
      this, gridLineColor, gridLineAlpha, gridFillColor, gridFillAlpha)
  }

  override def createLayerInfoPanel = {
    new FlightLayerInfoPanel(raceView,this){
      // insert tracon selection panel after generic layer info
      contents.insert(1, new StaticSelectionPanel[Tracon,IdAndNamePanel[Tracon]]("select TRACON",
                                            Tracon.NoTracon +: Tracon.traconList, 40,
                                            new IdAndNamePanel[Tracon]( _.id, _.name), selectTracon).styled())
    }.styled('consolePanel)
  }

  /**
    * this has to be implemented in the concrete RaceLayer. It is executed in the
    * event dispatcher
    */
  override def handleMessage: Receive = {
    case BusEvent(_, track: TATrack, _) =>
      selTracon match {
        case Some(tracon) =>
          if (track.src == tracon.id) {
            count = count + 1
            if (track.status != Status.Drop) {
              flights.get(track.cs) match {
                case Some(acEntry) => updateFlightEntry(acEntry, track)
                case None => addFlightEntry(track)
              }
            } else {
              ifSome(flights.get(track.cs)) {
                removeFlightEntry
              }
            }
          }
        case None => // nothing selected, ignore
      }
  }

  def showTraconSymbol (tracon: Tracon) = {
    addRenderable(new TraconSymbol(tracon,this))
  }

  def showTraconSymbols = Tracon.traconList.foreach(showTraconSymbol)

  def selectTracon(tracon: Tracon) = raceView.trackUserAction(gotoTracon(tracon))

  def reset(): Unit = {
    removeAllRenderables()
    flights.clear()
    releaseAll
    traconGrid.hide
    showTraconSymbols
  }

  def setTracon(tracon: Tracon) = {
    raceView.panTo(tracon.pos, gotoAltitude.toMeters)

    selTracon = Some(tracon)
    traconGrid.setCenter(tracon.pos)
    traconGrid.show
    requestTopic(selTracon)
  }

  def gotoTracon(tracon: Tracon) = {
    if (tracon eq Tracon.NoTracon) {
      reset
    } else {
      selTracon match {
        case Some(`tracon`) => // nothing, we already show it
        case Some(lastTracon) =>
          reset
          setTracon(tracon)
        case None =>
          setTracon(tracon)
      }
    }
  }

}
