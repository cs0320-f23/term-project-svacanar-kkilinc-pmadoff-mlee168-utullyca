/*
 * Copyright (c) 2019, United States Government, as represented by the
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
package gov.nasa.race.air.translator

import gov.nasa.race.config.ConfigurableTranslator
import gov.nasa.race.IdentifiableObject
import gov.nasa.race.air.{SfdpsTrack, SfdpsTracks}
import gov.nasa.race.common.{ByteSlice, _}
import gov.nasa.race.config._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.{MutSrcTracks, MutSrcTracksHolder, TrackedObject}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom._
import gov.nasa.race.uom.DateTime
import com.typesafe.config.Config

import scala.collection.Seq

class SfdpsTracksImpl(initSize: Int) extends MutSrcTracks[SfdpsTrack](initSize) with SfdpsTracks

/**
  * optimized translator for SFDPS MessageCollection (and legacy NasFlight) messages
  *
  * matcher parts are automatically generated by .tool.StringSetMatcher with "script/smg gen <tag> ..."
  */
class MessageCollectionParser(val config: Config=NoConfig) extends UTF8XmlPullParser2
              with ConfigurableTranslator with Parser with MutSrcTracksHolder[SfdpsTrack,SfdpsTracksImpl]{

  var artccId: String = null
  override def createElements = new SfdpsTracksImpl(100)

  //--- attributes and ancestor element constants
  val _messageCollection = ConstAsciiSlice("ns5:MessageCollection")
  val _nasFlight = ConstAsciiSlice("ns5:NasFlight")
  val _aircraftIdentification = ConstAsciiSlice("aircraftIdentification")
  val _enRoute = ConstAsciiSlice("enRoute")
  val _location = ConstAsciiSlice("location")
  val _positionTime = ConstAsciiSlice("positionTime")
  val _flight = ConstAsciiSlice("flight")
  val _actualSpeed = ConstAsciiSlice("actualSpeed")
  val _uom = ConstAsciiSlice("uom")
  val _knots = ConstAsciiSlice("KNOTS")
  val _mph = ConstAsciiSlice("MPH")
  val _kmh = ConstAsciiSlice("KMH")
  val _feet = ConstAsciiSlice("FEET")
  val _meters = ConstAsciiSlice("METERS")
  val _arrival = ConstAsciiSlice("arrival")
  val _arrivalPoint = ConstAsciiSlice("arrivalPoint")
  val _departure = ConstAsciiSlice("departure")
  val _departurePoint = ConstAsciiSlice("departurePoint")
  val _runwayTime = ConstAsciiSlice("runwayTime")
  val _time = ConstAsciiSlice("time")
  val _centre = ConstAsciiSlice("centre")

  val LOC_UNDEFINED: Int = 0
  val LOC_DEPARTURE: Int = 1
  val LOC_ARRIVAL: Int = 2

  // only created on-demand if we have to parse strings
  lazy protected val bb = new AsciiBuffer(config.getIntOrElse("buffer-size", 100000))

  val slicer = new SliceSplitter(' ')
  protected val valueSlice = MutAsciiSlice.empty

  override def translate(src: Any): Option[Any] = {
    src match {
      case s: String =>
        bb.encode(s)
        parse(bb.data, 0, bb.len)
      case Some(s: String) =>
        bb.encode(s)
        parse(bb.data, 0, bb.len)
      case s: ByteSlice =>
        parse(s.data,s.off,s.limit)
      case bs: Array[Byte] =>
        parse(bs,0,bs.length)
      case _ => None // unsupported input
    }
  }

  def parse (bs: Array[Byte], off: Int, length: Int): Option[Any] = {
    parseTracks(bs,off,length)
    if (artccId != null && elements.nonEmpty) return Some(elements) else None
  }

  //--- basic data parse methods that do not wrap results (collections) into Option

  def parseTracks(bs: Array[Byte], off: Int, length: Int): SfdpsTracks = {
    if (checkIfMessageCollection(bs,off,length)) {
      parseMessageCollectionInitialized
    } else {
      SfdpsTracks.empty
    }
  }

  def parseTracks(slice: ByteSlice): Seq[IdentifiableObject] = parseTracks(slice.data,slice.off,slice.len)

  def checkIfMessageCollection (bs: Array[Byte], off: Int, length: Int): Boolean = {
    if (initialize(bs,off,length)) {
      if (parseNextTag && isStartTag) return tag == _messageCollection
    }
    false
  }

  def parseMessageCollectionInitialized: SfdpsTracks = {
    clearElements
    parseMessageCollection
    elements
  }

  //--- override in subclasses if we filter
  protected def filterSrc (srcId: String) = false
  protected def filterTrack (track: SfdpsTrack) = false

  protected def parseMessageCollection: Unit = {
    artccId = null
    while (parseNextTag) {
      if (isStartTag) {
        if (tag == _flight) {
          parseFlight
          if (artccId == null) return // it was filtered
        }
      }
    }
  }

  // generated by script/smg gen flightIdentification pos position x y surveillance altitude arrival departure actual estimated
  protected def parseFlight: Unit = {
    var id, cs: String = null
    var src: String = null
    var lat, lon, vx, vy: Double = UndefinedDouble
    var alt: Length = UndefinedLength
    var spd: Speed = UndefinedSpeed
    var vr: Speed = UndefinedSpeed // there is no vertical rate in FIXM_v3_2
    var date = DateTime.UndefinedDateTime
    var arrivalPoint: String = "?"
    var arrivalDate: DateTime = DateTime.UndefinedDateTime
    var departurePoint: String = "?"
    var departureDate: DateTime = DateTime.UndefinedDateTime
    var status: Int = 0

    var loc: Int = LOC_UNDEFINED

    def flight (data: Array[Byte], off: Int, len: Int): Unit = {
      if (isStartTag) {
        //--- matcher generated by gov.nasa.race.tool.StringMatchGenerator

        def readSpeed: Speed = {
          val u = if (parseAttr(_uom)) attrValue else ConstUtf8Slice.EmptySlice

          if (parseSingleContentString) {
            val v = contentString.toDouble
            if (u == _mph) UsMilesPerHour(v)
            else if (u == _kmh) KilometersPerHour(v)
            else Knots(v)

          } else UndefinedSpeed
        }

        def readAltitude: Length = {
          val u = if (parseAttr(_uom)) attrValue else ConstUtf8Slice.EmptySlice

          if (parseSingleContentString) {
            val v = contentString.toDouble
            if (u == _meters) Meters(v) else Feet(v)
          } else UndefinedLength
        }

        @inline def process_flightIdentification = {
          while ((id == null || cs == null) && parseNextAttr) {
            val off = attrName.off
            val len = attrName.len

            @inline def match_computerId = {
              len == 10 && data(off) == 99 && data(off + 1) == 111 && data(off + 2) == 109 && data(off + 3) == 112 && data(off + 4) == 117 && data(off + 5) == 116 && data(off + 6) == 101 && data(off + 7) == 114 && data(off + 8) == 73 && data(off + 9) == 100
            }

            @inline def match_aircraftIdentification = {
              len == 22 && data(off) == 97 && data(off + 1) == 105 && data(off + 2) == 114 && data(off + 3) == 99 && data(off + 4) == 114 && data(off + 5) == 97 && data(off + 6) == 102 && data(off + 7) == 116 && data(off + 8) == 73 && data(off + 9) == 100 && data(off + 10) == 101 && data(off + 11) == 110 && data(off + 12) == 116 && data(off + 13) == 105 && data(off + 14) == 102 && data(off + 15) == 105 && data(off + 16) == 99 && data(off + 17) == 97 && data(off + 18) == 116 && data(off + 19) == 105 && data(off + 20) == 111 && data(off + 21) == 110
            }

            if (match_computerId) {
              id = attrValue.intern
            } else if (match_aircraftIdentification) {
              cs = attrValue.intern
            }
          }
        }

        @inline def process_pos = {
          if (tagHasParent(_location)) {
            if (parseSingleContentString) {
              slicer.setSource(contentString)
              if (slicer.hasNext) lat = slicer.next(valueSlice).toDouble
              if (slicer.hasNext) lon = slicer.next(valueSlice).toDouble
            }
          }
        }

        @inline def process_position = {
          if (tagHasParent(_enRoute)) {
            if (parseAttr(_positionTime)) date = DateTime.parseYMDTSlice(attrValue)
          }
        }

        @inline def process_x = vx = readDoubleContent // we ignore uom since we just use content value to compute heading
        @inline def process_y = vy = readDoubleContent

        @inline def process_surveillance = if (tagHasParent(_actualSpeed)) spd = readSpeed

        @inline def process_altitude = alt = readAltitude

        @inline def process_arrival = {
          if (parseAttr(_arrivalPoint)) arrivalPoint = attrValue.intern
          loc = LOC_ARRIVAL
        }

        @inline def process_departure = {
          if (parseAttr(_departurePoint)) departurePoint = attrValue.intern
          loc = LOC_DEPARTURE
        }

        def process_actual = {
          if (tagHasParent(_runwayTime)) {
            if (parseAttr(_time)) {
              val d = DateTime.parseYMDTSlice(attrValue)
              if (loc == LOC_DEPARTURE) {
                departureDate = d
              } else if (loc == LOC_ARRIVAL) {
                arrivalDate = d
                status |= TrackedObject.CompletedFlag
              }
            }
          }
        }

        def process_estimated = {
          if (tagHasParent(_runwayTime)) {
            if (loc == LOC_ARRIVAL) {
              if (parseAttr(_time)) arrivalDate = DateTime.parseYMDTSlice(attrValue)
            }
            // there should be no estimated departure dates - this is a flight in progress
          }
        }

        @inline def match_flightIdentification = {
          len == 20 && data(off) == 102 && data(off + 1) == 108 && data(off + 2) == 105 && data(off + 3) == 103 && data(off + 4) == 104 && data(off + 5) == 116 && data(off + 6) == 73 && data(off + 7) == 100 && data(off + 8) == 101 && data(off + 9) == 110 && data(off + 10) == 116 && data(off + 11) == 105 && data(off + 12) == 102 && data(off + 13) == 105 && data(off + 14) == 99 && data(off + 15) == 97 && data(off + 16) == 116 && data(off + 17) == 105 && data(off + 18) == 111 && data(off + 19) == 110
        }

        @inline def match_pos = {
          len >= 3 && data(off) == 112 && data(off + 1) == 111 && data(off + 2) == 115
        }

        @inline def match_pos_len = {
          len == 3
        }

        @inline def match_position = {
          len == 8 && data(off + 3) == 105 && data(off + 4) == 116 && data(off + 5) == 105 && data(off + 6) == 111 && data(off + 7) == 110
        }

        @inline def match_x = {
          len == 1 && data(off) == 120
        }

        @inline def match_y = {
          len == 1 && data(off) == 121
        }

        @inline def match_surveillance = {
          len == 12 && data(off) == 115 && data(off + 1) == 117 && data(off + 2) == 114 && data(off + 3) == 118 && data(off + 4) == 101 && data(off + 5) == 105 && data(off + 6) == 108 && data(off + 7) == 108 && data(off + 8) == 97 && data(off + 9) == 110 && data(off + 10) == 99 && data(off + 11) == 101
        }

        @inline def match_a = {
          len >= 1 && data(off) == 97
        }

        @inline def match_altitude = {
          len == 8 && data(off + 1) == 108 && data(off + 2) == 116 && data(off + 3) == 105 && data(off + 4) == 116 && data(off + 5) == 117 && data(off + 6) == 100 && data(off + 7) == 101
        }

        @inline def match_arrival = {
          len == 7 && data(off + 1) == 114 && data(off + 2) == 114 && data(off + 3) == 105 && data(off + 4) == 118 && data(off + 5) == 97 && data(off + 6) == 108
        }

        @inline def match_actual = {
          len == 6 && data(off + 1) == 99 && data(off + 2) == 116 && data(off + 3) == 117 && data(off + 4) == 97 && data(off + 5) == 108
        }

        @inline def match_departure = {
          len == 9 && data(off) == 100 && data(off + 1) == 101 && data(off + 2) == 112 && data(off + 3) == 97 && data(off + 4) == 114 && data(off + 5) == 116 && data(off + 6) == 117 && data(off + 7) == 114 && data(off + 8) == 101
        }

        @inline def match_estimated = {
          len == 9 && data(off) == 101 && data(off + 1) == 115 && data(off + 2) == 116 && data(off + 3) == 105 && data(off + 4) == 109 && data(off + 5) == 97 && data(off + 6) == 116 && data(off + 7) == 101 && data(off + 8) == 100
        }

        if (match_flightIdentification) {
          process_flightIdentification
        } else if (match_pos) {
          if (match_pos_len) {
            process_pos
          } else if (match_position) {
            process_position
          }
        } else if (match_x) {
          process_x
        } else if (match_y) {
          process_y
        } else if (match_surveillance) {
          process_surveillance
        } else if (match_a) {
          if (match_altitude) {
            process_altitude
          } else if (match_arrival) {
            process_arrival
          } else if (match_actual) {
            process_actual
          }
        } else if (match_departure) {
          process_departure
        } else if (match_estimated) {
          process_estimated
        }
      }
    }

    // this is an SFDPS specific quirk - there is no src attribute in the MessageCollection elem,
    // but all flight childElements have the same src attr - we have to dig it out from the first one
    if (parseAttr(_centre)) src = attrValue.intern
    if (artccId == null) { // first src spec in this MessageCollection
      if (filterSrc(src)) return // bail out - not a relevant source

      artccId = src
      elements.src = src
    } else {
      if (artccId != src) throw new RuntimeException(s"conflicting 'centre' attributes: $artccId - $src")
    }

    parseElement(flight)

    if (cs != null) {
      val track = if ((status & TrackedObject.CompletedFlag) != 0) {
        // we have to use undefined positions if we don't cache tracks
        SfdpsTrack(id, cs, GeoPosition.undefinedPos, spd, UndefinedAngle, vr, date, status,
          src, departurePoint, departureDate, arrivalPoint, arrivalDate)

      } else {
        if (lat.isDefined && lon.isDefined && date.isDefined &&
          vx.isDefined && vy.isDefined && spd.isDefined && alt.isDefined) {
          SfdpsTrack(id, cs, GeoPosition(Degrees(lat),Degrees(lon),alt),
                                 spd, Degrees(Math.atan2(vx, vy).toDegrees), vr, date, status,
            src, departurePoint, departureDate, arrivalPoint, arrivalDate)
        } else null
      }

      if (!filterSrc(src) && (track != null && !filterTrack(track))) elements += track
    }
  }
}
