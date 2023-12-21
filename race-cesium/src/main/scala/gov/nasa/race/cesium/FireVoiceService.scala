



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



/**
 * FireVoiceLayerService.scala
 *
 * Purpose:
 * This service is responsible for managing, serving, and updating fire and audio layer data in a web application. 
 *
 * Input-Output (IO):
 * - Input: Receives messages of type `SmokeAvailable` via an Akka actor system, which encapsulates details about available fire and audio data.
 * - Output: Serves files related to fire and audio layers via HTTP and also pushes real-time updates to connected clients via Websockets.
 *
 * Functionality:
 * 1. Data Ingestion: Upon receiving a `SmokeAvailable` message, the service creates instances of `Layer` and `FirePerimLayer` case classes.
 * 2. Data Storage: These instances are stored in LinkedHashMaps (`layers` and `firePerimlayers`) for quick and easy access. (keyed by the url)
 * 3. HTTP Routing: Defines routes to serve layer files directly to clients, as well as additional client-side assets like JS modules and icons.
 * 4. Real-Time Updates: Utilizes Websockets to push real-time layer updates to connected clients.
 * 5. Client Configuration: Generates and serves configuration for the client-side rendering of fire and audio layers.
 *
 * Integration into the Webpage:
 * - This service works in tandem with a client-side JavaScript module (`ui_cesium_smoke.js`) to visualize the layer data.
 * - It serves the necessary files and configurations for the client-side to render these layers properly.
 * - It may also be part of a larger Akka actor system, receiving data from other services or data pipelines and updating other connected systems or databases.
 *
 * Note: The script is highly configurable but lacks explicit logging, which should be included for production-grade service.
 * wfa in WebSockets: The SmokeAvailable data is also converted to JSON and pushed over WebSockets for real-time updates. This is done by using the
 * push(TextMessage(firePerimSl.json)) function call.
 *
 * Questions:
 *
 * why do we also have a route to request data with get requests if the data is also pushed through the websocket?
 * who calls initialConnection?
 * what if we having missing data like no audio file?
 * how do we know what happens in BusEvent? (if I am using WildFireDataAvailable as my only case class and then adding)
 *  fields to it progressively, will this cause other actors to be able to view it?
 *
 *
 * what is the location of where the data is kept? assuming its in race-data? the wfa file path to audio and geojson files right?
 *  when we make requests to FV-Actor we get the coordinates (do not need to save JSON)
 */

package gov.nasa.race.cesium

import akka.actor.Actor.Receive
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.common.{JsonProducer, JsonWriter}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.NoConfig
import gov.nasa.race.core.{BusEvent, ParentActor, PipedRaceDataClient}
import gov.nasa.race.earth.{WildfireDataAvailable, WildfireGeolocationData, Coordinate}
import gov.nasa.race.http._
import gov.nasa.race.ui._
import gov.nasa.race.util.StringUtils
import scalatags.Text
import gov.nasa.race.uom.DateTime

import java.net.InetSocketAddress
import java.io.File
import scala.collection.mutable

// define default contour rendering settings

class DefaultContourRenderingS (conf: Config) {
  val strokeWidth = conf.getDoubleOrElse("stroke-width", 1.5)
  val strokeColor = conf.getStringOrElse( "stroke-color", "grey")
  val smokeColor = conf.getStringOrElse( "fire-color", "black")
  val cloudColor = conf.getStringOrElse( "audio-color", "white")
  val alpha = conf.getDoubleOrElse("alpha", 0.5)

  def toJsObject = s"""{ strokeWidth: $strokeWidth, strokeColor: Cesium.Color.fromCssColorString('$strokeColor'), smokeColor:Cesium.Color.fromCssColorString('$smokeColor'), cloudColor:Cesium.Color.fromCssColorString('$cloudColor'), alpha:$alpha }"""//s"""{ strokeWidth: $strokeWidth, strokeColor: Cesium.Color.fromCssColorString('$strokeColor'), fillColors:$jsFillColors, alpha:$alpha }"""
}

object FireVoiceLayerService {
  val jsModule = "ui_cesium_fire_voice.js"
  val icon ="firehistory-icon.svg"
}

import FireVoiceLayerService._


trait FireVoiceService extends CesiumService with FileServerRoute with PushWSRaceRoute with CachedFileAssetRoute with PipedRaceDataClient with JsonProducer {
  //////////////////////////////////////////////////////////////////
  // service definition

  // case class that takes in an available object and the type - used to push data through the route server
  // he code looks up the Layer object using this internal URL path and serves the corresponding File
  // rather an internal URL path for routing within the Akka HTTP framework
  // wfa is input, the files are set to fields in layer, these files are then served in the route
  // the later is looked up by tue
  case class Layer(wfa: WildfireDataAvailable, scType: String) {
    val urlName = scType match {
      case "perim" => s"perim-${wfa.WildfireGeolocationData.Call_ID}-${wfa.WildfireGeolocationData.Incident_ID}"
      case "text" => s"text-${wfa.WildfireGeolocationData.Call_ID}-${wfa.WildfireGeolocationData.Incident_ID}"
    }
    val file: Option[File] = scType match {
      case "perim" => wfa.firePerimFile
      case "text" => wfa.WildfireGeolocationData.fireTextFile
    }
  }

  //////////////////////////////////////////////////////////////////
  case class FirePerimLayer(wfa: WildfireDataAvailable) {
    // case class that takes in an available object - used to push data through the websocket
    val fireUrlName = s"perim-${wfa.WildfireGeolocationData.Call_ID}-${wfa.WildfireGeolocationData.Incident_ID}"
    val textUrlName = s"text-${wfa.WildfireGeolocationData.Call_ID}-${wfa.WildfireGeolocationData.Incident_ID}"
    val uniqueId = s"${wfa.WildfireGeolocationData.Call_ID}-${wfa.WildfireGeolocationData.Incident_ID}"

    // (text, perim, id)
    val json = wfa.toJsonWithTwoUrls(s"fire-data/$textUrlName", s"fire-data/$fireUrlName", uniqueId)
  }
  //////////////////////////////////////////////////////////////////

  // Two Different HashMaps for the two different data types
  protected val layers: mutable.LinkedHashMap[String, Layer] = mutable.LinkedHashMap.empty // urlName -> Layer
  protected val firePerimlayers: mutable.LinkedHashMap[String, FirePerimLayer] = mutable.LinkedHashMap.empty // urlName -> FirePerimLayer

  //--- obtaining and updating fire fields
  override def receiveData: Receive = receiveFireData orElse super.receiveData

  // Instant message passing: messages are sent from CloudFire Actor and then parsed into data layers
  def receiveFireData: Receive = { // action for recieving bus message with new data
    // Create 3 new layers
    // All fields are compelete (CloudFireActor creates and published wfa)
    case BusEvent(_, wfa: WildfireDataAvailable, _) =>
      warning(s"Received WildfireDataAvailable: $wfa")
      // create layers
      val textL = Layer(wfa, "text") // create the audio layer
      val perimL = Layer(wfa, "perim") // create the fire layer
      // val simReportL = Layer(wfa, "fire") // create the fire layer
      // ... (any other layers or information that is collected)

      // FirePerimLayer contains the data for all related FireVoice files pushes the filepaths over the websocket with the routes
      // The routes can then be used to access the Layer instances through http
      val firePerimSl = FirePerimLayer(wfa) // create the fire and audio layer

      // add layers to the collected hashmap
      addLayer(textL)
      addLayer(perimL)
      addFirePerimLayer(firePerimSl)
      //the server maintains a list of active WebSocket connections. Whenever the server needs to push data, it iterates over this list and sends data to each connected WebSocket client
      //Here we are pushing the reference class (filepaths only:  no actual GeoJson content) geoJSON will be retreived by the httpRequest later

      warning(s"Pushing combined data over websocket: ${firePerimSl.json}")
      push(TextMessage(firePerimSl.json)) // the server maintains a list of active WebSocket connections. Whenever the server needs to push data, it iterates over this list and sends data to each connected WebSocket client
  }

  // add new layer functions
  // WATCH OUT - these can be used concurrently so we have to sync
  // Layers are based on the urlName
  def addLayer(sl: Layer): Unit = synchronized {
    layers += (preprocess_url(sl.urlName) -> sl)
  } //  Adds an entry to the layers LinkedHashMap, with the key being sl.urlName and the value being sl.

  def currentFireLayerValues: Seq[Layer] = synchronized {
    layers.values.toSeq
  }


  // WATCH OUT - these can be used concurrently so we have to sync
  // firePerimLayer indexed on unique id.
  def addFirePerimLayer(sl: FirePerimLayer): Unit = synchronized {
    firePerimlayers += (sl.uniqueId -> sl)
  }

  def currentFirePerimLayers: Seq[FirePerimLayer] = synchronized {
    firePerimlayers.values.toSeq
  }

  // Function to standardize URLs for consistent comparison
  def preprocess_url(url: String): String = {
    // Example preprocessing steps (can be adjusted as needed)
    url.trim.toLowerCase.replaceAll(" ", "")  // Remove leading/trailing spaces, convert to lower case, and remove spaces
  }

  //--- route

  /**
   * Defines the HTTP route handlers for fire-data related requests.
   * This function has multiple responsibilities:
   *   1. Serves files based on the provided path prefix "fire-data".
   *      2. Handles client requests to "fire-audio" which serves client-side shader code.
   *      3. Serves JavaScript modules and icons to the client.
   *
   * The Scala code here is setting up the server-side logic to handle incoming HTTP requests. (The Browser will know to access these endpoints)
   * * List of Defined Routes and Example Calls:
   *
   * 1. Route: "fire-data/{unmatched_path}"
   *    - Handles any GET request that starts with "fire-data/" and has some unmatched path after it.
   *    - Example Call: GET "http://server_address/fire-data/someFile"
   *
   * 2. Route: "fire-audio/{unmatched_path}"
   *    - Handles any GET request that starts with "fire-audio/" and has some unmatched path after it.
   *    - Example Call: GET "http://server_address/fire-audio/someShader"
   *
   * TODO: There may be more routes for other data types.
   * 3. Route: "{jsModule}"
   *    - Serves a particular JavaScript module, where `jsModule` is a predefined variable likely holding the path or name of the module.
   *    - Example Call: GET "http://server_address/jsModulePath"
   *
   * 4. Route: "{icon}"
   *    - Serves an icon file, where `icon` is a predefined variable likely holding the path or name of the icon.
   *    - Example Call: GET "http://server_address/iconPath"
   *
   * Note: "server_address" is a placeholder for where the service is actually hosted.
   *
   * @return Route The constructed Akka HTTP Route.
   */

  //If a request does not match any of the routes, Akka HTTP will automatically respond with a 404 Not Found status. You can also explicitly define a "catch-all" route to handle unmatched routes with custom logic if desired.
  // QUESTION: How do we ensure that overwrites to other data with the same prefix (fire-data) do not occur
  def fireVoiceRoute: Route = {
    get {
      pathPrefix("fire-data" / Segment) { rawUniqueId =>
        val uniqueId = preprocess_url(rawUniqueId)
        warning(s"Received GET request for fire-data with processed unique ID: $uniqueId")

        layers.get(uniqueId) match {
          case Some(layer) =>
            warning(s"Serving fire data for processed unique ID: $uniqueId")
            completeWithFileContent(layer.file.get)

          case None =>
            // Log the available keys in the hashmap for debugging
            val availableKeys = layers.keys.mkString(", ")
            warning(s"Data not found for processed unique ID: $uniqueId. Available keys in hashmap: $availableKeys")
            complete(StatusCodes.NotFound, uniqueId)
        }
      }
    } ~    // the function fireVoiceRoute is defined as handling only GET requests, as indicated by the get directive.
      get {
        // Handles requests for single files (json, geojson, etc)
        pathPrefix("fire-data-single" ~ Slash) { // This directive captures the starting segment of the URL path. It is used to group multiple routes that share a common path prefix.
          // Extract the remaining part of the URL
          extractUnmatchedPath { p => // This directive is used to capture the rest of the URL path after the prefix. It puts the unmatched portion into a variable (p in this case).
            val pathName = p.toString()
            warning(s"Attempting to access single fire data for path: $pathName")

            // Try to find the Layer object corresponding to this path
            layers.get(pathName) match {
              // If a Layer object is found, complete the request with the file content
              // Here we are delivering the ACTUAL contents the GeoJSON data (not the reference class)
              case Some(sl) =>
                warning(s"Found single fire data layer for path: $pathName")
                completeWithFileContent(sl.file.get) // file information is stored in the layer

              // If not found, return a 404 status
              case None =>
                warning(s"Single fire data layer not found for path: $pathName")
                complete(StatusCodes.NotFound, pathName)

            }
          }
        } ~ // This symbol is used to concatenate multiple routes. When a request comes in, Akka HTTP will try each of these routes in the order they are defined until it finds a match.
          // Accesses the firePerimLayers (with multiple file paths and serves all of them)
          pathPrefix("fire-data-combined" ~ Slash) {
            // Extract the remaining part of the URL
            extractUnmatchedPath { p =>
              val pathName = s"fire-data-combined/$p"
              warning(s"Attempting to access combined fire data for path: $pathName")

              // Complete the request by sending the file content to the client
              complete(ResponseData.forPathName(pathName, getFileAssetContent(pathName)))
            }
          } ~
          // Serves the JavaScript module to the client
          fileAssetPath(jsModule) ~
          // Serves the icon to the client
          fileAssetPath(icon)
      }
  }

  // Include this route in your overall HTTP route setup
  override def route: Route = fireVoiceRoute ~ super.route

  //--- websocket
  // Who calls this? is this just a default lifecycle method?
  protected override def initializeConnection(ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    warning(s"WebSocket connection initializing with remote address: ${ctx.remoteAddress}")
    super.initializeConnection(ctx, queue)
    initializeFireConnection(ctx, queue)
    warning("WebSocket connection initialized.")
  }

  // Define the method that initializes the fire data connection over WebSocket
  private def initializeFireConnection(ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = synchronized {
    val remoteAddr = ctx.remoteAddress
    warning(s"Initializing fire connection for remote address: $remoteAddr")

    currentFirePerimLayers.foreach { sl =>
      warning(s"Sending initial fire perimeter layer data over WebSocket to $remoteAddr: ${sl.json}")
      pushTo(remoteAddr, queue, TextMessage(sl.json))
    }
    // Call this function in the service's constructor or initialization block
    warning("Initializing Default Data Swap")
    initializeDefaultData()

    warning(s"Fire connection initialized for $remoteAddr with ${currentFirePerimLayers.size} fire perimeter layers.")
  }

  //--- document content generated by js module
  override def getHeaderFragments: Seq[Text.TypedTag[String]] = {
    val headerFragments = super.getHeaderFragments :+ addJsModule(jsModule)
    warning(s"Adding JS module to header fragments: $jsModule")
    headerFragments
  }

  //--- client config
  override def getConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val configStr = super.getConfig(requestUri, remoteAddr) + fireLayerConfig(requestUri, remoteAddr)
    warning(s"Generating client config for requestUri $requestUri from remoteAddr $remoteAddr")
    configStr
  }

  def fireLayerConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val cfg = config.getConfig("fireVoiceLayer")
    val defaultContourRenderingS = new DefaultContourRenderingS(cfg.getConfigOrElse("contour.render", NoConfig))
    val configJson =
      s"""
  export const fireVoiceLayer = {
    contourRender: ${defaultContourRenderingS.toJsObject},
    followLatest: ${cfg.getBooleanOrElse("follow-latest", false)}
  };"""
    warning(s"Fire layer client config generated: $configJson")
    configJson
  }
  ////////////////////////////////////////////
  // Initialize default data
  private def createDefaultData(): Seq[WildfireDataAvailable] = {
    val defaultFireTextFile1 = new File("race-earth/src/main/python/fire-voice-mocked/mockedRawFireTextDataJson/mocked_1.json")
    val defaultFireTextFile2 = new File("race-earth/src/main/python/fire-voice-mocked/mockedRawFireTextDataJson/mocked_2.json")
    val defaultFireTextFile3 = new File("race-earth/src/main/python/fire-voice-mocked/mockedRawFireTextDataJson/mocked_3.json")
    // Add more file paths as needed

    val defaultFirePerimFile1 = new File("race-earth/src/main/python/cloud-fire-mocked/mockedCloudFireData/perim_lat=39.027604_lon=-120.881455.json")
    val defaultFirePerimFile2 = new File("race-earth/src/main/python/cloud-fire-mocked/mockedCloudFireData/perim_lat=39.034572_lon=-120.853744.json")
    val defaultFirePerimFile3 = new File("race-earth/src/main/python/cloud-fire-mocked/mockedCloudFireData/perim_lat=39.024991_lon=-120.840288.json")
    // Add more file paths as needed

    val defaultWFADatas = Seq(
      WildfireDataAvailable(
        WildfireGeolocationData(
          Some(defaultFireTextFile1),
          Some(DateTime.now),
          Some("INC123"),
          Some("CALL123"),
          Some(List(Coordinate(34.0522, -118.2437))),
          Some("Report 1"),
          Some("High"),
          Some("GPS")
        ),
        Some("Simulation Report"),
        Some(defaultFirePerimFile1)
      ),
      WildfireDataAvailable(
        WildfireGeolocationData(
          Some(defaultFireTextFile2),
          Some(DateTime.now),
          Some("INC124"),
          Some("CALL124"),
          Some(List(Coordinate(34.0523, -118.2438))),
          Some("Report 2"),
          Some("Medium"),
          Some("GPS")
        ),
        Some("Simulation Report"),
        Some(defaultFirePerimFile2)
      ),
      WildfireDataAvailable(
        WildfireGeolocationData(
          Some(defaultFireTextFile3),
          Some(DateTime.now),
          Some("INC125"),
          Some("CALL125"),
          Some(List(Coordinate(34.0524, -118.2439))),
          Some("Report 3"),
          Some("Low"),
          Some("GPS")
        ),
        Some("Simulation Report"),
        Some(defaultFirePerimFile3)
      )
      // Add more default WFA objects as needed
    )

    defaultWFADatas
  }


  // Add a function to initialize default data into layers and firePerimLayers
  private def initializeDefaultData(): Unit = {
    val defaultWFADatas = createDefaultData()

    // Cannot use Self ! message passing because its not really an actor ya - heard
    defaultWFADatas.foreach { wfa =>
      val textL = Layer(wfa, "text")
      val perimL = Layer(wfa, "perim")
      val firePerimSl = FirePerimLayer(wfa)

      warning("Default data initialized for Wildfire: " + wfa.toString)

      addLayer(textL)
      addLayer(perimL)
      addFirePerimLayer(firePerimSl)

      // Optionally, you can send these data to WebSocket clients
      warning(s"Pushing combined data over websocket: ${firePerimSl.json}")
      push(TextMessage(firePerimSl.json))
    }
    // Log the contents of the layers and firePerimlayers
    // Log the contents of 'layers' each on a new line
    warning("Contents of 'layers':")
    for ((key, layer) <- layers) {
      warning(s"Key: $key, Layer: $layer")
    }

    // Log the contents of 'firePerimlayers' each on a new line
    warning("Contents of 'firePerimlayers':")
    for ((key, firePerimLayer) <- firePerimlayers) {
      warning(s"Key: $key, FirePerimLayer: $firePerimLayer")
    }
  }

}

/**
 * a single page application that processes fire and audio segmentation images
 */
class CesiumFireVoiceApp(val parent: ParentActor, val config: Config) extends DocumentRoute with FireVoiceService with ImageryLayerService