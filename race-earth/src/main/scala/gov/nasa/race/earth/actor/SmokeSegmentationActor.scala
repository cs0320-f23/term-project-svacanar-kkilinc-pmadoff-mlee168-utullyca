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

import akka.actor.ActorRef
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.MediaTypes.`image/tiff`
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, MediaTypes}
import com.typesafe.config.Config
import gov.nasa.race
import gov.nasa.race.ResultValue
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.common.{ExternalProc, JsonWriter, StringJsonPullParser}
import gov.nasa.race.core.{BusEvent, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.util.FileUtils
import gov.nasa.race.earth.{Gdal2Tiles, GdalContour, SmokeAvailable}
import gov.nasa.race.http.{FileRequestFailed, FileRetrieved, HttpActor}
import gov.nasa.race.uom.DateTime

import java.io.File
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.sys.process.Process
import scala.util.{Failure, Success}

trait  SmokeSegmentationActor extends PublishingRaceActor with SubscribingRaceActor{
  // unsure what might go here - may omit
}

class SmokeSegmentationImportActor(val config: Config) extends SmokeSegmentationActor with HttpActor{
  case class ProcessDataSet (date: DateTime, files: Seq[File])

  val writer = new JsonWriter()
  val archive: Boolean = config.getBoolean("archive")

  val apiPort: String = config.getString("api-port")
  val dataFileName: String = "smokeSegmentation" //remove change to datadir

  val dataDir: File = new File(config.getString("data-dir"), "tifs")//.flatMap(FileUtils.ensureDir) // if not set data will not be stored in files
  val dataDirContours: File = new File(config.getString("data-dir"), "contours")
  val gdal2tilesPath: File = new File (config.getString("gdal2tiles-driver"))
  val pythonPath: File = new File(config.getString("python-exe"))
  val apiPath: File = new File(System.getProperty("user.dir"), config.getString("api-exe"))
  val apiCwd: File = new File(System.getProperty("user.dir"), config.getString("api-cwd"))
  val apiProcess = new SmokeSegmentationAPIProcess(pythonPath, Some(apiCwd), apiPath)
  //apiProcess.ignoreOutput

  var runningProc:Process = startAPI // starts the Python server hosting model API and checks if it is available
  var requestDate: DateTime = DateTime.UndefinedDateTime
  var apiAvailable: Boolean = true //false
  val gdalContour:File = new File(config.getString("gdal-contour"))
  //val gdalContour:File = new File(config.getStringOrElse("gdal-contour", getExecutableFile("gdal-contour-prog", "gdal_contour").getPath))
  val contourCmd = new GdalContour(gdalContour)

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    runningProc.destroy()
    super.onTerminateRaceActor(originator)
  }

  def startAPI: Process = {
    val runningProc = apiProcess.customExec()
    Thread.sleep(6000) // bad practice - need to remove?
    val serviceFuture = IsServiceAvailable()
    Await.result(serviceFuture, 3.seconds)
    serviceFuture.onComplete {
      case Success(v) => info ("smoke api status confirmed")
      case Failure(x) => warning(s"smoke api status could not be confirmed $x")
    }
    runningProc
  }

  def IsServiceAvailable(): Future[Unit]  = { // need future on this - need to wait until it executes to continue in thread
    Future {
      httpRequestStrict(apiPort, HttpMethods.GET) {
        case Success(strictEntity) =>
          info("finished initiating smoke api")
          apiAvailable = true
        case Failure(x) =>
          warning(s"smoke api is not initiated $x")
          apiAvailable = false
      }
    }
  }

  def handleSmokeMessage: Receive = {
    case BusEvent(imageChannel, msg:FileRetrieved, _) => // need to account for replay
      makeRequest(msg) // Bus event should trigger request by message from goes true color import actor
    case pds: ProcessDataSet => handleProcessDataSet(pds) // completed segmentation event should trigger processing the data for visualization - currently undefined
  }
  override def handleMessage = handleSmokeMessage orElse super.handleMessage

  def makeRequest(importedImage: FileRetrieved): Unit = {
    val serviceFuture = IsServiceAvailable()
    Await.result(serviceFuture, 3.seconds)
    serviceFuture.onComplete {
      case Success(v) => info ("smoke api status confirmed")
      case Failure(x) => warning(s"smoke api status could not be confirmed $x")
    }
    if (apiAvailable) {
      val tiffBytes: Array[Byte] = FileUtils.fileContentsAsBytes(importedImage.req.file).get
      val reqEntity = HttpEntity(MediaTypes.`image/tiff`, tiffBytes) // image/tiff
      val outputFile: File = new File(dataDir.getPath, importedImage.req.file.getName.replace(".tif", "_segmented.tif"))
      httpRequestFile(apiPort, outputFile, HttpMethods.POST, entity = reqEntity).onComplete {
        case Success(f) =>
          info(s"download complete: $f")
          // send message with file retrieval to process data set
          val contourFuture: Future[Any] = createSmokeCloudContours(f, importedImage.date)
          contourFuture.onComplete{
            case Success(c) =>
              val contourFiles: Seq[File] = Seq(getOutputFile(importedImage.date, "smoke", parseSatelliteName(f).get), getOutputFile(importedImage.date, "cloud", parseSatelliteName(f).get))
              info(s"created contour files: $contourFiles")
              self ! ProcessDataSet(importedImage.date, contourFiles)// process data set by sending two smoke available messages - one with cloud one with smoke data
            case Failure(x) =>
              warning(s"failed to create smoke and cloud contour files: $x")
              println(s"failed to create smoke and cloud contour files: $x")
          }
        case Failure(x) =>
          warning(s"download failed: $x")
      }
    }
  }

  def createSmokeCloudContours(segmentedFile:File, date:DateTime): Future[Any] = {
    val result = for {
      r1 <- createContourFile(segmentedFile, getOutputFile(date, "smoke", parseSatelliteName(segmentedFile).get), 2, 0.25)
      r2 <- createContourFile(segmentedFile, getOutputFile(date, "cloud", parseSatelliteName(segmentedFile).get), 3, 0.25)
    } yield (r2)
    result
  }

  def getOutputFile(date: DateTime, scType: String, satellite: String): File = {
    new File(dataDirContours, s"${satellite}_${scType}_${date.format_yMd_Hms_z(('-'))}.geojson")
  }

  def createContourFile(segmentedFile: File, outFile:File, band: Int, interval: Double): Future[ResultValue[File]] = {
    val res = contourCmd.reset() // try to remove islands, start with binaries
      .setInFile( segmentedFile)
      .setOutFile( outFile)
      .setBand(band)
      .setInterval(interval)
      .setPolygon()
      .setAttrMinName("prob") // in case
      //.ignoreConsoleOutput()
      .exec()//.execSync().asOption
    res
  }

  // new process dataset - convert layers to contours, send to UI?

  def makeRequestImagery(importedImage: FileRetrieved): Unit = {
    if (apiAvailable) {
      writer.clear()
      writer.writeObject { w =>
        w.writeStringMember("file", importedImage.req.file.getPath.replace("\\", "/"))
      }
      val bodyJson = writer.toJson
      val reqEntity = HttpEntity(`application/json`, bodyJson) // image/tiff
      httpRequestStrict(apiPort, HttpMethods.POST, entity = reqEntity) { // maybe max time out in // httpRequestFile
              case Success(strictEntity) =>
                val data = strictEntity.getData().utf8String
                val outputFiles: ArrayBuffer[File] = getOutputFileLocations(data)
                val f: Future[(Any, Any)] = storeData(importedImage.date, outputFiles)
                Await.result(f, Duration.Inf) // not in actor thread, add comment to enter and exit thread, could use messages
                f.onComplete {
                  case Success(v) =>
                    info("finished storing smoke and cloud tiles")
                  case Failure(e) =>
                    e.printStackTrace
                }
                //self ! ProcessDataSet(importedImage.date, data) // move back into actor thread to avoid sync, ! sends the message
              case Failure(x) =>
                warning(s"retrieving data set failed with $x")
            }
    }
  }

  def getOutputFileLocations(data: String): ArrayBuffer[File] = {
    val p = new StringJsonPullParser
    p.initialize(data)
    val _out_ = asc("output")
    val stringSeq = p.readNextObject {
      val stringSeq = p.readNextStringArrayMemberInto(_out_, ArrayBuffer.empty[String])
      assert( stringSeq.size == 2)
      stringSeq
    }
    val fileSeq = stringSeq.map { s =>
      new File(s"$s")
    }
    fileSeq
  }


  def createTiles(outputPath: File, inFile: File): Future[race.ResultValue[File]] = {
    info(s"saving smoke/cloud wms tiles to $outputPath")
    val tiles = new Gdal2Tiles(prog = pythonPath, inFile = inFile, outputPath = outputPath, driverPath = gdal2tilesPath,
      verbose = false, webviewer = "all", tileSize=256, zoom=Some(7))
    //tiles.ignoreOutput
    val tilesFuture = tiles.exec()
    tilesFuture
  }

  def getTileDir(archive: Boolean, date: DateTime): String = {
    var tileDir = ""
    if (archive) {
      tileDir = s"${dataFileName}_${date.format_yMd_Hms_z('-')}" // identify file names where data will be stored
    }
    tileDir
  }

  def getTileOutputFile(tiff:File, tileDir:String, dir:File): File ={
    val tiffType: String = tiff.getPath.split("_").last.replace(".tif", "")
    val tiffDir = tileDir + tiffType
    val outputFile = new File(dir, tiffDir) // create the file
    outputFile
  }

  def storeData (date: DateTime, data: ArrayBuffer[File]): Future[(Any, Any)] = { // store tiles
    info(s"tilling smoke and cloud tifs from $date")
    val tileDir = getTileDir(archive, date)
    val tiff1: File = data(0)
    val outputFile1 = getTileOutputFile(tiff1, tileDir, dataDir)
    val tiles1Future = createTiles(outputFile1, tiff1)
    val tiff2: File = data(1)
    val outputFile2 = getTileOutputFile(tiff2, tileDir, dataDir)
    val tiles2Future = createTiles(outputFile2, tiff2)
    val result = for {
      r1 <- tiles1Future
      r2 <- tiles2Future
    } yield (r1, r2)
    result
  }

  def handleProcessDataSet(pds: ProcessDataSet): Unit = {
    info("in handle process data set") // Undefined now - will need to process for visualization needs
    //smoke - parsing output file which does not contain the satellite name - add to name
    publish(SmokeAvailable(parseSatelliteName(pds.files(0)).get, pds.files(0), "smoke", "epsg:4326", pds.date))
    //add satellite parser from file download
    publish(SmokeAvailable(parseSatelliteName(pds.files(1)).get, pds.files(1), "cloud", "epsg:4326", pds.date))
  }

  def parseSatelliteName(file: File): Option[String] = {
    val satellitePattern1 = raw"GOES(\d{2})".r // raw".*_c(\d{4})(\d{3})(\d{2})(\d{2})(\d{2})(\d)\.tif".r
    val p1 = satellitePattern1.unanchored
    val satellitePattern2 = raw"G(\d{2})".r
    val p2 = satellitePattern2.unanchored
    file.getName match {
      case p1(sat) =>
        val satelliteName: String = "G" + sat
        Some(satelliteName)
      case p2(sat) =>
        val satelliteName: String = "G" + sat
        Some(satelliteName)
      case _ =>
        None
    }
  }

}

class SmokeSegmentationAPIProcess(val prog: File, override val cwd: Some[File], val apiPath: File) extends ExternalProc[Boolean] {
  if (!prog.isFile) throw new RuntimeException(s"Python executable not found: $prog")
  if (!apiPath.isFile) throw new RuntimeException(s"Smoke Segmentation API run script not found: $apiPath")

  protected override def buildCommand: StringBuilder = {
    args = List(
      s"$apiPath"
    )
    super.buildCommand
  }

  override def getSuccessValue: Boolean = {
    true
  }

  def customExec(): Process = {
    val proc = log match {
      case Some(logger) => Process(buildCommand.toString(), cwd, env: _*).run(logger)
      case None => Process(buildCommand.toString(), cwd, env: _*).run()
    }
    proc
  }
}



