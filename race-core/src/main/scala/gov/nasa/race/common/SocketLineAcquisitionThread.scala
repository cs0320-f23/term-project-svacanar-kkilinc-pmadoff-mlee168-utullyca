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
package gov.nasa.race.common

import gov.nasa.race.actor.SocketDataAcquisitionThread

import java.io.IOException
import java.net.{Socket, SocketTimeoutException}

/**
  * data acquisition thread that reads text lines from a socket and calls provided processing functions for each
  * complete line that was received. This also supports optional socket timeout processing
  *
  * note the provided callback return values determine if the data acquisition loop is terminated, which does not
  * close the socket (which is the responsibility of the caller)
  *
  * note also that we don't handle line processing exceptions here
  */
class SocketLineAcquisitionThread (name: String, socket: Socket, initSize: Int, maxSize: Int,
                                   processLine: ByteSlice=>Boolean,
                                   processTimeout: Option[()=>Boolean] = None
                                  ) extends SocketDataAcquisitionThread(name) with LogWriter {

  override def run(): Unit = {
    val is = socket.getInputStream
    val buf = new CanonicalLineBuffer(is, initSize, maxSize)

    while (!isDone.get()) {
      try {
        if (buf.nextLine()) { // this is the blocking point - note LineBuffer fills automatically from its InputStream
          if (!processLine(buf)) isDone.set(true)
        }

      } catch {
        case ix: InterruptedException =>
          // nothing to do - the interrupter has to set isDone if we should stop

        case _: SocketTimeoutException =>
          processTimeout.foreach( f=> if (!f()) isDone.set(true))

        case iox: IOException =>
          if (!socket.isClosed) {
            warning(s"socket stream closed: $iox")
          } else {
            info("socket closed")
            isDone.set(true)
          }
      }
    }
  }
}