package gov.nasa.race.earth.actor

import akka.actor.{ ActorSystem, Props }
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.{ ImplicitSender, TestKit, TestProbe }
import com.typesafe.config.ConfigFactory
import gov.nasa.race.core.{ BusEvent, PubSubRaceActor }
import gov.nasa.race.earth.WildfireDataAvailable
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import java.nio.file.{ Files, Path, Paths }
import scala.concurrent.duration._

class CloudFireImportActorSpec extends TestKit(ActorSystem("CloudFireImportActorSpec", ConfigFactory.load("application.conf")))
  with ImplicitSender
  with AnyWordSpecLike
  with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "CloudFireImportActor" should {
    "initialize and terminate correctly" in {
      val actorRef = system.actorOf(Props(new CloudFireImportActor(ConfigFactory.load())))
      watch(actorRef)
      actorRef ! akka.actor.PoisonPill // Don't need
      expectTerminated(actorRef)
    }

    "handle WildfireDataAvailable messages correctly" in {
      val probe = TestProbe()
      val actorRef = system.actorOf(Props(new CloudFireImportActor(ConfigFactory.load())))

      probe.send(actorRef, WildfireDataAvailable(37.7749, -122.4194)) // Example coordinates
      // Here, you need to mock the HTTP response and test the actor's behavior
      // Expect the actor to send a GeoJSONData message to itself
      // Use probe.expectMsg to check the received message
    }

    "handle GeoJSONData messages correctly" in {
      val geoJsonDirPath = Paths.get(ConfigFactory.load().getString("geojson-dir"))
      val actorRef = system.actorOf(Props(new CloudFireImportActor(ConfigFactory.load())))
      val geoJsonData = GeoJSONData(Coordinates(37.7749, -122.4194), "{}") // Example data

      actorRef ! geoJsonData
      // Here you need to check if the file is created and contains the correct GeoJSON data
      // You might also want to check if the WildfirePerim message is published correctly
    }

    "handle errors gracefully" in {
      val actorRef = system.actorOf(Props(new CloudFireImportActor(ConfigFactory.load())))
      val invalidData = "Invalid Data" // Example of invalid data that should cause an error

      actorRef ! invalidData
      // Expect the actor to log an error or handle the error gracefully
      // Use TestProbe or other utilities to check the actor's behavior
    }


    // 1. Test Actor's Response to Valid Wildfire Data
    "process valid WildfireDataAvailable messages correctly" in {
      val actorRef = system.actorOf(Props(new CloudFireImportActor(ConfigFactory.load())))
      actorRef ! wildfireDataAvailable
      // Assertions and expectations here
    }

    // 2. Test Actor's Response to Invalid Wildfire Data
    "handle invalid WildfireDataAvailable messages gracefully" in {
      val actorRef = system.actorOf(Props(new CloudFireImportActor(ConfigFactory.load())))
      actorRef ! WildfireDataAvailable(invalidCoordinates.lat, invalidCoordinates.lon)
      // Assertions and expectations here
    }

    // 3. Test GeoJSON Handling with Valid Data
    "handle valid GeoJSONData messages correctly" in {
      val actorRef = system.actorOf(Props(new CloudFireImportActor(ConfigFactory.load())))
      actorRef ! GeoJSONData(validCoordinates, validGeoJSON)
      // Assertions and expectations here
    }

    // 4. Test GeoJSON Handling with Invalid Data
    "handle invalid GeoJSONData messages gracefully" in {
      val actorRef = system.actorOf(Props(new CloudFireImportActor(ConfigFactory.load())))
      actorRef ! GeoJSONData(validCoordinates, invalidGeoJSON)
      // Assertions and expectations here
    }

    // 5. Test Publishing of WildfirePerim after Handling GeoJSON
    "publish WildfirePerim after handling GeoJSONData" in {
      val probe = TestProbe()
      val actorRef = system.actorOf(Props(new CloudFireImportActor(ConfigFactory.load())))
      system.eventStream.subscribe(probe.ref, classOf[WildfirePerim])

      actorRef ! GeoJSONData(validCoordinates, validGeoJSON)
      probe.expectMsgClass(classOf[WildfirePerim])
      // Additional assertions and expectations here
    }

    // 6. Test File Creation after Handling GeoJSON
    "create a GeoJSON file after handling GeoJSONData" in {
      val actorRef = system.actorOf(Props(new CloudFireImportActor(ConfigFactory.load())))
      val geoJsonDirPath = Paths.get(ConfigFactory.load().getString("geojson-dir"))

      actorRef ! GeoJSONData(validCoordinates, validGeoJSON)
      // Wait for the actor to process the message
      Thread.sleep(1000) // This is just an example, you might want to use a more robust way to wait

      // Check if the file is created
      val files = Files.list(geoJsonDirPath).toArray
      assert(files.nonEmpty)
    }
  }
}