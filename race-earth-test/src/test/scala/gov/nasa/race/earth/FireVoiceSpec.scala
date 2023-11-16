package gov.nasa.race.earth.actor

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import gov.nasa.race.core.{RaceActor, BusEvent}
import gov.nasa.race.http.FileRetrieved
import gov.nasa.race.earth.WildfireDataAvailable
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import java.io.File
import akka.actor.Status.{Success, Failure}


class FireVoiceImportActorSpec extends TestKit(ActorSystem("FireVoiceImportActorSpec"))
  with ImplicitSender
  with AnyWordSpecLike
  with BeforeAndAfterAll
  with BeforeAndAfterEach {

  // Define the configuration settings for the actor
  // Is CWD the actor dir or the API dir, sequoia has it a the python folder.
  val config = ConfigFactory.parseString(
    """
      |fire-voice-import-actor {
      |  data-dir = " ${data.dir}"/fire-voice"
      |  python-exe = ${user.home}"/Anaconda3/envs/odin/python.exe"
      |  api-exe = "race-earth\src\main\python\fire-voice\apiV1.py"
      |  api-cwd = "path/to/api/cwd" 
      |  api-port = "http://localhost:5000/process"
      |}
      |""".stripMargin) 

  // Create the FireVoiceImportActor with the specified configuration
  val fireVoiceImportActor = system.actorOf(Props(new FireVoiceImportActor(config.getConfig("fire-voice-import-actor"))), "fireVoiceImportActor")

  // A probe to intercept messages from the actor
  val probe = TestProbe()

  override def beforeAll(): Unit = {
    // Setup code that should run before all tests go here
    // For example, you could start any required services or initialize shared resources
  }

  override def afterEach(): Unit = {
    fireVoiceImportActor ! PoisonPill
  }


  // Utility methods
  def makeAPIUnavailable(): Unit = {
    // Implement how to make the API unavailable
  }

  def simulateFailedAPIRequest(): Unit = {
    // Implement how to simulate a failed API request
  }

// TODO: 
    // Change the BusEvents to Publish
"A FireVoiceImportActor" should {
  "log a warning when the API is not available" in {
    // Assuming there is a way to make the API unavailable for this test
    makeAPIUnavailable() // This is a placeholder for the actual implementation
    val message = FileRetrieved("/path/to/audio.mp3")
    fireVoiceImportActor ! BusEvent("radioChannel", message, self)
    probe.expectMsg("API is not available. Aborting makeRequest.")
  }

  "log a warning on failed API request" in {
    val message = FileRetrieved("/path/to/audio.mp3")
    fireVoiceImportActor ! BusEvent("radioChannel", message, self)
    // Simulate a failed API request
    simulateFailedAPIRequest()
    probe.expectMsg("Request to FireVoice API failed: <Error Details>")
  }

  "handle FireVoiceImportMessage correctly" in {
    val message = FireVoiceImportMessage("test message")
    fireVoiceImportActor ! message
    // Test the actor's response here
  }

  "handle invalid messages gracefully" in {
    fireVoiceImportActor ! "invalid message"
    probe.expectMsg(Status.Failure(new IllegalArgumentException("Invalid message type")))
  }

  "start and stop the API server correctly" in {
    fireVoiceImportActor ! StartAPI
    probe.expectMsg(SuccessfulAPIStart)

    fireVoiceImportActor ! StopAPI
    probe.expectMsg(SuccessfulAPIStop)
  }

  "log a warning for non-mp3 files" in {
    val nonMp3Message = FileRetrieved("test.txt")
    fireVoiceImportActor ! BusEvent("radioChannel", nonMp3Message, null)
    probe.expectMsg(LogWarning("Received a non-mp3 file: test.txt"))
  }

  "process data and produce WildfireDataAvailable events" in {
    val message = FireVoiceImportMessage("test message")
    fireVoiceImportActor ! message
    probe.expectMsgType[WildfireDataAvailable]
  }

  "handle file retrieval failures gracefully" in {
    val message = FileRetrieved(new File("nonexistent file"))
    fireVoiceImportActor ! message
    probe.expectMsg(Status.Failure(new FileNotFoundException("File not found")))
  }

  "respond with success when processing is successful" in {
    val message = FireVoiceImportMessage("test message")
    fireVoiceImportActor ! message
    probe.expectMsg(Status.Success)
  }

  "respond with failure when processing fails" in {
    val message = FireVoiceImportMessage("invalid data")
    fireVoiceImportActor ! message
    probe.expectMsg(Status.Failure)
  }

  "initiate makeFireVoiceGeolocateCallRequest for mp3 files" in {
    val mp3Message = FileRetrieved("test.mp3")
    fireVoiceImportActor ! BusEvent("channel", mp3Message, null)
    probe.expectMsg(CallRequestInitiated("test.mp3"))
  }

  "log an error for JSON processing failures" in {
    val invalidJsonFile = new File("invalid.json")
    fireVoiceImportActor ! ProcessCallJson(invalidJsonFile)
    probe.expectMsg(LogError("Failed to decode JSON from invalid.json: ..."))
  }

  "handle CustomExec with invalid API path gracefully" in {
    val invalidApiPathConfig = config.withValue("fire-voice-import-actor.api-exe", ConfigValueFactory.fromAnyRef("invalid/path/to/api/script"))
    val actorWithInvalidApiPath = system.actorOf(Props(new FireVoiceImportActor(invalidApiPathConfig.getConfig("fire-voice-import-actor"))), "actorWithInvalidApiPath")
    actorWithInvalidApiPath ! StartAPI
    probe.expectMsg(LogError("Invalid API script path"))
  }

  "handle API query with unavailable service gracefully" in {
    fireVoiceImportActor ! StartAPI
    probe.expectMsg(SuccessfulAPIStart)
    makeAPIServiceUnavailable() // Placeholder for actual implementation
    sendQuery("test query")
    probe.expectMsg(LogError("API service is unavailable"))
  }

  "handle API query with incorrect endpoint gracefully" in {
    fireVoiceImportActor ! StartAPI
    probe.expectMsg(SuccessfulAPIStart)
    sendQueryToIncorrectEndpoint("test query")
    probe.expectMsg(LogError("Queried a nonexistent endpoint"))
  }

  "stop the API service correctly with StopAPI" in {
    fireVoiceImportActor ! StartAPI
    probe.expectMsg(SuccessfulAPIStart)
    fireVoiceImportActor ! StopAPI
    probe.expectMsg(SuccessfulAPIStop)
  }
}

}