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
import gov.nasa.race.earth.{WildfireDataAvailable}
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
import scala.util.{Success, Failure}

import com.typesafe.config.Config
import gov.nasa.race.core.{PublishingRaceActor, SubscribingRaceActor}
//import gov.nasa.race.earth.WildfireDataAvailable
import gov.nasa.race.earth.{Gdal2Tiles, GdalContour, GdalPolygonize, GdalWarp, SmokeAvailable, WildfireDataAvailable}

// do we need to bring these into scope
// import akka.actor.ActorSystem
// import akka.stream.Materializer

import gov.nasa.race.http.HttpActor
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}

trait CloudFireActor extends PublishingRaceActor with SubscribingRaceActor {
  // unsure what might go here - may omit
}

case class Coordinates(lat: Double, lon: Double)

// One perimeter per call assumption: we can just make it ensemble prediction if needed
// Important:: CANNOT have case class as a super class
case class WildfirePerim(dataAvailable: WildfireDataAvailable, geoJsonFilePath: String) // We pass the URL to the geoJSON and not the actual JSON here
case class GeoJSONData(wfa: WildfireDataAvailable, geoJson: File)
// ^^ Both these are bad :: TODO: CHANGE

// WebBrowser Accepts certain positions 
class CloudFireImportActor(val config: Config) extends CloudFireActor with HttpActor {

  // Is this really nessecary??????
  // implicit val system: ActorSystem = context.system
  // implicit val materializer: Materializer = Materializer(system)


  // Again is this the correct logging?
  val log = Logging(context.system, this)
  val apiUrl = config.getString("api-url")
  val dataDir: File = new File(config.getString("data-dir"), "tifs")

  val timeout = 5.seconds
  val geoJsonDirPath = Paths.get(config.getString("geojson-dir"))

  // Initialize directories for GeoJSON storage
  if (!Files.exists(geoJsonDirPath)) {
    log.info(s"GeoJSON directory does not exist, creating directory: $geoJsonDirPath")
    Files.createDirectories(geoJsonDirPath)
  } else {
    log.info(s"GeoJSON directory already exists: $geoJsonDirPath")
  }

  /**
   * Fetches wildfire data from a specified API based on coordinates.
   * @param coordinates Coordinates object containing latitude and longitude.
   */

  def fetchWildfireData(wfa: WildfireDataAvailable): Unit = {
    // Define coordinates as an Option outside the match scope
    val maybeCoordinates: Option[Coordinates] = wfa.Coordinates match {
      case Some(List(lat, lon)) => // Assuming the list always contains two elements
        log.info(s"Coordinates extracted: Lat = $lat, Lon = $lon")
        // Convert lat and lon to Double and create Coordinates
        try {
          Some(Coordinates(lat.toDouble, lon.toDouble))
        } catch {
          case e: NumberFormatException =>
            log.error("Latitude and Longitude conversion error", e)
            None
        }

      case Some(_) =>
        log.warning("Coordinates list does not contain exactly two elements.")
        None // Explicitly return None for this case
      case None =>
        log.warning("No coordinates available in the WildfireDataAvailable message.")
        None // Explicitly return None for this case
    }

    // Process the coordinates if they are available
    maybeCoordinates.foreach { coordinates =>
      log.info(s"Initiating fetch for wildfire data for coordinates: Lat = ${coordinates.lat}, Lon = ${coordinates.lon}")
      val apiEndpoint = s"$apiUrl?lon=${coordinates.lon}&lat=${coordinates.lat}"
      log.debug(s"Generated API Endpoint: $apiEndpoint")
      
      // Path for the geoJson data
      val filePath =  f"fire-${wfa.Call_ID}-${wfa.Incident_ID}"
      val outputFile: File = new File(dataDir.getPath, filePath) // What is the file extension here?
      
      maybeCoordinates.foreach { coordinates =>
        log.info(s"Initiating fetch for wildfire data for coordinates: Lat = ${coordinates.lat}, Lon = ${coordinates.lon}")
        val apiEndpoint = s"$apiUrl?lon=${coordinates.lon}&lat=${coordinates.lat}"
        log.debug(s"Generated API Endpoint: $apiEndpoint")
        
        // Define the output file path for the GeoJSON data
        val filename = s"fire-${wfa.Call_ID}-${wfa.Incident_ID}.geojson"
        val outputFile = new File(dataDir.getPath, filename) // make sure this is not leaked
        
        // Make the HTTP request and handle the response
        // This thread is not executed inside the actor thread --> potential race conditions
        // Same actor would execute same code for the same data --> the field in the cloudfire actor would be subject to race
        // Partial Function: make it exclusive ()
        // because this is a native HTTPActor instance
        httpRequestFileWithTimeout(apiEndpoint, outputFile, 240.seconds, HttpMethods.GET).onComplete {
          case Success(f) =>
            info(s"Download complete: ${f.getPath}")
            // Create a new instance of WildfireDataAvailable with the updated file path
            self ! GeoJSONData(wfa, f) // <-- this is the syncronization (go through actor messages)
          case Failure(x) =>
            warning(s"Download failed: $x")
        }
      }

    }
  }

// Assume Coordinates is a case class that exists
// Assume GeoJsonData is a case class you would create to hold coordinates and the geoJson string

  /**
   * Saves GeoJSON data to a file.
   * @param wfa A WildfireDataAvailable object.
   * @param geoJson A GeoJSON string.
   * @return The file path where the data was saved.
   * 
   * 
   * This also may not need to be used because the saving is taken care of inside the httpRequesWithTimeout
   */
  // def saveGeoJSONToFile(wfa: WildfireDataAvailable, geoJson: String): String = {
  //   val filename = f"fire-${wfa.Call_ID}-${wfa.Incident_ID}.geojson"
  //   log.debug(s"Generated filename: $filename")
  //   val filePath = geoJsonDirPath.resolve(filename)
  //   Files.write(filePath, geoJson.getBytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
  //   log.info(s"Successfully written GeoJSON data to file: $filePath")
  //   filePath.toString
  // }

  /**
   * Publishes wildfire perimeter data.
   * @param wfa A WildfireDataAvailable object.
   * @param filePath The file path where the GeoJSON data was saved.
   */

  // TODO: change this to WFA (with the updated geoJSON?): do we need a seperate case class for the geoJSON
  def publishWildfireData(wfa: WildfireDataAvailable, fireFile: File): Unit = {
    //val wildfirePerim = WildfirePerim(wfa, filePath)
    //publish(wildfirePerim)
    //log.info(s"Published wildfire perimeter data: $wildfirePerim")

    // Creating the copy:
    val updatedWfa = wfa.copy(fireFile = Some(fireFile)) // Wrapping `f` in an Option
    publish(updatedWfa)

  }

  /**
   * Handles GeoJSON data by saving it and then publishing.
   * @param wfa A WildfireDataAvailable object.
   * @param geoJson A GeoJSON string.
   */
  def handleGeoJSON(wfa: WildfireDataAvailable, fireFile: File): Unit = {
    log.info(s"Starting to handle GeoJSON data for Incident_ID: ${wfa.Incident_ID}, Call_ID: ${wfa.Call_ID}, Coordinates: ${wfa.Coordinates}")
    try {
      //val filePath = saveGeoJSONToFile(wfa, geoJson)
      publishWildfireData(wfa, fireFile) // wfa fireFile field is type File
    } catch {
      case e: Exception =>
        log.error(s"Failed to handle GeoJSON for Incident_ID: ${wfa.Incident_ID}, Call_ID: ${wfa.Call_ID}, Coordinates: ${wfa.Coordinates}, Error: ${e.getMessage}")
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
  case BusEvent(_,wildfireData:WildFireFVInfo,_) => // deconstructing fields
    log.info("Received WildfireDataAvailable message.")
    fetchWildfireData(wildfireData)

  // is this bad? sending wfa with geoJSON like htis>
  // sent directly to the actor so we don't need a BusEvent
  case GeoJSONData(wildfireData: WildfireDataAvailable, geoJson: File) =>
    //log.info(s"Received GeoJSONData message with coordinates: Lat = ${coordinates.lat}, Lon = ${coordinates.lon}")
    handleGeoJSON(wildfireData, geoJson)

  case other =>
    log.warning(s"Unhandled message: $other")
    super.handleMessage(other)
  }


  // Maybe we just subscribe to the busEvent of type WildFireDataAvailable?
  // These subscriptions? Should I subscribe to the channel or the event?
  // what is the peristence of messages within kafka? 
  // the lifecycle methods are inherited from Publishing + Subscribing (race-core)
  
  // override def onStartRaceActor(originator: ActorRef): Boolean = {
  //   if (super.onStartRaceActor(originator)) {
  //     subscribe(classOf[WildfireDataAvailable])
  //     log.debug("CloudFireImportActor is starting and subscribed to WildfireDataAvailable")
  //     true
  //   } else false
  // }

  // override def onTerminateRaceActor(originator: ActorRef): Boolean = {
  //   unsubscribe(classOf[WildfireDataAvailable])
  //   log.debug("CloudFireImportActor is terminating and unsubscribed from WildfireDataAvailable")
  //   super.onTerminateRaceActor(originator)
  // }
}

