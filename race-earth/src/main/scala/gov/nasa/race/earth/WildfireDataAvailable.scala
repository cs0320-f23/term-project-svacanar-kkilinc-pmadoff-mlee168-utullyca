package gov.nasa.race.earth

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.DateTime
import java.io.File
import scala.Option

// Added in defaulting None behavior (some will error and not return during API call )
// Only Publish this once its fully formatted and ready to be sent to the client
case class WildfireText(
    audioFile: Option[File] = None, // Audio MP3 file (just text)

)

case class WildfireFVInfo(
 // Written by the data locator (parsing MP3 + potential other text like social media)
  audioFile: Option[File] = None, // Audio MP3 file (just text)

  // Written By FireVoice API 
  date: Option[DateTime] = None,
  Incident_ID: Option[String] = None,
  Call_ID: Option[String] = None,
  Coordinates: Option[List[String]] = None, // Coordinates as List[String] with [latitude, longitude]
  Incident_Report: Option[String] = None,
  Severity_Rating: Option[String] = None,
  Coordinate_Type: Option[String] = None,

)


case class WildfireDataAvailable(

  // Files
  // by default all the fields are val (not var) so they are immutable.
  // var is potential source of race conditions (can be changed and dangerous)

  // Written by the data locator (parsing MP3 + potential other text like social media)
  audioFile: Option[File] = None, // Audio MP3 file (just text)

  // Written By FireVoice API 
  date: Option[DateTime] = None,
  Incident_ID: Option[String] = None,
  Call_ID: Option[String] = None,
  Coordinates: Option[List[String]] = None, // Coordinates as List[String] with [latitude, longitude]
  Incident_Report: Option[String] = None,
  Severity_Rating: Option[String] = None,
  Coordinate_Type: Option[String] = None,

  // Written By CloudFire Actor
  simReport: Option[String] = None, //metadata for the simulation (num sims, etc)
  fireFile: Option[File] = None, // GeoJSON file saved on local drive (only send fileName to the client, service knows where the file is stored)
  
) {

  // TODO: WE CANNOT PASS DOWN THE PHYSICAL FILE TO THE CLIENT
  // we just just send the filename and common prefix: /fire-perim/fireFileName
  def toJson(): String = {
    val coordJson = Coordinates.map(c => s""" "$c" """).mkString("[", ",", "]")
    s"""{
      |  "audioFile": "${audioFile.map(_.getPath).getOrElse("N/A")}",
      |  "fireFile": "${fireFile.map(_.getPath).getOrElse("N/A")}",
      |  "date": ${date.map(_.toEpochMillis).getOrElse("N/A")},
      |  "Incident_ID": "${Incident_ID.getOrElse("N/A")}",
      |  "Call_ID": "${Call_ID.getOrElse("N/A")}",
      |  "Coordinates": $coordJson,
      |  "Incident_Report": "${Incident_Report.getOrElse("N/A")}",
      |  "Severity_Rating": "${Severity_Rating.getOrElse("N/A")}",
      |  "Coordinate_Type": "${Coordinate_Type.getOrElse("N/A")}",
      |  "simReport": "${simReport.getOrElse("N/A")}"
      |}""".stripMargin
  }

  def toJsonWithUrl(fireUrl: Option[String]): String = {
    val coordJson = Coordinates.map(c => s""" "$c" """).mkString("[", ",", "]")
    s"""{
      |  "audioFile": "${audioFile.map(_.getPath).getOrElse("N/A")}",
      |  "fireFile": "${fireUrl.getOrElse("N/A")}",
      |  "date": ${date.map(_.toEpochMillis).getOrElse("N/A")},
      |  "Incident_ID": "${Incident_ID.getOrElse("N/A")}",
      |  "Call_ID": "${Call_ID.getOrElse("N/A")}",
      |  "Coordinates": $coordJson,
      |  "Incident_Report": "${Incident_Report.getOrElse("N/A")}",
      |  "Severity_Rating": "${Severity_Rating.getOrElse("N/A")}",
      |  "Coordinate_Type": "${Coordinate_Type.getOrElse("N/A")}",
      |  "simReport": "${simReport.getOrElse("N/A")}"
      |}""".stripMargin
  }
  def toJsonWithTwoUrls(audioUrl: Option[String], fireUrl: Option[String]): String = {
    val coordJson = Coordinates.map(c => s""" "$c" """).mkString("[", ",", "]")
    s"""{
      |  "audioFile": "${audioUrl.getOrElse("N/A")}",
      |  "fireFile": "${fireUrl.getOrElse("N/A")}",
      |  "date": ${date.map(_.toEpochMillis).getOrElse("N/A")},
      |  "Incident_ID": "${Incident_ID.getOrElse("N/A")}",
      |  "Call_ID": "${Call_ID.getOrElse("N/A")}",
      |  "Coordinates": $coordJson,
      |  "Incident_Report": "${Incident_Report.getOrElse("N/A")}",
      |  "Severity_Rating": "${Severity_Rating.getOrElse("N/A")}",
      |  "Coordinate_Type": "${Coordinate_Type.getOrElse("N/A")}",
      |  "simReport": "${simReport.getOrElse("N/A")}"
      |}""".stripMargin
  }
}
