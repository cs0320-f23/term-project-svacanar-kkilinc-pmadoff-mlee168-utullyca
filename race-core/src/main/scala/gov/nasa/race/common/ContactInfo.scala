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
package gov.nasa.race.common

case class PhoneNumber (number: String, description: String="")


object ContactInfo {
  def apply(street: String, city: String, state: String, zip: String, phone: String=null) = {
    if (phone != null) new ContactInfo(street,city,state,zip,Seq(PhoneNumber(phone)))
    else new ContactInfo(street,city,state,zip,Seq.empty[PhoneNumber])
  }
}

/**
  * address and related data
  */
case class ContactInfo (street: String, city: String, state: String, zip: String, phones: Seq[PhoneNumber])