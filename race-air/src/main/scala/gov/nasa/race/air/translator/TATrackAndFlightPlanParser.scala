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

import com.typesafe.config.Config
import gov.nasa.race.air.{FlightPlan, TATrack, TATracks}
import gov.nasa.race.common.{ASCII8Internalizer, ASCIIBuffer, AssocSeq, ClearableElementsHolder, UTF8XmlPullParser2}
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.{ConfigurableTranslator, NoConfig}
import gov.nasa.race.geo.{GeoPosition, XYPos}
import gov.nasa.race.track.{MutSrcTracks, MutSrcTracksHolder, TrackedObject}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.{Angle, DateTime, Length, Speed}


/**
  * a mutable implementation of TATracks, which is the value we export
  */
class TATracksImpl (initSize: Int) extends MutSrcTracks[TATrack](initSize) with TATracks

/**
  * translator for SWIM TATrackAndFlightPlan (TAIS) XML messages
  *
  * matcher parts are automatically generated by .tool.StringSetMatcher with "script/smg gen <tag> ..."
  */
class TATrackAndFlightPlanParser(val config: Config=NoConfig)  extends UTF8XmlPullParser2
           with ConfigurableTranslator with MutSrcTracksHolder[TATrack,TATracksImpl] {

  val allowIncompleteTrack: Boolean = if (config != null) config.getBooleanOrElse("allow-incomplete", false) else false

  override def createElements = new TATracksImpl(150)

  val taTrackAndFlightPlan = Slice("TATrackAndFlightPlan")
  val ns2TATrackAndFlightPlan = Slice("ns2:TATrackAndFlightPlan")
  val src = Slice("src")
  val record = Slice("record")

  // only created on-demand if we have to parse strings
  lazy protected val bb = new ASCIIBuffer(config.getIntOrElse("buffer-size", 120000))

  override def translate(src: Any): Option[Any] = {
    src match {
      case s: String =>
        bb.encode(s)
        parse(bb.data, 0, bb.length)
      case Some(s: String) =>
        bb.encode(s)
        parse(bb.data, 0, bb.length)
      case s: Slice =>
        parse(s.data,s.offset,s.limit)
      case bs: Array[Byte] =>
        parse(bs,0,bs.length)
      case _ => None // unsupported input
    }
  }

  protected def parse (bs: Array[Byte], off: Int, limit: Int): Option[Any] = {
    parseTracks(bs,off,limit)
    if (elements.nonEmpty) return Some(elements) else None
  }

  //--- basic data parse methods that do not wrap results (collections) into Option

  def parseTracks(bs: Array[Byte], off: Int, limit: Int): TATracks = {
    clearElements
    if (initialize(bs,off,limit)) {
      while (parseNextTag) {
        if (isStartTag) {
          if (tag == ns2TATrackAndFlightPlan || tag == taTrackAndFlightPlan) {
            parseTATrackAndFlightPlan
          }
        }
      }
    }
    elements
  }

  def parseTracks(s: Slice): TATracks = parseTracks(s.data,s.offset,s.limit)

  //--- the internal parse functions

  //--- override to filter TRACONS and tracks
  protected def filterSrc (srcId: String) = false
  protected def filterTrack (track: TATrack) = false

  def parseTATrackAndFlightPlan: Unit = {
    var srcId: String = null

    while (parseNextTag) {
      if (isStartTag) {
        if (tag == src) {
          if (parseSingleContentString) {
            srcId = contentString.intern
            if (filterSrc(srcId)) return  // bail out point for TRACON filtering
            elements.src = srcId
          }
        } else if (tag == record) {
          if (srcId != null) parseRecord(srcId)
        }
      }
    }
  }

  def parseRecord (srcId: String): Unit = {
    var trackNum: String = null
    var acId: String  = null
    var beaconCode: String = ""
    var mrtTime: DateTime = DateTime.UndefinedDateTime
    var lat,lon: Angle = UndefinedAngle
    var xPos,yPos: Length = UndefinedLength
    var vx,vy,vVert: Speed = UndefinedSpeed
    var status: Int = 0
    var reportedAltitude: Length = UndefinedLength
    var flightPlan: Option[FlightPlan] = None

    def record (data: Array[Byte], off: Int, len: Int): Unit = {
      if (isStartTag) {

        @inline def readStatusFlag(s: Slice): Int = {
          val data = this.data
          val off = s.offset
          val len = s.length

          @inline def match_active = {len == 6 && data(off) == 97 && data(off + 1) == 99 && data(off + 2) == 116 && data(off + 3) == 105 && data(off + 4) == 118 && data(off + 5) == 101}
          @inline def match_coasting = {len == 8 && data(off) == 99 && data(off + 1) == 111 && data(off + 2) == 97 && data(off + 3) == 115 && data(off + 4) == 116 && data(off + 5) == 105 && data(off + 6) == 110 && data(off + 7) == 103}
          @inline def match_drop = {len == 4 && data(off) == 100 && data(off + 1) == 114 && data(off + 2) == 111 && data(off + 3) == 112}

          if (match_active) 0
          else if (match_coasting) TATrack.CoastingFlag
          else if (match_drop) TrackedObject.DroppedFlag
          else 0
        }
        @inline def process_trackNum = trackNum = readInternedStringContent
        @inline def process_acid = acId = readInternedStringContent
        @inline def process_adsb = if (readBooleanContent) status |= TATrack.AdsbFlag
        @inline def process_mrtTime = mrtTime = readDateTimeContent
        @inline def process_xPos = xPos = NauticalMiles(readIntContent / 256.0)
        @inline def process_yPos = yPos = NauticalMiles(readIntContent / 256.0)
        @inline def process_lat = lat = Degrees(readDoubleContent)
        @inline def process_lon = lon = Degrees(readDoubleContent)
        @inline def process_vVert = vVert = FeetPerMinute(readIntContent)
        @inline def process_vx = vx = Knots(readIntContent)
        @inline def process_vy = vy = Knots(readIntContent)
        @inline def process_status = status |= readStatusFlag(readSliceContent)
        @inline def process_frozen = if (readBooleanContent) status |= TrackedObject.FrozenFlag
        @inline def process_flightPlan = flightPlan = Some(new FlightPlan) // FIXME
        @inline def process_new = if (readBooleanContent) status |= TrackedObject.NewFlag
        @inline def process_pseudo = if (readBooleanContent) status |= TATrack.PseudoFlag
        @inline def process_reportedBeaconCode = beaconCode = readStringContent
        @inline def process_reportedAltitude = reportedAltitude = Feet(readIntContent)

        @inline def match_trackNum = {len == 8 && data(off) == 116 && data(off + 1) == 114 && data(off + 2) == 97 && data(off + 3) == 99 && data(off + 4) == 107 && data(off + 5) == 78 && data(off + 6) == 117 && data(off + 7) == 109}
        @inline def match_a = {len >= 1 && data(off) == 97}
        @inline def match_acid = {len == 4 && data(off + 1) == 99 && data(off + 2) == 105 && data(off + 3) == 100}
        @inline def match_adsb = {len == 4 && data(off + 1) == 100 && data(off + 2) == 115 && data(off + 3) == 98}
        @inline def match_mrtTime = {len == 7 && data(off) == 109 && data(off + 1) == 114 && data(off + 2) == 116 && data(off + 3) == 84 && data(off + 4) == 105 && data(off + 5) == 109 && data(off + 6) == 101}
        @inline def match_xPos = {len == 4 && data(off) == 120 && data(off + 1) == 80 && data(off + 2) == 111 && data(off + 3) == 115}
        @inline def match_yPos = {len == 4 && data(off) == 121 && data(off + 1) == 80 && data(off + 2) == 111 && data(off + 3) == 115}
        @inline def match_l = {len >= 1 && data(off) == 108}
        @inline def match_lat = {len == 3 && data(off + 1) == 97 && data(off + 2) == 116}
        @inline def match_lon = {len == 3 && data(off + 1) == 111 && data(off + 2) == 110}
        @inline def match_v = {len >= 1 && data(off) == 118}
        @inline def match_vVert = {len == 5 && data(off + 1) == 86 && data(off + 2) == 101 && data(off + 3) == 114 && data(off + 4) == 116}
        @inline def match_vx = {len == 2 && data(off + 1) == 120}
        @inline def match_vy = {len == 2 && data(off + 1) == 121}
        @inline def match_status = {len == 6 && data(off) == 115 && data(off + 1) == 116 && data(off + 2) == 97 && data(off + 3) == 116 && data(off + 4) == 117 && data(off + 5) == 115}
        @inline def match_f = {len >= 1 && data(off) == 102}
        @inline def match_frozen = {len == 6 && data(off + 1) == 114 && data(off + 2) == 111 && data(off + 3) == 122 && data(off + 4) == 101 && data(off + 5) == 110}
        @inline def match_flightPlan = {len == 10 && data(off + 1) == 108 && data(off + 2) == 105 && data(off + 3) == 103 && data(off + 4) == 104 && data(off + 5) == 116 && data(off + 6) == 80 && data(off + 7) == 108 && data(off + 8) == 97 && data(off + 9) == 110}
        @inline def match_new = {len == 3 && data(off) == 110 && data(off + 1) == 101 && data(off + 2) == 119}
        @inline def match_pseudo = {len == 6 && data(off) == 112 && data(off + 1) == 115 && data(off + 2) == 101 && data(off + 3) == 117 && data(off + 4) == 100 && data(off + 5) == 111}
        @inline def match_reported = {len >= 8 && data(off) == 114 && data(off + 1) == 101 && data(off + 2) == 112 && data(off + 3) == 111 && data(off + 4) == 114 && data(off + 5) == 116 && data(off + 6) == 101 && data(off + 7) == 100}
        @inline def match_reportedBeaconCode = {len == 18 && data(off + 8) == 66 && data(off + 9) == 101 && data(off + 10) == 97 && data(off + 11) == 99 && data(off + 12) == 111 && data(off + 13) == 110 && data(off + 14) == 67 && data(off + 15) == 111 && data(off + 16) == 100 && data(off + 17) == 101}
        @inline def match_reportedAltitude = {len == 16 && data(off + 8) == 65 && data(off + 9) == 108 && data(off + 10) == 116 && data(off + 11) == 105 && data(off + 12) == 116 && data(off + 13) == 117 && data(off + 14) == 100 && data(off + 15) == 101}

        if (match_trackNum) {
          process_trackNum
        } else if (match_a) {
          if (match_acid) {
            process_acid
          } else if (match_adsb) {
            process_adsb
          }
        } else if (match_mrtTime) {
          process_mrtTime
        } else if (match_xPos) {
          process_xPos
        } else if (match_yPos) {
          process_yPos
        } else if (match_l) {
          if (match_lat) {
            process_lat
          } else if (match_lon) {
            process_lon
          }
        } else if (match_v) {
          if (match_vVert) {
            process_vVert
          } else if (match_vx) {
            process_vx
          } else if (match_vy) {
            process_vy
          }
        } else if (match_status) {
          process_status
        } else if (match_f) {
          if (match_frozen) {
            process_frozen
          } else if (match_flightPlan) {
            process_flightPlan
          }
        } else if (match_new) {
          process_new
        } else if (match_pseudo) {
          process_pseudo
        } else if (match_reported) {
          if (match_reportedBeaconCode) {
            process_reportedBeaconCode
          } else if (match_reportedAltitude) {
            process_reportedAltitude
          }
        }
      }
    }

    parseElement(record)

    if (srcId != null && trackNum != null && xPos.isDefined && yPos.isDefined) {
      if (allowIncompleteTrack || (mrtTime.isDefined && vx.isDefined && vy.isDefined && reportedAltitude.isDefined)) {
        val spd = Speed.fromVxVy(vx, vy)
        val hdg = Angle.fromVxVy(vx, vy)
        if (acId == null) acId = trackNum
        val id = getUniqueTrackId(srcId,trackNum,acId)  // note that trackNum is not unique over different Tracons

        val track = new TATrack(id,acId,GeoPosition(lat,lon,reportedAltitude),hdg,spd,vVert,mrtTime,status,
          srcId, XYPos(xPos, yPos), beaconCode, flightPlan)

        if (!filterTrack(track)) elements += track
      }
    }
  }

  val idBuf = new ASCIIBuffer(32)

  // we combine the tracon id with the trackNum in order to get a global (unique) TATrack identifier
  def getUniqueTrackId (srcId: String, trackNum: String, acid: String): String = {
    idBuf.clear
    idBuf += srcId
    idBuf += '-'
    idBuf += trackNum

    ASCII8Internalizer.get(idBuf)
  }
}
