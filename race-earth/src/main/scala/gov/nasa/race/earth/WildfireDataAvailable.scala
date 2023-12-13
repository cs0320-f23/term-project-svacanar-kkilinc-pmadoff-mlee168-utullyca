package gov.nasa.race.earth

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.DateTime
import java.io.File // the base of the file handling chain
import gov.nasa.race.http.{FileRetrieved, HttpActor}

// Added in defaulting None behavior (some will error and not return during API call )
// Only Publish this once its fully formatted and ready to be sent to the client
// First case class with just the fireTextFile
// I May actually just want to save the JSON file --> not actually parse these fields
case class Coordinate(latitude: Double, longitude: Double)

// Second case class extending WildfireDataUnstructuredText with additional fields from the FireVoice API
// Modify the WildfireGeolocationData case class
case class WildfireGeolocationData(
                                    fireTextFile: Option[File] = None,
                                    date: Option[DateTime] = None,
                                    Incident_ID: Option[String] = None,
                                    Call_ID: Option[String] = None,
                                    Coordinates: Option[List[Coordinate]] = None, // Now Coordinates is a List of Coordinate objects
                                    Incident_Report: Option[String] = None,
                                    Severity_Rating: Option[String] = None,
                                    Coordinate_Type: Option[String] = None
                                  ) {
  def toJson(): String = {
    // Serialize Coordinates as a list of JSON objects
    val coordJson = Coordinates.map(_.map(coord => s"""{"latitude": ${coord.latitude}, "longitude": ${coord.longitude}}""").mkString("[", ",", "]")).getOrElse("[]")
    s"""{
       |  "date": "${date.map(_.toString).getOrElse("N/A")}",
       |  "Incident_ID": "${Incident_ID.getOrElse("N/A")}",
       |  "Call_ID": "${Call_ID.getOrElse("N/A")}",
       |  "Coordinates": $coordJson,
       |  "Incident_Report": "${Incident_Report.getOrElse("N/A")}",
       |  "Severity_Rating": "${Severity_Rating.getOrElse("N/A")}",
       |  "Coordinate_Type": "${Coordinate_Type.getOrElse("N/A")}"
       |}""".stripMargin
  }
}

// Third case class that is the complete WildfireDataAvailable, including all previous data plus the CloudFire Actor data
case class WildfireDataAvailable(
                                  WildfireGeolocationData: WildfireGeolocationData,
                                  simReport: Option[String] = None, // Metadata for the simulation (num sims, etc)
                                  firePerimFile: Option[File] = None // GeoJSON file saved on local drive
                                ) {
  // TODO: We actually don't want to expose the backend datalocations to the frontend
  // Take in arguments for the route where the frontend can access the files, right now its the actual filepath and this is wrong

  def toJsonWithTwoUrls(fireTextUrl: String, firePerimUrl: String, id: String): String = {
    val geolocationJson = WildfireGeolocationData.toJson().dropRight(1)
    // Wrap in a fireVoiceLayer identifier so that the frontend understands the message and handles rendering apprioately
    s"""{
       |  "fireVoiceLayer": {
       |    "id": "$id",
       |    $geolocationJson,
       |    "fireTextUrl": "$fireTextUrl",
       |    "firePerimUrl": "$firePerimUrl",
       |    "simReportUrl": "${simReport.getOrElse("N/A")}"
       |  }
       |}""".stripMargin
  }


  def toJsonWithUrl (url: String ): String = {
    // Used for Layer HashMap that will serve individual files (can be any file). the id will be the call_id-incident_id
    // and will be set in the FireVoiceService Actor
    s"""{
       |  "fireVoiceLayer": {
       |     "url":"$url"
       |  }
       |}""".stripMargin
  }
}