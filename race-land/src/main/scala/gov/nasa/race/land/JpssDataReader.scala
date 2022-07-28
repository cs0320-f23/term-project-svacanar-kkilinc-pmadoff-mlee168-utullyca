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
package gov.nasa.race.land

import gov.nasa.race.common.Utf8CsvPullParser
import gov.nasa.race.uom.DateTime

import java.io.File
import scala.collection.mutable.ArrayBuffer

case class JpssProduct (name: String, url: String, satellite: String, dirReader: JpssDirReader, reader: JpssDataReader) {
  var lastPollTime: DateTime = DateTime.UndefinedDateTime
  var lastMd5: String = ""

  def parseDir(data: Array[Byte], pollDate: DateTime, startDate: DateTime): Seq[JpssDirEntry] = {
    val entries = if (lastMd5.isEmpty) dirReader.parseFirst(data, startDate) else dirReader.parse(data, lastMd5)
    if (entries.nonEmpty) lastMd5 = entries.last.md5
    lastPollTime = pollDate

    entries
  }
}

/**
 * downloaded file for JPSS data product
 */
case class JpssData(satellite: String, file: File, product: JpssProduct, date: DateTime)

/**
 * some object that can read files that contain JPSS data products
 */
trait JpssDataReader {
  def read (data: JpssData): Option[Any]
}

/**
 * JpssDataReader for VIIRS Active Fire Product (see https://lpdaac.usgs.gov/documents/427/VNP14_User_Guide_V1.pdf)
 */
class ViirsFireReader extends JpssDataReader {

  override def read(data: JpssData): Option[Any] = {
    None
  }
}


case class JpssDirEntry (date: DateTime, md5: String, url: String)

/**
 * a reader for LANCE NRT directories from https://nrt3.modaps.eosdis.nasa.gov/archive/allData/
 *
 * CSV dir format:  name, last_modified, size, mtime, cksum, md5sum, resourceType, downloadsLink
 */
class JpssDirReader extends Utf8CsvPullParser {

  def parseFirst (data: Array[Byte], startDate: DateTime): Seq[JpssDirEntry] = {
    val newData = ArrayBuffer.empty[JpssDirEntry]

    if (initialize(data)) {
      skipToNextRecord() // skip header line

      while (hasMoreData) {
        skip(3)
        val date = DateTime.ofEpochSeconds( readNextValue().toLong)
        if (date > startDate) {
          skip(1)
          val md5 = readNextValue().toString
          skip(1)
          val url = readNextValue().toString
          newData += JpssDirEntry(date, md5, url)
        }

        skipToNextRecord()
      }
    }

    newData.toSeq
  }

  def parse (data: Array[Byte], lastMd5: String): Seq[JpssDirEntry] = {
    var isNew = false
    val newData = ArrayBuffer.empty[JpssDirEntry]

    if (initialize(data)) {
      skipToNextRecord() // skip header line

      while (hasMoreData) {
        skip(3)
        val date = DateTime.ofEpochSeconds( readNextValue().toLong)
        skip(1)
        val md5 = readNextValue().toString

        if (isNew) {
          skip(1)
          val url = readNextValue().toString
          newData += JpssDirEntry(date, md5, url)
        } else {
          isNew = (md5 == lastMd5)
        }

        skipToNextRecord()
      }
    }

    newData.toSeq
  }
}
