/*
 * Copyright (c) 2019, United States Government, as represented by the
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
package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race.actor.FlatFilteringPublisher
import gov.nasa.race.air.translator.TfmDataServiceParser
import gov.nasa.race.jms.{JMSImportActor, TranslatingJMSImportActor}
import javax.jms.Message

/**
  * specialized JMSImportActor that translates SWIM tfmData messages into TfmTracks objects
  */
class TfmDataImportActor (config: Config) extends JMSImportActor(config) with TranslatingJMSImportActor
                                             with FlatFilteringPublisher {
  val parser = new TfmDataServiceParser
  parser.setElementsReusable(flatten)

  override def translate (msg: Message): Any = parser.parseTracks(getContentSlice(msg))
}
