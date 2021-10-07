/*
 * Copyright (c) 2021, United States Government, as represented by the
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

import akka.actor.Actor.Receive
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import gov.nasa.race.common.JsonWriter
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.track.{TrackedObject, TrackedObjects}

import scala.collection.immutable.Iterable

/**
  * a RaceRoute that pushes TrackedObject updates over a websocket connection
  */
trait TrackWSRoute extends BasicPushWSRaceRoute {
  val flatten = config.getBooleanOrElse("flatten", false)
  val writer = new JsonWriter()

  // TBD - this will eventually handle client selections
  override protected def handleIncoming (ctx: BasicWSContext, m: Message): Iterable[Message] = {
    info(s"ignoring incoming message $m")
    discardMessage(m)
    Nil
  }

  // called from associated actor (different thread)
  override def receiveData: Receive = {

    case track: TrackedObject =>
      synchronized {
        writer.clear().writeObject( w=> track.serializeFormattedAs(w, "track"))
        push( TextMessage.Strict(writer.toJson))
      }

    case tracks: TrackedObjects[_] =>
      synchronized {
        if (flatten) {
          tracks.foreach { t =>
            writer.clear().writeObject(w => t.serializeFormattedAs(w, "track"))
            push(TextMessage.Strict(writer.toJson))
          }
        } else {
          writer.clear().writeObject(w => tracks.serializeFormattedAs(w, "trackList"))
          push(TextMessage.Strict(writer.toJson))
        }
      }

    case _ => // ignore
  }
}