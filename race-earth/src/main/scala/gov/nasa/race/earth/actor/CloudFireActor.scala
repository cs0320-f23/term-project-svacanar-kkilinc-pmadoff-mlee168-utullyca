package gov.nasa.race.earth.actor

import akka.event.Logging
import akka.actor.ActorRef
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, MediaTypes}
import com.typesafe.config.Config
import gov.nasa.race
import gov.nasa.race.ResultValue
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{ExternalProc, StringJsonPullParser}
import gov.nasa.race.core.{BusEvent, PublishingRaceActor, RaceContext, RegisterRaceActor, StartRaceActor, SubscribingRaceActor, TerminateRaceActor}
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
  val dataDir: File = new File(config.getString("data-dir"))
  val timeout = 120.seconds
  val geoJsonDirPath = Paths.get(config.getString("geojson-dir"))

  val pythonPath: File = new File(config.getString("python-exe")) // Python executable (for versioning purposes)
  val apiPath: File = new File(System.getProperty("user.dir"), config.getString("api-exe")) // Location of FV Flask Server
  val apiCwd: File = new File(System.getProperty("user.dir"), config.getString("api-cwd")) // Working directory
  val apiProcess = new CloudFireAPIProcess(pythonPath, Some(apiCwd), apiPath) // API wrapper to handle intialization
  var apiAvailable: Boolean = true //false
  val apiPort: String = config.getString("api-port") // Each port will specify a different endpoint
  val apiPortAvailable: String = config.getString("api-port-available") // Each port will specify a different endpoint

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
    s"""CloudFireImportActor Configuration:
       |Write To: $wt
       |Read From: $rf
       |Data Directory: ${dataDir.getAbsolutePath}
       |Timeout: $timeout
       |GeoJSON Directory Path: $geoJsonDirPath

       |pythonPath: ${pythonPath.getAbsolutePath}
       |apiPath: ${apiPath.getAbsolutePath}
       |apiCwd: ${apiCwd.getAbsolutePath}
       |apiPort: $apiPort
       |apiAvailable: $apiAvailable""".stripMargin
  )

  // Function to delete all files in the GeoJSON directory
  // Initialize directories for GeoJSON storage and clean if already exists
  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = {
    warning("onInitializeRaceActor: Initializing CloudFireImportActor.")
    initializeAndCleanGeoJSONDirectory()
    super.onInitializeRaceActor(rc, actorConf)
  }


  /**
   * Start the Race Actor
   *
   * @param originator the ActorRef of the originator
   * @return Boolean indicates success or failure
   */
  override def onStartRaceActor(originator: ActorRef): Boolean = {
    warning("Entered onStartRaceActor function")
    var runningProc: Process = startAPI // starts the Python server hosting model API and checks if it is available

    try {
      val result = super.onStartRaceActor(originator)
      warning(s"Successfully started Race Actor ${result}")
      result
    } catch {
      case e: Exception =>
        error(s"Failed to start Race Actor: ${e.getMessage}")
        false
    }
  }

  /**
   * Terminate the Race Actor (built-in akka method)
   *
   * @param originator the ActorRef of the originator
   * @return Boolean indicates success or failure
   */
  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    debug("Entered onTerminateRaceActor function")
    try {
      val stopFuture = stopAPI()
      Await.result(stopFuture, 5.seconds) // Fulfill the promise?
      stopFuture.onComplete {
        case Success(v) =>
          warning("CloudFire API shutdown status confirmed")
        case Failure(x) =>
          warning(s"CloudFire API shutdown status could not be confirmed: $x")
      }
      //runningProc.destroy()
      val result = super.onTerminateRaceActor(originator)
      warning("Successfully terminated Race Actor")
      result
    } catch {
      case e: Exception =>
        error(s"Failed to terminate Race Actor: ${e.getMessage}")
        false
    }
  }

  /**
   * Stops the API service.
   *
   * This function sends a request to the API server instructing it to stop.
   * It then sets `apiAvailable` to false to indicate that the API is no longer available.
   *
   * @return A future that resolves when the API server is successfully stopped.
   */
  def stopAPI(): Future[Unit] = { //Remember Future is for Aysnc operations (do we want this to be async though?)
    Future {
      // --> /stop_server and /process are flask endpoint (sends get request to stop_server endpoints)
      httpRequestStrict(apiPort.replace("/process", "/stop_server"), HttpMethods.GET) { //.replace (how to access different endpoints)
        case Success(strictEntity) =>
          warning("Finished stopping CloudFire API server")
          apiAvailable = false
        case Failure(x) =>
          warning(s"Failed to stop CloudFire API server: $x")
          apiAvailable = false
      }
    }
  }

  /**
   * Starts the API service.
   *
   * This function initiates the API process and waits for its availability.
   *
   * @return The process that runs the API service.
   */
  def startAPI: Process = {
    warning("Attempting to Launch API using startAPI process")

    // Invoke our API wrapper
    val runningProc = apiProcess.customExec()
    Thread.sleep(10000) // bad practice - need to remove? Also why not make a promise here?
    val serviceFuture = IsServiceAvailable()
    Await.result(serviceFuture, Duration.Inf)
    serviceFuture.onComplete {
      case Success(v) =>
        warning("startAPI: CloudFire Python API status confirmed using startAPI process")
      case Failure(x) =>
        warning(s"startAPI: CloudFire Python API status could not be confirmed: $x")
    }
    runningProc
  }

  /**
   * Checks if the API service is available.
   *
   * This function sends a request to the API server to check its availability.
   * It then sets `apiAvailable` to indicate the status.
   *
   * @return A future that resolves when the check is complete.
   */
  def IsServiceAvailable(): Future[Unit] = {
    Future {
      httpRequestStrict(apiPortAvailable, HttpMethods.GET) { // the /process endpoint should be able to accept GET + POST requests (GET for availability)
        case Success(strictEntity) =>
          warning("IsServiceAvailable: Finished initiating CloudFire Flask API")
          apiAvailable = true
        case Failure(x) =>
          warning(s"CloudFire Flask API is not initiated: $x")
          apiAvailable = false
      }
    }
  }




  // Function to initialize and clean the GeoJSON directory
  private def initializeAndCleanGeoJSONDirectory(): Unit = {
    if (Files.exists(geoJsonDirPath)) {
      warning(s"GeoJSON directory exists: $geoJsonDirPath. Cleaning up...")
      cleanGeoJSONDirectory()
    } else {
      warning(s"GeoJSON directory does not exist, creating: $geoJsonDirPath")
      Files.createDirectories(geoJsonDirPath)
    }
  }

  // Function to delete all files in the GeoJSON directory
  private def cleanGeoJSONDirectory(): Unit = {
    try {
      val files = geoJsonDirPath.toFile.listFiles()
      if (files != null && files.nonEmpty) {
        files.foreach { file =>
          if (file.isFile && file.delete()) {
            warning(s"Deleted file: ${file.getPath}")
          } else {
            warning(s"Failed to delete file: ${file.getPath}")
          }
        }
      }

      // Recheck the directory to confirm deletion
      val remainingFiles = geoJsonDirPath.toFile.listFiles()
      if (remainingFiles == null || remainingFiles.isEmpty) {
        warning("All files successfully deleted from GeoJSON directory.")
      } else {
        warning("Files remaining in GeoJSON directory after deletion attempt:")
        remainingFiles.foreach(file => warning(s"  - ${file.getPath}"))
      }
    } catch {
      case e: IOException =>
        warning(s"Error while cleaning GeoJSON directory: ${e.getMessage}")
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
            warning("Checking API availability.")
            val serviceFuture = IsServiceAvailable() // Returns a Future

            Await.result(serviceFuture, 3.seconds) // Comment out bc we are using .onComplete (redundant?)
            serviceFuture.onComplete {
              case Success(_) =>
                warning("CloudFire API is available, proceeding with HTTP request preparation.")
              case Failure(x) =>
                warning(s"CloudFire API HTTP status could not be confirmed: $x")
            }
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
    val apiEndpoint = s"$apiPort?lon=${coordinates.longitude}&lat=${coordinates.latitude}"
    warning(s"Constructed API Endpoint: $apiEndpoint")

    if (apiAvailable) {
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

      // POST on the FLASK API
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
    } else {
      warning("CloudFire API is not available. Aborting fetchDataFromAPI.")
    }
  }

  def isValidGeoJSON(file: File): Boolean = {
    if (!file.exists() || file.length() < 200) {
      warning(s"File does not exist or is too small: ${file.getPath}")
      val content = scala.io.Source.fromFile(file).getLines().mkString
      warning(s"Invalid GeoJSON data in file ${file.getPath}: $content")

      return false
    }

    val content = scala.io.Source.fromFile(file).getLines().mkString
    if (content.contains("Internal Server Error")) {
      warning(s"Invalid GeoJSON data in file ${file.getPath}: $content")
      return false
    }

    true // Return true if none of the above conditions are met
  }


  // Assume Coordinates is a case class that exists
  // Assume GeoJsonData is a case class you would create to hold coordinates and the geoJson string

  /**
   * Publishes wildfire perimeter data.
   *
   * @param wfa      A WildfireDataAvailable object.
   * @param filePath The file path where the GeoJSON data was saved.
   */

  def publishWildfireData(wfa: WildfireGeolocationData, firePerimFile: File): Unit = {
    warning(s"Publishing Wildfire Data: Type - ${wfa.getClass.getSimpleName}, Content - $wfa , File: $firePerimFile")
    val updatedWfa = WildfireDataAvailable(
      WildfireGeolocationData = wfa,
      simReport = null, // Set simReport to null
      firePerimFile = Some(firePerimFile),
    )

    // Log the updatedWfa using 'warning'
    // You can customize the log message as needed

    //To make the publishing a BusEvent, you can directly utilize the publish method from the PublishingRaceActor trait,
    // which takes care of wrapping the message in a BusEvent
    val channel: String = wt // hardcode for right now
    warning(s"Publishing Wildfire Data to channel $channel")
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
          //error(s"Failed to handle GeoJSON for Incident_ID: ${wfa.Incident_ID}, Call_ID: ${wfa.Call_ID}, Coordinates: ${wfa.Coordinates}, Error: ${e.getMessage}")
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


class CloudFireAPIProcess(val prog: File, override val cwd: Some[File], val apiPath: File) extends ExternalProc[Boolean] {


  if (!prog.isFile) {
    throw new RuntimeException(s"Python executable not found: $prog")
  }

  if (!apiPath.isFile) {
    throw new RuntimeException(s"Smoke Segmentation API run script not found: $apiPath")
  }

  /**
   * Constructs and returns the command to start the FireVoice API service.
   * This command includes the path to the Python executable and the script
   * that runs the API service, along with any required arguments and options.
   *
   * Example:
   * Assuming /usr/bin/python3 is the path to Python executable and
   * /path/to/firevoice_api.py is the path to the FireVoice API script,
   * the returned command string would be "/usr/bin/python3 /path/to/firevoice_api.py".
   *
   * @return StringBuilder containing the full command to start the API service.
   * @throws RuntimeException if the Python executable or API script is not found.
   */
  protected override def buildCommand: StringBuilder = {
    //warning("Building command to start the FireVoice API process.")
    args = List(
      s"$apiPath"
    )
    val builtCommand = super.buildCommand
    //warning(s"Command built: $builtCommand")
    builtCommand
  }

  /**
   * Provides the value to indicate a successful process run.
   *
   * This method is intended to be overridden for different types of external processes.
   */
  override def getSuccessValue: Boolean = {
    // warning("Returning the success value for FireVoice API process.")
    true
  }

  /**
   * Executes the command to start the FireVoice API service in an external process.
   * The process output (stdout and stderr) is optionally logged if a logger is available.
   * The method returns a Process instance representing the running external service.
   *
   * Example:
   * The external FireVoice API service is started, and its output is logged.
   * The method returns a Process instance for further interaction or monitoring.
   *
   * @return Process instance representing the running external FireVoice API service.
   */

  def customExec(): Process = {
    val proc = log match {
      case Some(logger) => Process(buildCommand.toString(), cwd, env: _*).run(logger)
      case None => Process(buildCommand.toString(), cwd, env: _*).run()
    }
    proc
  }

}
