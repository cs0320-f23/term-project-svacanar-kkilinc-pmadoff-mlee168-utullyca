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
package gov.nasa.race.share

import gov.nasa.race.common.{JsonMessageObject, JsonSerializable, JsonWriter}

object UpstreamChange {
  def online (id: String) = UpstreamChange(id,true)
  def offline (id: String) = UpstreamChange(id,false)
}

/**
  * event to indicate we have a new upstream selection
  *
  * if isOnline == false we lost connection to the respective upstream node
  */
case class UpstreamChange (id: String, isOnline: Boolean) extends JsonMessageObject {

  def serializeMembersTo (w: JsonWriter): Unit = {
    w.writeObjectMember("upstreamChange") {_
      .writeStringMember("id", id)
      .writeBooleanMember("isOnline", isOnline)
    }
  }
}
