package gov.nasa.race.earth.actor

import akka.event.Logging
import akka.actor.ActorRef
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, MediaTypes}
import com.typesafe.config.Config
import gov.nasa.race
import gov.nasa.race.ResultValue
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{ExternalProc, StringJsonPullParser}
import gov.nasa.race.core.{BusEvent, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.util.FileUtils
import gov.nasa.race.earth.{WildfireDataAvailable, WildfireGeolocationData}
import gov.nasa.race.http.{FileRetrieved, HttpActor}
import gov.nasa.race.uom.DateTime

import java.io.File
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.sys.process.Process
import scala.util.{Failure, Success}
import scala.io.Source
import scala.util.{Failure, Success}
import com.typesafe.config.Config
import gov.nasa.race.core.{PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.earth.{Gdal2Tiles, GdalContour, GdalPolygonize, GdalWarp, SmokeAvailable, WildfireDataAvailable}


import gov.nasa.race.http.HttpActor
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}

// Step 1: The actor's handleMessage method is defined to handle incoming messages.

// Step 2: When a WildfireGeolocationData message is received as a BusEvent, it triggers the following sequence:

// Step 3: Inside fetchWildfireData function:
//   - Extract the latitude and longitude coordinates from WildfireGeolocationData.
//   - Construct an API endpoint URL with the coordinates.
//   - Make an HTTP request to the API using the constructed URL.
//   - Upon successful completion of the HTTP request:
//       - Save the GeoJSON data to a file.
//       - Trigger the handleGeoJSON function with WildfireGeolocationData and the GeoJSON file.

// Step 4: Inside handleGeoJSON function:
//   - Log that it is starting to handle GeoJSON data for a specific incident.
//   - Attempt to save the GeoJSON data to a file.
//   - Publish the WildfireDataAvailable message with the updated data, including the path to the GeoJSON file.

// Step 5: Finally, when the updated WildfireDataAvailable message is published:
//   - It will be sent to any subscribers.
//   - The log message indicates that the data is being published.

// Additional Information:
// - The actor sends messages to itself using 'self ! Message' to synchronize its internal processing steps.
// - The actor publishes messages using the 'publish' method, which automatically wraps the message in a BusEvent for distribution to subscribers.

trait CloudFireActor extends PublishingRaceActor with SubscribingRaceActor {
  // unsure what might go here - may omit
}

case class Coordinates(lat: Double, lon: Double)
case class GeoJSONData(wildfireData: WildfireGeolocationData, geoJson: File)

// WebBrowser Accepts certain positions 
class CloudFireImportActor(val config: Config) extends CloudFireActor with HttpActor {

  // Again is this the correct logging?
  val apiUrl = config.getString("api-url")
  val dataDir: File = new File(config.getString("data-dir"))
  val timeout = 5.seconds
  val geoJsonDirPath = Paths.get(config.getString("geojson-dir"))

  // Initialize directories for GeoJSON storage
  if (!Files.exists(geoJsonDirPath)) {
    warning(s"GeoJSON directory does not exist, creating directory: $geoJsonDirPath")
    Files.createDirectories(geoJsonDirPath)
  } else {
    warning(s"GeoJSON directory already exists: $geoJsonDirPath")
  }

  /**
   * Fetches wildfire data from a specified API based on coordinates.
   *
   * @param coordinates Coordinates object containing latitude and longitude.
   */

  def fetchWildfireData(wfa: WildfireGeolocationData): Unit = {
    wfa.Coordinates match {
      case Some(List(latStr, lonStr)) =>
        try {
          val coordinates = Coordinates(latStr.toDouble, lonStr.toDouble)
          warning(s"Coordinates extracted: $coordinates")
          fetchDataFromAPI(coordinates, wfa)
        } catch {
          case e: NumberFormatException =>
            error("Invalid coordinate format")
        }
      case _ =>
        warning("Invalid or missing coordinates in WildfireDataAvailable message.")
    }
  }

  def fetchDataFromAPI(coordinates: Coordinates, wfa: WildfireGeolocationData): Unit = {
    val apiEndpoint = s"$apiUrl?lon=${coordinates.lon}&lat=${coordinates.lat}"
    warning(s"Fetching wildfire data from API: $apiEndpoint")

    // Define output file path (we define the output type)
    val filename = s"fire-${wfa.Call_ID.getOrElse("unknown")}-${wfa.Incident_ID.getOrElse("unknown")}.geojson"
    val outputFile = new File(dataDir.getPath, filename) // this is where the file is saved, we don't need an explicit function

    httpRequestFileWithTimeout(apiEndpoint, outputFile, 240.seconds, HttpMethods.GET).onComplete {
      case Success(file) =>
        warning(s"Download complete: ${file.getPath}")

        // Additional processing or actor messaging
        self ! GeoJSONData(wfa, outputFile)

      case Failure(exception) =>
        warning(s"Failed to fetch wildfire data: ${exception.getMessage}")
    }
  }

  // Assume Coordinates is a case class that exists
  // Assume GeoJsonData is a case class you would create to hold coordinates and the geoJson string

  /**
   * Saves GeoJSON data to a file.
   *
   * @param wfa     A WildfireDataAvailable object.
   * @param geoJson A GeoJSON string.
   * @return The file path where the data was saved.
   *
   *
   *         This also may not need to be used because the saving is taken care of inside the httpRequesWithTimeout
   */
  def saveGeoJSONToFile(wfa: WildfireGeolocationData, geoJson: String): String = {
    val filename = f"fire-${wfa.Call_ID}-${wfa.Incident_ID}.geojson"
    debug(s"Generated filename: $filename")
    val filePath = geoJsonDirPath.resolve(filename)
    Files.write(filePath, geoJson.getBytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    warning(s"Successfully written GeoJSON data to file: $filePath")
    filePath.toString
  }

  /**
   * Publishes wildfire perimeter data.
   *
   * @param wfa      A WildfireDataAvailable object.
   * @param filePath The file path where the GeoJSON data was saved.
   */

  def publishWildfireData(wfa: WildfireGeolocationData, firePerimFile: File): Unit = {
    // Create an instance of WildfireDataAvailable by extracting fields from wfa
    val updatedWfa = WildfireDataAvailable(
      WildfireGeolocationData = wfa,
      simReport = null, // Set simReport to null
      firePerimFile = Some(firePerimFile),
    )

    // Log the updatedWfa using 'warning'
    // You can customize the log message as needed
    warning(s"Publishing Wildfire Data:")

    //To make the publishing a BusEvent, you can directly utilize the publish method from the PublishingRaceActor trait,
    // which takes care of wrapping the message in a BusEvent
    val channel: String = "wfa-channel" // hardcode for right now
    publish(channel, updatedWfa) // This will automatically wrap updatedWfa in a BusEvent
  }


  /**
   * Handles GeoJSON data by saving it and then publishing.
   *
   * @param wfa     A WildfireDataAvailable object.
   * @param geoJson A GeoJSON string.
   */
  def handleGeoJSON(wfa: WildfireGeolocationData, fireFile: File): Unit = {
    warning(s"Starting to handle GeoJSON data for Incident_ID: ${wfa.Incident_ID}, Call_ID: ${wfa.Call_ID}, Coordinates: ${wfa.Coordinates}")
    try {
      //val filePath = saveGeoJSONToFile(wfa, geoJson)
      publishWildfireData(wfa, fireFile) // wfa fireFile field is type File
    } catch {
      case e: Exception =>
        error(s"Failed to handle GeoJSON for Incident_ID: ${wfa.Incident_ID}, Call_ID: ${wfa.Call_ID}, Coordinates: ${wfa.Coordinates}, Error: ${e.getMessage}")
    }
  }

  /**
   * Handles incoming messages of various types.
   *
   */
  override def handleMessage: PartialFunction[Any, Unit] = {
    // runtime type checking in the receiving
    // Everytime you publish a message it becomes a busEvent
    // case wildfireData: WildfireDataAvailable =>
    // channel, data, sender
    case BusEvent(_, wildfireData: WildfireGeolocationData, _) => // deconstructing fields
      warning("Received WildfireDataAvailable message.")
      fetchWildfireData(wildfireData)

    // is this bad? sending wfa with geoJSON like htis>
    // sent directly to the actor so we don't need a BusEvent
    case GeoJSONData(wildfireData: WildfireGeolocationData, geoJson: File) =>
      //warning(s"Received GeoJSONData message with coordinates: Lat = ${coordinates.lat}, Lon = ${coordinates.lon}")
      handleGeoJSON(wildfireData, geoJson)

  }
}

