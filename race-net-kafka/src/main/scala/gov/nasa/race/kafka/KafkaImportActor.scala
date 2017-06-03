/*
 * Copyright (c) 2016, United States Government, as represented by the
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
package gov.nasa.race.kafka

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.actor.FilteringPublisher
import gov.nasa.race.core.RaceContext
import gov.nasa.race.util.ThreadUtils

/**
  * a RaceActor that publishes messages received from a Kafka broker
  *
  * TODO - shutdown does not work yet if server is terminated (somewhat artificial case that happens in reg tests)
  */
class KafkaImportActor (val config: Config) extends FilteringPublisher {

  var consumer: Option[ConfigurableKafkaConsumer] = None // defer init since Kafka topics might be from remote config
  var terminate = false

  val thread = ThreadUtils.daemon {
    ifSome(consumer) { c =>
      while (!terminate) {
        try {
          if (c.fillValueBuffer > 0) c.valueBuffer.foreach(publishFiltered)
        } catch {
          case x:Throwable if !terminate => error(s"exception during Kafka read: ${x.getMessage}")
        }
      }
      c.close
    }
  }

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    consumer = createConsumer(actorConf.getConfig("consumer"))
    // we won't subscribe before we start
    super.onInitializeRaceActor(rc,actorConf)
  }

  override def onStartRaceActor(originator: ActorRef) = {
    ifSome(consumer) { c =>
      if (c.subscribe) {
        thread.start
      } else {
        warning("Kafka topic subscription failed")
      }
    }
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    terminate = true
    if (thread.isAlive) thread.interrupt()
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage = handleFilteringPublisherMessage

  def createConsumer(conf: Config) = {
    newInstance[ConfigurableKafkaConsumer](conf.getString("class"), Array(classOf[Config]), Array(conf))
  }
}
