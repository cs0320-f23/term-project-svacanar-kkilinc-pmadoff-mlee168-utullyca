/*
 * Copyright (c) 2022, United States Government, as represented by the
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

package gov.nasa.race.earth.actor

//adding logging capabilities w/ built in akka
// import akka.event.Logging

import scala.util.Try
import scala.collection.mutable
import akka.actor.ActorRef
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, MediaTypes}
import akka.http.scaladsl.model.ContentTypes.`application/json`
import com.typesafe.config.Config
import gov.nasa.race
import gov.nasa.race.ResultValue
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{BufferedStringJsonPullParser, CharSeqByteSlice, ConstAsciiSlice, ExternalProc, JsonPullParser, JsonWriter, StringJsonPullParser}
import gov.nasa.race.core.{BusEvent, PublishingRaceActor, RaceContext, RegisterRaceActor, StartRaceActor, SubscribingRaceActor, TerminateRaceActor}
import gov.nasa.race.util.FileUtils
import gov.nasa.race.earth
import gov.nasa.race.earth.WildfireGeolocationData
import gov.nasa.race.earth.Coordinate
import gov.nasa.race.http.{FileRetrieved, HttpActor}
import gov.nasa.race.uom.DateTime

import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.sys.process.Process
import scala.util.{Failure, Success}
import scala.collection.mutable

trait  FireVoiceActor extends PublishingRaceActor with SubscribingRaceActor{
  // unsure what might go here - may omit
}

/**
 * a import actor class which performs image segmentation to get smoke and cloud geojsons
 * @param config
 *
 * Question: What is the standarization of the File dtype?
 * How is this used in functions are we using the raw bytes?
 */

class FireVoiceImportActor(val config: Config) extends FireVoiceActor with HttpActor {

  //Creation of case classes that are only used internally within the actor
  //Basically a substitution of fields but are immutable
  case class FireVoiceJsonData(
                                date: DateTime,
                                Incident_ID: String,
                                Call_ID: String,
                                Coordinates: String, // You may need to parse this string to a Coordinate type
                                Incident_Report: String,
                                Severity_Rating: Int,
                                Coordinate_Type: String
                              )

  /**
   * Configure the directory paths
   *
   * Data is stored in a external local folder in RACE
   */

  val dataDir: File = new File(config.getString("data-dir"), "fire_calls") // Create a directory with fire_calls subfolder
  val pythonPath: File = new File(config.getString("python-exe")) // Python executable (for versioning purposes)
  val apiPath: File = new File(System.getProperty("user.dir"), config.getString("api-exe")) // Location of FV Flask Server
  val apiCwd: File = new File(System.getProperty("user.dir"), config.getString("api-cwd")) // Working directory
  val apiProcess = new FireVoiceAPIProcess(pythonPath, Some(apiCwd), apiPath) // API wrapper to handle intialization
  val apiPort: String = config.getString("api-port") // Each port will specify a different endpoint
  //var runningProc:Process = startAPI // starts the Python server hosting model API and checks if it is available
  var apiAvailable: Boolean = true //false
  val writer = new JsonWriter()

  // Intialize the Akka Logging capabilities
  // val log = Logging(context.system, this)
  warning(
    s"""
       |FireVoiceImportActor Fields:
       |dataDir: ${dataDir.getAbsolutePath}
       |pythonPath: ${pythonPath.getAbsolutePath}
       |apiPath: ${apiPath.getAbsolutePath}
       |apiCwd: ${apiCwd.getAbsolutePath}
       |apiPort: $apiPort
       |apiAvailable: $apiAvailable
  """.stripMargin
  )

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = {
    warning("onInitializeRaceActor: Initializing FireVoiceImportActor.")
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
          warning("FireVoice API shutdown status confirmed")
        case Failure(x) =>
          warning(s"FireVoice API shutdown status could not be confirmed: $x")
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
          warning("Finished stopping FireVoice API server")
          apiAvailable = false
        case Failure(x) =>
          warning(s"Failed to stop FireVoice API server: $x")
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
        warning("startAPI: FireVoice Python API status confirmed using startAPI process")
      case Failure(x) =>
        warning(s"startAPI: FireVoice Python API status could not be confirmed: $x")
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
      httpRequestStrict(apiPort, HttpMethods.GET) { // the /process endpoint should be able to accept GET + POST requests (GET for availability)
        case Success(strictEntity) =>
          warning("IsServiceAvailable: Finished initiating FireVoice Flask API")
          apiAvailable = true
        case Failure(x) =>
          warning(s"FireVoice Flask API is not initiated: $x")
          apiAvailable = false
      }
    }
  }


  override def handleMessage = handleFireMessage orElse super.handleMessage


  /**
   * Handles fire-related messages received through the message bus.
   *
   * The channel is a higher-level construct in Akka, channels define the ActorRefs. BusEvent adds the sender + channel
   *
   * The function matches against the message type and content, performing different actions based on the match:
   * - If the message is of type `BusEvent` and contains a `FileRetrieved` message with a file path ending in ".mp3",
   * it initiates a FireVoice geolocation call request.
   * - If the message is of type `BusEvent` but the file is not an mp3, it logs a warning.
   * - If the message is of type `WildfireData`, it publishes the wildfire data.
   *
   * FileRetrieved --> WildfireGeoLocation Data (Synch) --> Publish
   * This function is intended to be used as a message handler in an actor system, specifically for handling messages related to fire incidents.
   *
   * @return A `Receive` partial function that handles specific message types and performs actions accordingly.
   */


  def handleFireMessage: Receive = {
    case msg@BusEvent(_, fileRetrieved: FileRetrieved, _) =>
      val fileName = fileRetrieved.req.file.getName
      val fileType = if (fileName.endsWith(".json")) "json" else "non-mp3"
      warning(s"Received a $fileType file: $fileName with message: $msg")

      fileType match {
        case "json" => makeFireVoiceGeolocateCallRequest(fileRetrieved)
        case _ => warning(s"Received a non-mp3 file: $fileName")
      }

    case wildfireData: WildfireGeolocationData =>
      warning(s"Handling direct WildfireGeolocationData message: $wildfireData")
      publishWildfireData(wildfireData)
  }


  /**
   * Initiates an HTTP request to a specified API endpoint with a file and processes the JSON response.
   * This is broad method that can handle changing endpoints if needed, different endpoints will need their
   * JSON parsed into different case classes, we
   *
   * @param importedFilePath The path to the file that needs to be sent in the HTTP request.
   * @param apiPort          The port of the API server to which the HTTP request will be made.
   * @param processor        An instance of JsonResponseProcessor, which defines how the JSON response is processed.
   *
   *                         This function performs the following steps:
   *                         1. Checks if the API is available by making a call to IsServiceAvailable and waiting for up to 3 seconds.
   *    - Logs an information message before checking the API availability.
   *    - Logs a confirmation message if the API is available, or a warning message with the exception if it is not.
   *      2. If the API is available, it prepares and sends an HTTP POST request with the file.
   *    - The file is read into bytes and included in the request as a form data part.
   *    - A `HttpRequest` object is created with the `POST` method, the API URI, and the form data entity.
   *      3. Calls `httpRequestStrictWithRetry` to send the HTTP request, with a maximum of 3 retries.
   *    - If the request is successful, it writes the response JSON to an output file, logs an information message
   *      stating the success and file path, and sends a `ProcessJson` message to the actor for further processing.
   *    - If the request fails, logs a warning message with the exception.
   *    - Utilizes `httpRequestStrictWithRetry` for retrying the request in case of a `BufferOverflowException`.
   *      4. If the API is not available, logs a warning message and aborts the request.
   *
   *
   * If we are sending the filepath instead of the bytes then we assume that fireVoice API must be on the local server
   */
  def makeRequest(importedFireTextData: FileRetrieved): Unit = {
    warning(s"Initiating makeRequest procedure: $importedFireTextData")

    // Step 1: Checking API availability
    warning("Checking API availability.")
    val serviceFuture = IsServiceAvailable() // Returns a Future

    Await.result(serviceFuture, 3.seconds) // Comment out bc we are using .onComplete (redundant?)
    serviceFuture.onComplete {
      case Success(_) =>
        warning("API is available, proceeding with HTTP request preparation.")
      case Failure(x) =>
        warning(s"FireVoice API HTTP status could not be confirmed: $x")
    }

    // Step 2: Preparing HTTP request
    // Check if the API is available before proceeding.
    if (apiAvailable) {

      // Step 2: Preparing HTTP request
      // IMPORTANT: only valid if FireVoice is running on localhost, else we need to send the file itself (sending filepath only works on localhost)
      // The size of the mp3 is more of a problem on edge server (open-client side so it could overflow)
      // Translate to text so we don't have to send the file itself .mp3:: OR acquisition of the files into the edge server
      // this is string json data (we can send the filepaths of the text file for now)
      val fileRequest: File = new File(importedFireTextData.req.file.getPath.replace("\\", "/"))
      writer.clear()
      writer.writeObject { w =>
        w.writeStringMember("file", fileRequest.getPath.replace("\\", "/"))
      }
      val bodyJson = writer.toJson
      val reqEntity = HttpEntity(`application/json`, bodyJson)
      warning(s"Generated JSON body for HTTP request:: url: ${apiPort} entity:  $bodyJson")

      // Determine the output file path and name.
      // This is where the result of the API call will be stored, in JSON format.
      // read the entire payload immediately in memory
      httpRequestStrict(apiPort, HttpMethods.POST, entity = reqEntity) { // maybe max time out in
        case Success(strictEntity) =>
          val data = strictEntity.getData().utf8String // convention
          warning(s"makeRequest: download http request payload complete: ${data}")

          // The FireVoice API should give enough JSON information to parse into a WildfireDataAvailable Object
          // Return the case class WFA object and then publish it to the message bus
          var wgd = processResponse(data, importedFireTextData) // can be changed
          warning(s"Synchronizing FireVoiceImportActor: ${wgd.getClass.getName} + ${wgd} + a")
          self ! wgd // Effectively a synchronization thread: allows us to not care about the execution of
        case Failure(x) =>
          warning(s"download failed: $x")
      }
    } else {
      warning("FireVoice API is not available. Aborting makeRequest.")
    }
  }

  // TODO: Unit Test this and ask sequoia if this is correct
  def processResponse(response: String, importedFireTextData: FileRetrieved): WildfireGeolocationData = {
    val parser = new WgdParser()
    val parsedDataOption = parser.processResponse(response, importedFireTextData.req.file)

    // Check if parsing was successful (Option is not None)
    parsedDataOption match {
      case Some(parsedData) =>
        // Parsing was successful, return the parsed data
        parsedData
      case None =>
        warning("NO WILDFIRE GEOLOCATION DATA RETURNED: RETURNING NONE")
        // Parsing failed, you can handle this case as needed
        // For now, let's return a default WildfireGeolocationData
        WildfireGeolocationData(
          fireTextFile = Some(importedFireTextData.req.file),
          date = None,
          Incident_ID = None,
          Call_ID = None,
          Coordinates = None,
          Incident_Report = None,
          Severity_Rating = None,
          Coordinate_Type = None
        )
    }
  }

  // Utility function to parse a coordinate entry

  /**
   * Constructs a URL and initiates an HTTP request for the FireVoice Geolocate service.
   * Wrapper in Case additional preprocessing is needed
   * @param filePath The path to the audio file that needs to be sent for geolocation processing.
   *
   * This function performs the following steps:
   * 1. Constructs the URL based on the provided filePath and other criteria (if any).
   * 2. Calls the `makeRequest` function, passing the constructed URL, API port, and a new instance of `ProcessCallJson` as the JSON response processor.
   * 3. The `makeRequest` function then takes care of sending the HTTP request, handling retries, and processing the response.
   *
   * Note: The API port is set beforehand (e.g., as a class or object variable) and is used when calling `makeRequest`.
   */

  def makeFireVoiceGeolocateCallRequest(fileFireRetrieved: FileRetrieved): Unit = {
    // Construct the URL based on filePath or other criteria
    // Call makeRequest with the specific processor

    makeRequest(fileFireRetrieved)
  }


  /**
   * Retrieves the output file based on Incident ID and Call ID.
   *
   * The function generates a File object pointing to the file where the fire records
   * for a specific incident and call should be saved or read from. The file will
   * reside within the directory specified by `dataDirFireRecords`.
   *
   * @param Incident_ID The ID of the incident.
   * @param Call_ID The ID of the call.
   * @return A File object pointing to the designated file.
   */
  def getOutputFile(Incident_ID: String, Call_ID: String): File = {
    warning(s"Generating output file for Incident ID: $Incident_ID and Call ID: $Call_ID")
    val outputFile = new File(dataDir, s"${Incident_ID}_${Call_ID}.json")

    warning(s"Output file generated at path: ${outputFile.getPath}")
    outputFile
  }



  /**
   * Publishes a `WildfireDataAvailable` object to a message bus.
   *
   * This function populates a `WildfireData` instance and publishes it for further processing or analytics.
   *
   * @param wildfireData The data object containing wildfire information.
   */
  def publishWildfireData(wildfireData: WildfireGeolocationData): Unit = {
    warning(s"Populated WildfireData object: $wildfireData")
    publish(wildfireData)
    warning("Finished Publishing WildfireData to message bus.")

  }
}



/**
 * FireVoiceAPIProcess is a utility class that abstracts the launching and interaction with
 * the Python-based API server used in the FireVoiceImportActor.
 *
 * This class is responsible for launching and interacting with an external FireVoice API service.
 * It extends `ExternalProc` which provides a skeleton for running and interacting with external processes.
 * The decision to keep this functionality in a separate class as opposed to integrating it directly
 * within an actor is to adhere to the Single Responsibility Principle. This makes the code more modular,
 * easier to test, and allows reusability. The actor should focus on actor-specific functionalities like
 * message-passing, while this class takes care of interacting with the external FireVoice API process.
 *
 * Inherits from ExternalProc which is a RACE specific class to encapsulate the functionality.
 *
 * @param prog The file object representing the Python executable required to run the API service. If not found, a RuntimeException is thrown.
 * @param cwd The working directory where the external process will be started. It's overridden from the superclass to make it optional.
 * @param apiPath The file object representing the API's run script. If not found, a RuntimeException is thrown.
 *
 * @usage
 *   - Initialize the API process within the FireVoiceImportActor
 *   - Start the API service using startAPI in FireVoiceImportActor
 *   - Optionally stop and monitor the API service
 */
class FireVoiceAPIProcess(val prog: File, override val cwd: Some[File], val apiPath: File) extends ExternalProc[Boolean] {


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

