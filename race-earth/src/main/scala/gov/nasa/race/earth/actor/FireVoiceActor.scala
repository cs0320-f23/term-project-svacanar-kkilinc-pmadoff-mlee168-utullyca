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
import akka.http.scaladsl.model.{HttpEntity, ContentTypes, Multipart}
import akka.http.scaladsl.model.Multipart.FormData
import akka.util.ByteString
import scala.io.Source

/** 
 * What Default Parsers do we have, I dont want to mess up the default parsers
 * libraryDependencies ++= Seq(
  "io.circe" %% "circe-core" % "0.14.1",
  "io.circe" %% "circe-generic" % "0.14.1",
  "io.circe" %% "circe-parser" % "0.14.1"
)
*/


// import io.circe._
// import io.circe.parser._
// import io.circe.generic.semiauto._


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

class FireVoiceImportActor(val config: Config) extends FireVoiceActor with HttpActor{

  //Creation of case classes that are only used internally within the actor
  //Basically a substitution of fields but are immutable
  case class JsonWildfireData(
    Audio_file: String,
    date: Long,
    Incident_ID: String,
    Call_ID: String,
    Coordinates: List[String],
    Incident_Report: String,
    Severity_Rating: String,
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
  var runningProc:Process = startAPI // starts the Python server hosting model API and checks if it is available
  var apiAvailable: Boolean = true //false

  // Intialize the Akka Logging capabilities
  // val log = Logging(context.system, this)

  /**
   * Start the Race Actor
   *
   * @param originator the ActorRef of the originator
   * @return Boolean indicates success or failure
   */
  override def onStartRaceActor(originator: ActorRef): Boolean = {
    debug("Entered onStartRaceActor function")
    try {
      val result = super.onStartRaceActor(originator)
      info("Successfully started Race Actor")
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
          info("FireVoice API status confirmed")
        case Failure(x) =>
          warning(s"FireVoice API status could not be confirmed: $x")
      }
      //runningProc.destroy()
      val result = super.onTerminateRaceActor(originator)
      info("Successfully terminated Race Actor")
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
          info("Finished stopping FireVoice API server")
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

    // Invoke our API wrapper 
    val runningProc = apiProcess.customExec()
    Thread.sleep(6000) // bad practice - need to remove? Also why not make a promise here?
    val serviceFuture = IsServiceAvailable()
    Await.result(serviceFuture, Duration.Inf)
    serviceFuture.onComplete {
      case Success(v) =>
        info("FireVoice status confirmed")
      case Failure(x) =>
        warning(s"FireVoice status could not be confirmed: $x")
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
      httpRequestStrict(apiPort, HttpMethods.GET) { // the /wprocess endpoint should be able to accept GET + POST requests (GET for availability)
        case Success(strictEntity) =>
          info("Finished initiating FireVoice Flask API")
          apiAvailable = true
        case Failure(x) =>
          warning(s"FireVoice Flask API is not initiated: $x")
          apiAvailable = false
      }
    }
  }


  /**
  * Handles fire-related messages received through the message bus.
  * 
  * The channel is a higher-level construct in Akka, channels define the ActorRefs. BusEvent adds the sender + channel 
  * 
  * The function matches against the message type and content, performing different actions based on the match:
  * - If the message is of type `BusEvent` and contains a `FileRetrieved` message with a file path ending in ".mp3", 
  *   it initiates a FireVoice geolocation call request.
  * - If the message is of type `BusEvent` but the file is not an mp3, it logs a warning.
  * - If the message is of type `WildfireData`, it publishes the wildfire data.
  * 
  * This function is intended to be used as a message handler in an actor system, specifically for handling messages related to fire incidents.
  * 
  * @return A `Receive` partial function that handles specific message types and performs actions accordingly.
  */

def handleFireMessage: Receive = {
  
  // Assuming `File.getName` is a method that retrieves the file name with the extension
  case BusEvent(radioChannel, msg: FileRetrieved, _) if msg.req.file.getName.endsWith(".mp3") =>
      makeFireVoiceGeolocateCallRequest(msg.req.file.getName)
      
  case BusEvent(radioChannel, msg: FileRetrieved, _) =>
      warning(s"Received a non-mp3 file: ${msg.req.file.getName}")
  
  // Assuming WildfireDataAvailable is also wrapped in a BusEvent just like FileRetrieved
  case BusEvent(radioChannel, wildfireData: WildfireDataAvailable, _) =>
      publishWildfireData(wildfireData) // will be processed by the fire simulator

  // Handling other messages
  case _ =>
      warning("Unhandled message type received")
}



  /**
  * Overrides the handleMessage function to include handling of fire-related messages.
  * 
  * This function is a combination of `handleFireMessage` and the super class's `handleMessage` method, ensuring that
  * fire-related messages are handled appropriately while other messages are handled by the default mechanism.
  * 
  * The `handleFireMessage` function takes precedence, and if it doesn't match the message, the control is passed to the `super.handleMessage`.
  * This allows for a modular and organized way to handle different types of messages within an actor system.
  * 
  * a PartialFunction is a function that does not provide an output for every possible input value it can be given. It only offers outputs for a subset of input values.
  * 
  * @return A `PartialFunction` that takes a message of any type and returns `Unit`, encapsulating the handling logic for fire-related messages as well as other messages.
  */

  override def handleMessage: PartialFunction[Any, Unit] = handleFireMessage orElse super.handleMessage

  
  trait JsonResponseProcessor {
    def processResponse(response: File): WildfireDataAvailable
  }
    
  /**
  * Processes a JSON response from a file, converting it into a `WildfireDataAvailable` object.
  * 
  * This function reads a JSON file, decodes its content into an intermediate case class `JsonWildfireData`, and then
  * converts it into a `WildfireDataAvailable` object. If the JSON file cannot be decoded properly, it logs an error and throws a runtime exception.
  * 
  * This function is an override from the `JsonResponseProcessor` class, providing a specific implementation for handling
  * JSON responses related to wildfire data.
  * 
  * @param response A `File` object representing the JSON file to be processed.
  * @return A `WildfireDataAvailable` object containing the information extracted and converted from the JSON file.
  * @throws RuntimeException If the JSON file cannot be decoded properly.
  */

  class ProcessCallJson extends JsonResponseProcessor {
    // use JSON-Pull-Parse 

    // IMPORTANT: Here I defaulted to Circe (Decoder object) but Sequioa mentioned there is a RACE specific deserializer?
    // License constraints?
    // RunTime of deserialization? Low amount of messages, consider with low-latency messages
    // Deserialization: structure based (we own the payload and lossless aka translation for every field) vs pull parsing (token stream for external format and then we pull constructs out of it into Scala)
    // Use pull-parser: do not break tokens in strings (tokens as slices + quicker)  
    implicit val wildfireDataDecoder: Decoder[JsonWildfireData]= deriveDecoder 

    override def processResponse(response: File): WildfireDataAvailable = {
      
      // Not actually json file:  (response is wrapper File)
      val source = Source.fromFile(response) // Allows us to read the contents from the source file
      val jsonString = try source.mkString finally source.close() // Convert source (file) intle a single string

      decode[JsonWildfireData](jsonString) match {
        // Scala Either type to represent a value of two possible tpes
        case Right(jsonWildfireData) =>
          info(s"Processing call JSON from: ${response.getPath}")
          WildfireDataAvailable(
            audio_file = jsonWildfireData.Audio_file,
            date = DateTime.ofEpochMillis(jsonWildfireData.date),
            Incident_ID = jsonWildfireData.Incident_ID,
            Call_ID = jsonWildfireData.Call_ID,
            Coordinates = jsonWildfireData.Coordinates,
            Incident_Report = jsonWildfireData.Incident_Report,
            Severity_Rating = jsonWildfireData.Severity_Rating,
            Coordinate_Type = jsonWildfireData.Coordinate_Type
          )
        case Left(error) =>
          throw new RuntimeException(s"Failed to decode JSON: $error")
      }
    }
  }

  class DefaultJsonProcessor extends JsonResponseProcessor {
    override def processResponse(response: File): Unit = {
      // Default logic to process JSON
      info(s"Processing default JSON from: ${response.getPath}")
    }
  }


    /**
    * Initiates an HTTP request to a specified API endpoint with a file and processes the JSON response.
    * This is broad method that can handle changing endpoints if needed, different endpoints will need their 
    * JSON parsed into different case classes, we 
    *
    * @param importedFilePath The path to the file that needs to be sent in the HTTP request.
    * @param apiPort The port of the API server to which the HTTP request will be made. 
    * @param processor An instance of JsonResponseProcessor, which defines how the JSON response is processed.
    *
    * This function performs the following steps:
    * 1. Checks if the API is available by making a call to IsServiceAvailable and waiting for up to 3 seconds.
    *    - Logs an information message before checking the API availability.
    *    - Logs a confirmation message if the API is available, or a warning message with the exception if it is not.
    * 2. If the API is available, it prepares and sends an HTTP POST request with the file.
    *    - The file is read into bytes and included in the request as a form data part.
    *    - A `HttpRequest` object is created with the `POST` method, the API URI, and the form data entity.
    * 3. Calls `httpRequestStrictWithRetry` to send the HTTP request, with a maximum of 3 retries.
    *    - If the request is successful, it writes the response JSON to an output file, logs an information message
    *      stating the success and file path, and sends a `ProcessJson` message to the actor for further processing.
    *    - If the request fails, logs a warning message with the exception.
    *    - Utilizes `httpRequestStrictWithRetry` for retrying the request in case of a `BufferOverflowException`.
    * 4. If the API is not available, logs a warning message and aborts the request.
    * 
    * 
    * If we are sending the filepath instead of the bytes then we assume that fireVoice API must be on the local server
    */
    def makeRequest(importedFilePath: String, apiPort: String, processor: JsonResponseProcessor): Unit = {
      info(s"Initiating makeRequest procedure: $importedFilePath")

      // Step 1: Checking API availability
      info("Checking API availability.")
      val serviceFuture = IsServiceAvailable()  // Returns a Future 

      Await.result(serviceFuture, 3.seconds) // Comment out bc we are using .onComplete (redundant?)
      serviceFuture.onComplete {
        case Success(_) =>
          info("FireVoice API status confirmed")
        case Failure(x) =>
          warning(s"FireVoice API status could not be confirmed: $x")
      }

      // Step 2: Preparing HTTP request
      // Check if the API is available before proceeding.
      if (apiAvailable) {
        
        // Step 2: Preparing HTTP request
        // IMPORTANT: only valid if FireVoice is running on localhost, else we need to send the file itself
        // The size of the mp3 is more of a problem on edge server (open-client side so it could overflow)
        // Translate to text so we don't have to send the file itself .mp3:: OR acquisition of the files into the edge server
        val filePathJson = importedFilePath.toString()

        // default akka http request classes 
        val entity = HttpEntity(ContentTypes.`application/json`, filePathJson) // we can also deliver the file as multi-part but I think this is too much
        // val request = HttpRequest(
        //   method = HttpMethods.POST,
        //   uri = apiPort,
        //   entity = entity
        // )

        // Optionally add headers if required
        // val requestWithHeaders = request.withHeaders(RawHeader("name", "value"))

        // Determine the output file path and name.
        // This is where the result of the API call will be stored, in JSON format.

        // change to HttpRequestFileWithTimeout
        val outputFile: File = new File(dataDir.getPath, s"${importedFilePath.split("/").last}_result.json")
        httpRequestFileWithTimeout(apiPort, outputFile, 240.seconds, HttpMethods.POST, entity = entity).onComplete {
          case Success(f) =>
            info(s"download complete: $f")

            // The FireVoice API should give enough JSON information to parse into a WildfireDataAvailable Object
            // Return the case class WFA object and then publish it to the message bus
            self ! processor.processResponse(outputFile) // Effectively a synchronization thread: allows us to not care about the execution of 
          case Failure(x) =>
            warning(s"download failed: $x")
        }

        
        // Send the HTTP request and handle the response.
        // Be aware of limitations: to violate the actor model (we assume sequential code): this is executed outside of actor thread
        // So we can encounter RACE conditions (if there is something that mutates actor state) 
        // httpRequestStrictWithRetry(request, maxRetry = 3) {
        //   case Success(entity) =>
        //       // If the request succeeds, the response entity is a stream of bytes. 
        //       // Here, we aggregate the bytes to construct the full response.
        //       entity.dataBytes.runFold(ByteString(""))(_ ++ _).map { bytes =>
                
        //       // Convert the byte response to a string, assuming it's in UTF-8 format.
        //       // This string is the JSON payload returned from the API.
        //       val jsonString = bytes.utf8String
              
        //       // Write the JSON string to the output file.
        //       // This serves as both a record of the API response and a way to asynchronously process the data.
        //       FileUtils.writeToFile(outputFile, jsonString) // RACE conditions are only for state of actor (not these external files)
              
        //       // This may be too much logic to put in the request but we pass in the deserialization logic 
        //       // for the JSON and return a case class, in our main instance this will be a WildFireDataAvailable instance
        //       self ! processor(outputFile) // Effectively a synchronization thread: allows us to not care about the execution of 
              
        //     }
        //   case Failure(x) =>
        //     warning(s"Request to FireVoice API failed: $x")
        // }
      } else {
        warning("API is not available. Aborting makeRequest.")
      }
    }


    /**
    * Constructs a URL and initiates an HTTP request for the FireVoice Geolocate service.
    *
    * @param filePath The path to the audio file that needs to be sent for geolocation processing.
    *
    * This function performs the following steps:
    * 1. Constructs the URL based on the provided filePath and other criteria (if any).
    * 2. Calls the `makeRequest` function, passing the constructed URL, API port, and a new instance of `ProcessCallJson` as the JSON response processor.
    * 3. The `makeRequest` function then takes care of sending the HTTP request, handling retries, and processing the response.
    *
    * Note: The API port is set beforehand (e.g., as a class or object variable) and is used when calling `makeRequest`.
    */

    def makeFireVoiceGeolocateCallRequest(filePath: String): Unit = {
      // Construct the URL based on filePath or other criteria
      //curPort = apiPort // use .replace if we need to access another endoint
      // Call makeRequest with the specific processor
      makeRequest(filePath, apiPort, new ProcessCallJson)
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
    info(s"Generating output file for Incident ID: $Incident_ID and Call ID: $Call_ID")
    val outputFile = new File(dataDir, s"${Incident_ID}_${Call_ID}.json")
    
    info(s"Output file generated at path: ${outputFile.getPath}")
    outputFile
  }


  /**
   * Serializes a `WildfireDataAvailable` object to a JSON file.
   *
   * The function serializes the object into JSON format and writes it to the output file.
   * Serialization converts the object into a string format that can be easily stored or transferred.
   *
   * @param wildfireData The data object containing wildfire information.
   * @param outputFile The file where the JSON will be written.
   * @param ec Execution context for the Future.
   */
  // def createWildFireDataFile(wildfireData: WildfireDataAvailable, outputFile: File)(implicit ec: ExecutionContext): Future[Unit] = Future {
  //    info("Starting serialization of WildfireData object.")
  //   val json = Json.toJson(wildfireData).toString()
  //    info(s"Serialized data: $json")
  //   val bw = new BufferedWriter(new FileWriter(outputFile))
  //   try {
  //      info(s"Writing serialized data to file: ${outputFile.getPath}")
  //     bw.write(json)
  //   } finally {
  //      info("Closing BufferedWriter.")
  //     bw.close()
  //   }
  // }

  /**
   * Publishes a `WildfireDataAvailable` object to a message bus.
   *
   * This function populates a `WildfireData` instance and publishes it for further processing or analytics.
   *
   * @param wildfireData The data object containing wildfire information.
   */
  def publishWildfireData(wildfireData: WildfireDataAvailable): Unit = {
    info(s"Populated WildfireData object: $wildfireData")
    publish(wildfireData)
    info("Finished Publishing WildfireData to message bus.")

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
    //info("Building command to start the FireVoice API process.")
    args = List(
      s"$apiPath"
    )
    val builtCommand = super.buildCommand
    //info(s"Command built: $builtCommand")
    builtCommand
  }

  /**
   * Provides the value to indicate a successful process run.
   * 
   * This method is intended to be overridden for different types of external processes.
   */
  override def getSuccessValue: Boolean = {
   // info("Returning the success value for FireVoice API process.")
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



