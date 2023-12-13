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
import gov.nasa.race.earth.{WildfireDataAvailable, WildfireGeolocationData, Coordinate}
import gov.nasa.race.http.{FileRetrieved, HttpActor}
import gov.nasa.race.uom.DateTime
import scala.util.Try
import java.io.{File, IOException}

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

case class GeoJSONData(wildfireData: WildfireGeolocationData, geoJson: File)

// WebBrowser Accepts certain positions
class CloudFireImportActor(val config: Config) extends CloudFireActor with HttpActor {
  val wt = config.getString("write-to")
  val rf = config.getString("read-from")

  // Again is this the correct logging?
  val apiUrl = config.getString("api-url")
  val dataDir: File = new File(config.getString("data-dir"))
  val timeout = 35.seconds
  val geoJsonDirPath = Paths.get(config.getString("geojson-dir"))

  // Initialize directories for GeoJSON storage
  // Validate and log the contents of the GeoJSON directory
  if (Files.exists(geoJsonDirPath)) {
    warning(s"GeoJSON directory exists: $geoJsonDirPath")
    val files = Files.list(geoJsonDirPath).toArray.map(_.toString)
    warning(s"Files in GeoJSON directory: ${files.mkString(", ")}")
    cleanGeoJSONDirectory()
  } else {
    warning(s"GeoJSON directory does not exist, creating: $geoJsonDirPath")
    Files.createDirectories(geoJsonDirPath)
  }
  // Log the configuration details
  warning(
    s"CloudFireImportActor Configuration:\n" +
      s"Write To: $wt\n" +
      s"Read From: $rf\n" +
      s"API URL: $apiUrl\n" +
      s"Data Directory: ${dataDir.getAbsolutePath}\n" +
      s"Timeout: $timeout\n" +
      s"GeoJSON Directory Path: $geoJsonDirPath"
  )



  // Function to delete all files in the GeoJSON directory
  private def cleanGeoJSONDirectory(): Unit = {
    try {
      val files = geoJsonDirPath.toFile.listFiles()
      if (files != null) {
        for (file <- files) {
          if (file.isFile) {
            if (file.delete()) {
              warning(s"Deleted file: ${file.getPath}")
            } else {
              error(s"Failed to delete file: ${file.getPath}")
            }
          }
        }
      }
    } catch {
      case e: IOException =>
        error(s"Error while cleaning GeoJSON directory: ${e.getMessage}")
    }
  }

  /**
   * Fetches wildfire data from a specified API based on coordinates.
   *
   * @param coordinates Coordinates object containing latitude and longitude.
   */

  def fetchWildfireData(wfa: WildfireGeolocationData): Unit = {
    wfa.Coordinates match {
      case Some(coords) =>
        coords.foreach { coordinate =>
          Try {
            // As coordinate is already a Coordinate object, we can use it directly
            warning(s"Coordinates extracted: $coordinate")
            fetchDataFromAPI(coordinate, wfa)
          }.recover {
            case e: Exception =>
              error(s"Error processing coordinates '$coordinate': ${e.getMessage}")
          }
        }
      case None =>
        warning("Invalid or missing coordinates in WildfireDataAvailable message.")
    }
  }


  def fetchDataFromAPI(coordinates: Coordinate, wfa: WildfireGeolocationData): Unit = {
    warning(s"Preparing to fetch wildfire data for Coordinates: $coordinates")
    val apiEndpoint = s"$apiUrl?lon=${coordinates.longitude}&lat=${coordinates.latitude}"
    warning(s"Constructed API Endpoint: $apiEndpoint")

    // Create directory structure: [dataDir]/[Incident_ID]/[Call_ID]
    val incidentDir = Paths.get(dataDir.getPath, wfa.Incident_ID.getOrElse("unknown"))
    val callDir = incidentDir.resolve(wfa.Call_ID.getOrElse("unknown"))
    if (!Files.exists(callDir)) {
      Files.createDirectories(callDir)
      warning(s"Created directory: $callDir")
    }

    // Generate a unique filename for each perimeter within a call
    //    val timestamp = java.time.LocalDateTime.now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
    //    val filename = s"perimeter-$timestamp.geojson"
    //    val outputFile = callDir.resolve(filename).toFile
    // Generate a unique filename based on latitude and longitude
    val filename = s"perimeter-${coordinates.latitude}-${coordinates.longitude}.geojson"
    val outputFile = callDir.resolve(filename).toFile
    warning(s"Output file path set: ${outputFile.getPath}")

    warning(s"Initiating HTTP request to fetch GeoJSON data")
    httpRequestFileWithTimeout(apiEndpoint, outputFile, timeout, HttpMethods.GET).onComplete {
      case Success(file) =>
        warning(s"Download complete: ${file.getPath}")
        if (file.exists()) {
          warning(s"File exists. Size: ${file.length()} bytes.")
        } else {
          warning(s"File not found: ${file.getPath}")
        }
        warning(s"Successfully downloaded GeoJSON data to: ${file.getPath}")
        self ! GeoJSONData(wfa, outputFile)
      case Failure(exception) =>
        warning(s"Failed to fetch wildfire data: ${exception.getMessage}")
    }
  }

  def isValidGeoJSON(file: File): Boolean = {
    if (!file.exists() || file.length() < 200) return false

    val content = scala.io.Source.fromFile(file).getLines().mkString
    !content.contains("Internal Server Error")
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
    warning(s"Publishing Wildfire Data: Type - ${wfa.getClass.getSimpleName}, Content - $wfa")
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

    if (!isValidGeoJSON(fireFile)) {
      error(s"Invalid GeoJSON data for Incident_ID: ${wfa.Incident_ID}, Call_ID: ${wfa.Call_ID}. File: ${fireFile.getPath}")
      publishWildfireData(wfa, null) // Passing null for invalid fireFile
    } else {
      try {
        // TODO: Make this option of multiple files (handled already ig actually)
        val filesInDir = Option(fireFile.getParentFile.listFiles()).getOrElse(Array.empty).map(_.getName)
        warning(s"Files in directory after handling GeoJSON (${fireFile.getParentFile}): ${filesInDir.mkString(", ")}")

        publishWildfireData(wfa, fireFile)
      } catch {
        case e: Exception =>
          error(s"Failed to handle GeoJSON for Incident_ID: ${wfa.Incident_ID}, Call_ID: ${wfa.Call_ID}, Coordinates: ${wfa.Coordinates}, Error: ${e.getMessage}")
      }
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

    // is this bad? sending wfa with geoJSON like this>
    // sent directly to the actor so we don't need a BusEvent
    case GeoJSONData(wildfireData: WildfireGeolocationData, geoJson: File) =>
      //warning(s"Received GeoJSONData message with coordinates: Lat = ${coordinates.lat}, Lon = ${coordinates.lon}")
      handleGeoJSON(wildfireData, geoJson)

  }
}

