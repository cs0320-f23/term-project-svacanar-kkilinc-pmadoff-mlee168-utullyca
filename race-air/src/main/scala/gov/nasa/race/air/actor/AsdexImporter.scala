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

package gov.nasa.race.air.actor

import gov.nasa.race.air.Airport
import scala.util.matching.Regex

/**
  * trait to handle conditional ASDE-X imports, filtered by requested airports
  */
trait AsdexImporter extends SubjectImporter[Airport] {

  override def topicSubject (topic: Any): Option[Airport] = {
    topic match {
      case Some(airport:Airport) => Airport.asdexAirports.get(airport.id)
      case Some(airportId: String) => Airport.asdexAirports.get(airportId)
      case _ => None
    }
  }
  override def subjectRegex(airport: Airport): Option[Regex] = Some(s"<airport>${airport.id}</airport>".r)
}
