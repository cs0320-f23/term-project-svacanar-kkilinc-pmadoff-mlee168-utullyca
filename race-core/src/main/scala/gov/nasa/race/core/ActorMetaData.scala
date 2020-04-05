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
package gov.nasa.race.core

import akka.actor.ActorRef
import com.typesafe.config.Config

/**
  * housekeeping meta-data to manage child actors
  */
class ActorMetaData(val actorRef: ActorRef, val config: Config) {
  var pingNanos: Long = 0 // when did we last send a ping
  var receivedNanos: Long = 0 // when actor process last ping
  var isUnresponsive: Boolean = false // this needs to be set explicitly to avoid blocking a pre-start termination
}
