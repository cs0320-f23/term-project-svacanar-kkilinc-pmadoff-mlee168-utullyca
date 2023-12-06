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
      }
    }
  }
}
