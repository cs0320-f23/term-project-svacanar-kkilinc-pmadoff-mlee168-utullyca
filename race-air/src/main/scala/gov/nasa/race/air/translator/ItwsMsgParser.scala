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

import java.awt.image.{BufferedImage, DataBuffer, IndexColorModel}

import com.typesafe.config.Config
import gov.nasa.race.air.{PrecipImage, PrecipImageStore}
import gov.nasa.race.common.{SliceSplitter, StringXmlPullParser2}
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.config.{ConfigurableTranslator, NoConfig}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.Angle.{Degrees, UndefinedAngle}
import gov.nasa.race.uom.{Angle, DateTime, Length}
import gov.nasa.race.uom.Length.{Meters, UndefinedLength}

object ItwsMsgParser {
  val cmap = Array[Int](  // reference color model
    0xffffffff, // 0: no precipitation - transparent colormodel index
    0xffa0f000, // 1: level 1
    0xff60b000, // 2: level 2
    0xfff0f000, // 3: level 3
    0xfff0c000, // 4: level 4
    0xffe09000, // 5: level 5
    0xffa00000, // 6: level 6
    0x00000000, // 7: attenuated
    0x00000000, // 8: anomalous propagation
    0x00000000, // 9: bad value
    0x00000000, // 10: ?
    0x00000000, // 11: ?
    0x00000000, // 12: ?
    0x00000000, // 13: ? no coverage ("should not be present")
    0x00000000, // 14: ? - " -
    0x00000000  // 15: no coverage
  )

  // we make it a var so that we can set it from tests
  var colorModel =  new IndexColorModel( 8, 16, cmap, 0, true, 0, DataBuffer.TYPE_BYTE)
}

/**
  * XmlPullParser2 based parser for itws_msg (ITWS) SWIM messages
  *
  * matcher parts are automatically generated by .tool.StringSetMatcher with "script/smg gen <tag> ..."
  *
  * Note - unfortunately the maxPrecipLevel comes *after* the data, i.e. we cannot
  * upfront detect if we should parse at all
  */
class ItwsMsgParser (val config: Config=NoConfig)
  extends StringXmlPullParser2(config.getIntOrElse("buffer-size",20000)) with ConfigurableTranslator {

  val itwsMsg = Slice("itws_msg")
  val precision = Slice("precision")
  val unit = Slice("unit")
  val meters = Slice("meters")

  override def translate(src: Any): Option[Any] = {
    src match {
      case s: String => parse(s)
      case Some(s: String) => parse(s)
      case _ => None // nothing else supported yet
    }
  }

  protected def parse (msg: String): Option[Any] = {
    if (initialize(msg)) {
      if (parseNextTag) {
        if (tag == itwsMsg) return parseItwsMsg
      }
    }
    None
  }

  protected def parseItwsMsg: Option[Any] = {
    val data = this.data

    var product = 0 // 9849: precip 5nm, 9850: tracon, 9905: long range
    var itwsSite: String = null // site id (e.g. N90 - describing a list of airports/tracons)
    var genDate: DateTime = DateTime.UndefinedDateTime; var expDate: DateTime = DateTime.UndefinedDateTime;
    var trpLat: Angle=UndefinedAngle; var trpLon: Angle=UndefinedAngle; var rotation: Angle=UndefinedAngle// degrees
    var xoffset: Length=UndefinedLength; var yoffset: Length=UndefinedLength; // meters
    var dx: Length=UndefinedLength; var dy: Length=UndefinedLength; var dz: Length=UndefinedLength; // meters
    var nRows = -1; var nCols = -1;
    var maxPrecipLevel = 0; // 0-6 - no use to display/parse 0
    var img: BufferedImage = null
    var id: String = null

    def productHeader (data: Array[Byte], off: Int, len: Int): Unit = {
      // product_msg_id product_header_itws_sites product_header_generation_time_seconds product_header_expiration_time_seconds

      if (isStartTag) {
        val off = tag.offset
        val len = tag.length

        @inline def readDate: DateTime = DateTime.ofEpochMillis(1000L * readIntContent)

        @inline def process_product_msg_id = product = readIntContent
        @inline def process_product_header_itws_sites = itwsSite = readStringContent
        @inline def process_product_header_generation_time_seconds = genDate = readDate
        @inline def process_product_header_expiration_time_seconds = expDate = readDate

        @inline def match_product_ = { len>=8 && data(off)==112 && data(off+1)==114 && data(off+2)==111 && data(off+3)==100 && data(off+4)==117 && data(off+5)==99 && data(off+6)==116 && data(off+7)==95 }
        @inline def match_product_msg_id = { len==14 && data(off+8)==109 && data(off+9)==115 && data(off+10)==103 && data(off+11)==95 && data(off+12)==105 && data(off+13)==100 }
        @inline def match_product_header_ = { len>=15 && data(off+8)==104 && data(off+9)==101 && data(off+10)==97 && data(off+11)==100 && data(off+12)==101 && data(off+13)==114 && data(off+14)==95 }
        @inline def match_product_header_itws_sites = { len==25 && data(off+15)==105 && data(off+16)==116 && data(off+17)==119 && data(off+18)==115 && data(off+19)==95 && data(off+20)==115 && data(off+21)==105 && data(off+22)==116 && data(off+23)==101 && data(off+24)==115 }
        @inline def match_product_header_generation_time_seconds = { len==38 && data(off+15)==103 && data(off+16)==101 && data(off+17)==110 && data(off+18)==101 && data(off+19)==114 && data(off+20)==97 && data(off+21)==116 && data(off+22)==105 && data(off+23)==111 && data(off+24)==110 && data(off+25)==95 && data(off+26)==116 && data(off+27)==105 && data(off+28)==109 && data(off+29)==101 && data(off+30)==95 && data(off+31)==115 && data(off+32)==101 && data(off+33)==99 && data(off+34)==111 && data(off+35)==110 && data(off+36)==100 && data(off+37)==115 }
        @inline def match_product_header_expiration_time_seconds = { len==38 && data(off+15)==101 && data(off+16)==120 && data(off+17)==112 && data(off+18)==105 && data(off+19)==114 && data(off+20)==97 && data(off+21)==116 && data(off+22)==105 && data(off+23)==111 && data(off+24)==110 && data(off+25)==95 && data(off+26)==116 && data(off+27)==105 && data(off+28)==109 && data(off+29)==101 && data(off+30)==95 && data(off+31)==115 && data(off+32)==101 && data(off+33)==99 && data(off+34)==111 && data(off+35)==110 && data(off+36)==100 && data(off+37)==115 }

        if (match_product_) {
          if (match_product_msg_id) {
            process_product_msg_id
          } else if (match_product_header_) {
            if (match_product_header_itws_sites) {
              process_product_header_itws_sites
            } else if (match_product_header_generation_time_seconds) {
              process_product_header_generation_time_seconds
            } else if (match_product_header_expiration_time_seconds) {
              process_product_header_expiration_time_seconds
            }
          }
        }
      }
    }

    def precip (data: Array[Byte], off: Int, len: Int): Unit = {
      // prcp_TRP_latitude prcp_TRP_longitude prcp_rotation prcp_xoffset prcp_yoffset prcp_dx prcp_dy prcp_grid_max_y prcp_grid_max_x prcp_grid_compressed prcp_grid_max_precip_level

      if (isStartTag) {
        def readDegreesWithPrecision: Angle = {
          if (parseAttr(precision)){
            Degrees(attrValue.toDouble * readDoubleContent)
          }
          Angle.UndefinedAngle
        }

        def readMetersWithUnit: Length = {
          if (parseAttr(unit)) {
            if (attrValue == meters) Meters(readIntContent)
          }
          Length.UndefinedLength
        }

        @inline def process_prcp_TRP_latitude = trpLat = readDegreesWithPrecision
        @inline def process_prcp_TRP_longitude = trpLon = readDegreesWithPrecision
        @inline def process_prcp_rotation = rotation = readDegreesWithPrecision
        @inline def process_prcp_xoffset = xoffset = readMetersWithUnit
        @inline def process_prcp_yoffset = yoffset = readMetersWithUnit
        @inline def process_prcp_dx = dx = readMetersWithUnit
        @inline def process_prcp_dy = dy = readMetersWithUnit
        @inline def process_prcp_grid_max_y = nRows = readIntContent
        @inline def process_prcp_grid_max_x = nCols = readIntContent
        @inline def process_prcp_grid_max_precip_level = maxPrecipLevel = readIntContent
        @inline def process_prcp_grid_compressed = {
          id = PrecipImageStore.computeId(product,itwsSite,xoffset,yoffset)
          img = PrecipImageStore.imageStore.getOrElseUpdate(id, createBufferedImage(nCols, nRows, ItwsMsgParser.colorModel))
          readImage(getScanLine(nCols),img)
        }

        @inline def match_prcp_ = { len>=5 && data(off)==112 && data(off+1)==114 && data(off+2)==99 && data(off+3)==112 && data(off+4)==95 }
        @inline def match_prcp_TRP_l = { len>=10 && data(off+5)==84 && data(off+6)==82 && data(off+7)==80 && data(off+8)==95 && data(off+9)==108 }
        @inline def match_prcp_TRP_latitude = { len==17 && data(off+10)==97 && data(off+11)==116 && data(off+12)==105 && data(off+13)==116 && data(off+14)==117 && data(off+15)==100 && data(off+16)==101 }
        @inline def match_prcp_TRP_longitude = { len==18 && data(off+10)==111 && data(off+11)==110 && data(off+12)==103 && data(off+13)==105 && data(off+14)==116 && data(off+15)==117 && data(off+16)==100 && data(off+17)==101 }
        @inline def match_prcp_rotation = { len==13 && data(off+5)==114 && data(off+6)==111 && data(off+7)==116 && data(off+8)==97 && data(off+9)==116 && data(off+10)==105 && data(off+11)==111 && data(off+12)==110 }
        @inline def match_prcp_xoffset = { len==12 && data(off+5)==120 && data(off+6)==111 && data(off+7)==102 && data(off+8)==102 && data(off+9)==115 && data(off+10)==101 && data(off+11)==116 }
        @inline def match_prcp_yoffset = { len==12 && data(off+5)==121 && data(off+6)==111 && data(off+7)==102 && data(off+8)==102 && data(off+9)==115 && data(off+10)==101 && data(off+11)==116 }
        @inline def match_prcp_d = { len>=6 && data(off+5)==100 }
        @inline def match_prcp_dx = { len==7 && data(off+6)==120 }
        @inline def match_prcp_dy = { len==7 && data(off+6)==121 }
        @inline def match_prcp_grid_ = { len>=10 && data(off+5)==103 && data(off+6)==114 && data(off+7)==105 && data(off+8)==100 && data(off+9)==95 }
        @inline def match_prcp_grid_max_ = { len>=14 && data(off+10)==109 && data(off+11)==97 && data(off+12)==120 && data(off+13)==95 }
        @inline def match_prcp_grid_max_y = { len==15 && data(off+14)==121 }
        @inline def match_prcp_grid_max_x = { len==15 && data(off+14)==120 }
        @inline def match_prcp_grid_max_precip_level = { len==26 && data(off+14)==112 && data(off+15)==114 && data(off+16)==101 && data(off+17)==99 && data(off+18)==105 && data(off+19)==112 && data(off+20)==95 && data(off+21)==108 && data(off+22)==101 && data(off+23)==118 && data(off+24)==101 && data(off+25)==108 }
        @inline def match_prcp_grid_compressed = { len==20 && data(off+10)==99 && data(off+11)==111 && data(off+12)==109 && data(off+13)==112 && data(off+14)==114 && data(off+15)==101 && data(off+16)==115 && data(off+17)==115 && data(off+18)==101 && data(off+19)==100 }

        if (match_prcp_) {
          if (match_prcp_TRP_l) {
            if (match_prcp_TRP_latitude) {
              process_prcp_TRP_latitude
            } else if (match_prcp_TRP_longitude) {
              process_prcp_TRP_longitude
            }
          } else if (match_prcp_rotation) {
            process_prcp_rotation
          } else if (match_prcp_xoffset) {
            process_prcp_xoffset
          } else if (match_prcp_yoffset) {
            process_prcp_yoffset
          } else if (match_prcp_d) {
            if (match_prcp_dx) {
              process_prcp_dx
            } else if (match_prcp_dy) {
              process_prcp_dy
            }
          } else if (match_prcp_grid_) {
            if (match_prcp_grid_max_) {
              if (match_prcp_grid_max_y) {
                process_prcp_grid_max_y
              } else if (match_prcp_grid_max_x) {
                process_prcp_grid_max_x
              } else if (match_prcp_grid_max_precip_level) {
                process_prcp_grid_max_precip_level
              }
            } else if (match_prcp_grid_compressed) {
              process_prcp_grid_compressed
            }
          }
        }
      }
    }

    val endLevel = depth-1

    while (parseNextTag) {
      if (isStartTag) {
        val off = tag.offset
        val len = tag.length

        @inline def process_product_header = parseElement(productHeader)
        @inline def process_precip = parseElement(precip)

        @inline def match_pr = { len>=2 && data(off)==112 && data(off+1)==114 }
        @inline def match_product_header = { len==14 && data(off+2)==111 && data(off+3)==100 && data(off+4)==117 && data(off+5)==99 && data(off+6)==116 && data(off+7)==95 && data(off+8)==104 && data(off+9)==101 && data(off+10)==97 && data(off+11)==100 && data(off+12)==101 && data(off+13)==114 }
        @inline def match_precip = { len==6 && data(off+2)==101 && data(off+3)==99 && data(off+4)==105 && data(off+5)==112 }

        if (match_pr) {
          if (match_product_header) {
            process_product_header
          } else if (match_precip) {
            process_precip
          }
        }

      } else {
        if (depth == endLevel) {
          // TODO - check if we got everything
          val precipImage = PrecipImage( id, product, itwsSite, genDate, expDate,
                                         GeoPosition(trpLat,trpLon), xoffset, yoffset, rotation,
                                         dx * nCols, dy * nRows, maxPrecipLevel, img)
          return Some(precipImage)
        }
      }
    }
    None
  }

  //--- image support

  protected val splitter = new SliceSplitter(',')
  protected var scanLine = new Array[Byte](2056)

  def createBufferedImage (width: Int, height: Int, cm: IndexColorModel) = {
    new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, cm)
  }
  def getScanLine (w: Int) = {
    if (scanLine.length < w) scanLine = new Array[Byte](w)
    scanLine
  }

  // read content containing list of run length encoded pairs:  <prcp_grid_compressed>15,608 0,24 15,337 0,38 ... </prcp_grid_compressed>
  def readImage (scanLine: Array[Byte], img: BufferedImage): Unit = {
    val slice = readSliceContent

    if (slice.nonEmpty){
      val raster = img.getRaster
      val width = img.getWidth
      val height = img.getHeight

      var n = width    // remaining pixels to fill in scanline
      var i = 0        // index within scanline
      var j = height-1 // image row (counting backwards since BufferedImages have origin in upper left)

      splitter.setSource(slice)
      splitter.setSeparator(',')

      while (splitter.hasNext) {
        val value: Byte = splitter.next.toByte
        splitter.setSeparator(' ')
        var count: Int = splitter.next.toInt
        splitter.setSeparator(',')

        while (count > 0) {
          if (count < n) {
            n -= count
            while (count > 0){
              scanLine(i) = value
              i += 1
              count -= 1
            }
          } else {
            if (n > 0) {
              count -= n
              while (n > 0) {
                scanLine(i) = value
                i += 1
                n -= 1
              }
            }

            raster.setDataElements(0,j,width,1,scanLine)
            if (j == 0) return // done, parsed 'height' number of scanlines

            n = width
            i = 0
            j -= 1
          }
        }
      }
    }
  }
}
