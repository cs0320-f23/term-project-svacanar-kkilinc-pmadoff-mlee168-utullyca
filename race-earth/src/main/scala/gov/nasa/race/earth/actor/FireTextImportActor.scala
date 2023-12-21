package gov.nasa.race.earth.actor

import akka.actor.ActorRef
import gov.nasa.race.common.{ActorDataAcquisitionThread, PollingDataAcquisitionThread}
import gov.nasa.race.uom.{DateTime, Time}
import java.io.File
import com.typesafe.config.Config
import gov.nasa.race.core.{PublishingRaceActor, RaceContext}
import gov.nasa.race.http.{FileRetrieved, RequestFile}
import gov.nasa.race.util.FileUtils
import gov.nasa.race.ifSome
import gov.nasa.race.uom.Time.Milliseconds

case class FireTextData(file: File)

class FireTextDataAcquisitionThread(actorRef: ActorRef, val pollingInterval: Time, dataDir: File)
  extends ActorDataAcquisitionThread(actorRef) with PollingDataAcquisitionThread {

  override protected def poll(): Unit = {
    warning("Polling for JSON files in the data directory.")
    val jsonFiles = dataDir.listFiles(_.getName.endsWith(".json"))

    if (jsonFiles.isEmpty) {
      val allFiles = Option(dataDir.listFiles()).getOrElse(Array()).map(_.getName).mkString(", ")
      warning(s"No JSON files found in the data directory: ${dataDir}. Current files: $allFiles")
    } else {
      jsonFiles.foreach { file =>
        warning(s"Found JSON file: ${file.getName}. Sending to client.")
        sendToClient(FireTextData(file))
        Thread.sleep(10000) // Add a 5-second sleep after sending the file

      }
    }
  }
}


class FireTextImportActor(val config: Config) extends PublishingRaceActor {

  val interval = Milliseconds(config.getDuration("polling-interval").toMillis)
  val dataDir = FileUtils.ensureWritableDir(config.getString("data-dir")).getOrElse {
    throw new RuntimeException("Failed to create or access the data directory.")
  }
  val filesAndFolders = dataDir.listFiles().map(_.getName).mkString(", ")
  warning(s"Data directory set to: ${dataDir.getPath}. Contents: $filesAndFolders")
  var dataAcquisitionThread: Option[FireTextDataAcquisitionThread] = None

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = {
    warning("Initializing FireTextImportActor.")
    val thread = new FireTextDataAcquisitionThread(self, interval, dataDir)
    thread.setLogging(this)
    dataAcquisitionThread = Some(thread)
    super.onInitializeRaceActor(rc, actorConf)
  }

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    warning("Starting FireTextImportActor.")
    Thread.sleep(4000) // TODO: fix this thread sleep issue with FVImportActor, the mailbox mechanism should hadle this

    ifSome(dataAcquisitionThread){ _.start() }
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    warning("Terminating FireTextImportActor.")
    ifSome(dataAcquisitionThread){ _.terminate() }
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage: Receive = {
    case r: FireTextData =>
      warning(s"Processing received FireTextData: ${r.file.getName}")
      val requestFile = RequestFile(url = "local", file = r.file)
      val fileRetrieved = FileRetrieved(req = requestFile, date = DateTime.now)
      publish(fileRetrieved)
  }
}
