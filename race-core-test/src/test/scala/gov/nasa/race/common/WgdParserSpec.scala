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
package gov.nasa.race.common

import gov.nasa.race.common.ConstAsciiSlice._
import gov.nasa.race.common.ConstUtf8Slice.utf8
import gov.nasa.race.common.JsonValueConverters._
import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec
import gov.nasa.race.common.StringJsonPullParser
import gov.nasa.race.uom.DateTime
import java.io.File
import scala.collection.mutable.ArrayBuffer
import scala.collection.{immutable, mutable}
import scala.collection.mutable.ArrayBuffer
import gov.nasa.race.common.ConstAsciiSlice.asc
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import gov.nasa.race.uom.DateTime
case class Coordinate(latitude: Double, longitude: Double)

case class WildfireGeolocationData(
                                    fireTextFile: Option[File] = None,
                                    date: Option[DateTime] = None,
                                    Incident_ID: Option[String] = None,
                                    Call_ID: Option[String] = None,
                                    Coordinates: Option[List[Coordinate]] = None,
                                    Incident_Report: Option[String] = None,
                                    Severity_Rating: Option[String] = None,
                                    Coordinate_Type: Option[String] = None
                                  )

class WgdParser extends StringJsonPullParser {
  val DATE = asc("date")
  val INCIDENT_ID = asc("Incident_ID")
  val CALL_ID = asc("Call_ID")
  val COORDINATES = asc("Coordinates")
  val INCIDENT_REPORT = asc("Incident_Report")
  val SEVERITY_RATING = asc("Severity_Rating")
  val COORDINATE_TYPE = asc("Coordinate_Type")

  private val latitudeSlice = asc("latitude")
  private val longitudeSlice = asc("longitude")

  def processResponse(response: String, importedFireTextData: File): Option[WildfireGeolocationData] = {
    println("Initializing parser...")
    if (initialize(response)) {
      try {
        val result = readNextObject(parseWildfireGeolocationData(importedFireTextData))
        result
      } catch {
        case _: Throwable =>
          println("Error parsing JSON: Invalid format")
          None
      }
    } else None
  }

  private def parseWildfireGeolocationData(importedFireTextData: File): Option[WildfireGeolocationData] = {
    println("Parsing WildfireGeolocationData...")
    val isoFormatter = DateTimeFormatter.ISO_ZONED_DATE_TIME

    var date: Option[DateTime] = None
    var incidentId: Option[String] = None
    var callId: Option[String] = None
    var coordinates: Option[List[Coordinate]] = None
    var incidentReport: Option[String] = None
    var severityRating: Option[String] = None
    var coordinateType: Option[String] = None

    foreachMemberInCurrentObject {
      case DATE =>
        println("Parsing date...")
        val dateStr = quotedValue.toString
        println(s"Date string: $dateStr")
        val parsedDate = ZonedDateTime.parse(dateStr, isoFormatter)
        date = Some(DateTime.ofEpochMillis(parsedDate.toInstant.toEpochMilli))
        println(s"Parsed date: $date")
      case INCIDENT_ID =>
        println("Parsing Incident_ID...")
        incidentId = Some(quotedValue.toString)
        println(s"Parsed Incident_ID: ${incidentId.get}")
      case CALL_ID =>
        println("Parsing Call_ID...")
        callId = Some(quotedValue.toString)
        println(s"Parsed Call_ID: ${callId.get}")
      case COORDINATES =>
        println("Parsing Coordinates...")
        coordinates = Some(readCoordinates())
        println(s"Parsed Coordinates: $coordinates")
      case INCIDENT_REPORT =>
        println("Parsing Incident_Report...")
        incidentReport = Some(quotedValue.toString)
        println(s"Parsed Incident_Report: ${incidentReport.get}")
      case SEVERITY_RATING =>
        println("Parsing Severity_Rating...")
        severityRating = Some(quotedValue.toString)
        println(s"Parsed Severity_Rating: ${severityRating.get}")
      case COORDINATE_TYPE =>
        println("Parsing Coordinate_Type...")
        coordinateType = Some(quotedValue.toString)
        println(s"Parsed Coordinate_Type: ${coordinateType.get}")
    }

    Some(WildfireGeolocationData(
      fireTextFile = Some(importedFireTextData),
      date = date,
      Incident_ID = incidentId,
      Call_ID = callId,
      Coordinates = coordinates,
      Incident_Report = incidentReport,
      Severity_Rating = severityRating,
      Coordinate_Type = coordinateType
    ))
  }

  private def readCoordinates(): List[Coordinate] = {
    val coordinatesBuffer = ArrayBuffer.empty[Coordinate]

    foreachElementInCurrentArray {
      parseCoordinateArray() match {
        case Some(coordinate) => coordinatesBuffer += coordinate
        case None => println("Error parsing coordinate")
      }
    }

    coordinatesBuffer.toList
  }

  private def parseCoordinateArray(): Option[Coordinate] = {
    val a = readCurrentDoubleArrayInto(ArrayBuffer.empty[Double])
    if (a.size == 2) {
      val coordinate = Coordinate(a(0), a(1))
      println(s"Parsed coordinate: $coordinate")
      Some(coordinate)
    } else {
      println("Error parsing coordinate array")
      None
    }
  }

}
// Assuming `latitudeSlice` and `longitudeSlice` are correctly defined earlier in your code.



/**
 * reg test for JsonPullParser
 */
class WgdParserSpec extends AnyFlatSpec with RaceSpec {
  "WgdParser" should "correctly parse coordinate arrays" in {
    val parser = new WgdParser()
    val dummyFile = new File("path/to/dummy/file")

    val jsonWithCoordinates = """
      {
        "Coordinates": [
          [34.0522, -118.2437],
          [24.05, -114.2437]
        ]
      }
    """

    val response = parser.processResponse(jsonWithCoordinates, dummyFile)
    response should not be None
    response.get.Coordinates.get.size shouldBe 2
    response.get.Coordinates.get.head shouldBe Coordinate(34.0522, -118.2437)
    response.get.Coordinates.get(1) shouldBe Coordinate(24.05, -114.2437)
  }

  "WgdParser" should "correctly parse a single JSON object into a WildfireGeolocationData object" in {
    val parser = new WgdParser()
    val dummyFile = new File("path/to/dummy/file") // Replace with an appropriate dummy file path

    val singleJson = """
      {
        "date": "2023-04-16T15:30:00Z",
        "Incident_ID": "WF002",
        "Call_ID": "CALL1002",
        "Coordinates": [[34.0522, -118.2437], [24.05, -114.2437]],
        "Incident_Report": "Multiple fire outbreaks near coastal area",
        "Severity_Rating": "Moderate",
        "Coordinate_Type": "GPS"
      }
    """

    val parsedData = parser.processResponse(singleJson, dummyFile)

    parsedData should not be None
    parsedData.foreach(println) // Print the Wgd object for manual verification
  }

  it should "handle invalid JSON format gracefully" in {
    val parser = new WgdParser()
    val dummyFile = new File("path/to/dummy/file")

    val invalidJson = """{ "invalid": "json" }"""

    val parsedData = parser.processResponse(invalidJson, dummyFile)

    parsedData shouldBe Some(
      WildfireGeolocationData(
        Some(dummyFile), None, None, None, None, None, None, None
      )
    )
  }

  it should "parse JSON with missing optional fields correctly" in {
    val parser = new WgdParser()
    val dummyFile = new File("path/to/dummy/file")

    val jsonWithMissingFields = """
      {
        "Incident_ID": "WF005",
        "Coordinates": [[35.0522, -119.2437]]
      }
    """

    val parsedData = parser.processResponse(jsonWithMissingFields, dummyFile)

    parsedData should not be None
    parsedData.get.Incident_ID shouldBe Some("WF005")
    parsedData.get.date shouldBe None // Date is missing, so it should be None
    parsedData.get.Coordinates.get.size shouldBe 1
  }

  it should "parse JSON with multiple coordinate objects correctly" in {
    val parser = new WgdParser()
    val dummyFile = new File("path/to/dummy/file")

    val jsonMultipleCoordinates = """
      {
        "date": "2023-04-17T09:45:00Z",
        "Incident_ID": "WF003",
        "Coordinates": [[36.0522, -120.2437], [25.05, -115.2437]]
      }
    """

    val parsedData = parser.processResponse(jsonMultipleCoordinates, dummyFile)

    parsedData should not be None
    parsedData.get.Coordinates.get.size shouldBe 2 // Expecting two coordinates
  }



  it should "return a list of coordinates when parsing valid JSON" in {
    val parser = new WgdParser()
    val dummyFile = new File("path/to/dummy/file")

    val jsonWithCoordinates = """
    {
      "Coordinates": [
        [34.0522, -118.2437],
        [24.05, -114.2437]
      ]
    }
  """

    val parsedData = parser.processResponse(jsonWithCoordinates, dummyFile)
    parsedData should not be None

    val coordinates = parsedData.get.Coordinates.getOrElse(Nil)
    coordinates.size shouldBe 2
    coordinates.head shouldBe Coordinate(34.0522, -118.2437)
    coordinates(1) shouldBe Coordinate(24.05, -114.2437)
  }

  it should "return an empty list of coordinates when Coordinates are missing in JSON" in {
    val parser = new WgdParser()
    val dummyFile = new File("path/to/dummy/file")

    val jsonWithMissingCoordinates = """
    {
      "Incident_ID": "WF005"
    }
    """

    val parsedData = parser.processResponse(jsonWithMissingCoordinates, dummyFile)
    parsedData should not be None

    val coordinates = parsedData.get.Coordinates.getOrElse(Nil)
    coordinates shouldBe empty
  }
  it should "handle empty JSON object gracefully" in {
    val parser = new WgdParser()
    val dummyFile = new File("path\\to\\dummy\\file")

    val emptyJson = "{}"

    val parsedData = parser.processResponse(emptyJson, dummyFile)

    parsedData shouldBe Some(
      WildfireGeolocationData(
        Some(dummyFile), None, None, None, None, None, None, None
      )
    )
  }

  it should "handle empty Coordinates array gracefully" in {
    val parser = new WgdParser()
    val dummyFile = new File("path\\to\\dummy\\file")

    val emptyCoordinatesJson = """
  {
    "Coordinates": []
  }
  """

    val parsedData = parser.processResponse(emptyCoordinatesJson, dummyFile)

    parsedData should not be None
    parsedData.get.Coordinates shouldBe Some(Nil)
  }

  it should "handle missing Coordinates field gracefully" in {
    val parser = new WgdParser()
    val dummyFile = new File("path\\to\\dummy\\file")

    val missingCoordinatesJson = """
  {
    "date": "2023-04-16T15:30:00Z",
    "Incident_ID": "WF002",
    "Call_ID": "CALL1002",
    "Incident_Report": "Multiple fire outbreaks near coastal area",
    "Severity_Rating": "Moderate",
    "Coordinate_Type": "GPS"
  }
  """

    val parsedData = parser.processResponse(missingCoordinatesJson, dummyFile)

    parsedData should not be None
    parsedData.get.Coordinates shouldBe None
  }

  it should "handle missing optional fields gracefully" in {
    val parser = new WgdParser()
    val dummyFile = new File("path\\to\\dummy\\file")

    val jsonWithMissingOptionalFields = """
  {
    "Incident_ID": "WF005"
  }
  """

    val parsedData = parser.processResponse(jsonWithMissingOptionalFields, dummyFile)

    parsedData should not be None
    parsedData.get.Incident_ID shouldBe Some("WF005")
    parsedData.get.date shouldBe None // Date is missing, so it should be None
    parsedData.get.Call_ID shouldBe None
    parsedData.get.Coordinates shouldBe None
    parsedData.get.Incident_Report shouldBe None
    parsedData.get.Severity_Rating shouldBe None
    parsedData.get.Coordinate_Type shouldBe None
  }
  // Additional test cases can be added here to cover other scenarios
}
