/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.http.tabdata

import java.nio.file.Path

import gov.nasa.race.common.JsonWriter
import gov.nasa.race.common.UnixPath.PathHelper
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.immutable.ListMap

/**
  * reg test for ProviderData
  */
class ColumnDataSpec extends AnyFlatSpec with RaceSpec {

  val resourcePath = "race-net-http-test/src/resources/sites/tabdata/data"

  val rl = "/data"
  val rows = Seq(
    LongRow(p"$rl/cat_A", "this is the cat_A header"),
    LongRow(p"$rl/cat_A/field_1", "this is the cat_A field 1"),
    LongRow(p"$rl/cat_A/field_2", "this is the cat_A field 2"),
    LongRow(p"$rl/cat_A/field_3", "this is the cat_A field 3"),
    LongListRow(p"$rl/cat_A/field_4", "this is the cat_A field 4"),

    DoubleRow(p"$rl/cat_B", "this is the cat_B header"),
    DoubleRow(p"$rl/cat_B/field_1", "this is the cat_B field 1"),
    DoubleRow(p"$rl/cat_B/field_2", "this is the cat_B field 2"),

    LongRow(p"$rl/cat_C", "this is the cat_C header"),
    LongRow(p"$rl/cat_C/field_1", "this is the cat_C field 1"),
    LongRow(p"$rl/cat_C/field_2", "this is the cat_C field 2"),
    LongRow(p"$rl/cat_C/field_3", "this is the cat_C field 3"),
    LongRow(p"$rl/cat_C/field_4", "this is the cat_C field 4")
  )

  val rowList = RowList (
    p"$rl",
    "Sample Data Set",
    DateTime.parseYMDT("2020-06-28T12:00:00.000"),
    rows.foldLeft(ListMap.empty[Path,AnyRow])( (acc,r) => acc + (r.id -> r))
  )

  "a ProviderDataParser" should "read ProviderData from JSON source" in {
    val input = FileUtils.fileContentsAsString(s"$resourcePath/provider_2.json").get

    val parser = new ColumnDataParser(rowList)
    println(s"#-- parsing: $input")

    parser.parse(input.getBytes) match {
      case Some(d:ColumnData) =>
        println("\n  -> result:")

        val w = new JsonWriter
        w.format(true)
        w.readableDateTime(true)
        d.serializeOrderedTo(w, rowList)
        println("\n  -> client JSON:")
        println(w.toJson)

      case _ => fail("failed to parse provider data")
    }
  }
}
