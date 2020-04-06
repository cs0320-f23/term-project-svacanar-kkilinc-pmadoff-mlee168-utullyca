/*
 * Copyright (c) 2016, United States Government, as represented by the
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

package gov.nasa.race.air

import gov.nasa.race.uom.DateTime
import gov.nasa.race.geo.{GeoPosition, GreatCircle}
import gov.nasa.race.track.TrackedObject
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom._

import scala.collection.Seq
import scala.collection.mutable.ArrayBuffer

/**
  * matchable type for a collection of TFMTracks
  */
trait TfmTracks extends Seq[TfmTrack]

object TfmTracks {
  val empty = new ArrayBuffer[TfmTrack](0) with TfmTracks
}


/**
  * object representing a Traffic Flow Management (TFM) track update
  */
case class TfmTrack(id: String,
                    cs: String,
                    position: GeoPosition,
                    speed: Speed,
                    date: DateTime,
                    status: Int,

                    src: String,
                    nextPos: Option[GeoPosition],
                    nextDate: DateTime // might be undefined
                   ) extends TrackedObject {

  val heading = if (nextPos.isDefined) GreatCircle.initialBearing(position,nextPos.get) else Degrees(0)
  def vr = Speed.UndefinedSpeed // not in current data model

  override def source: Option[String] = Some(src)
}