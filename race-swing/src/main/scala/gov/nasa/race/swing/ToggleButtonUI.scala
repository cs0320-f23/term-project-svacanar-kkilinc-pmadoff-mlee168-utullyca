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
package gov.nasa.race.swing

import java.awt.{BasicStroke, Color, Dimension, FontMetrics, Graphics, Graphics2D}

import javax.swing.{JComponent, JToggleButton}
import javax.swing.plaf.basic.BasicButtonUI

object ToggleButtonUI {
  final val DefaultTextGap = 8
  final val DefaultIconHeightFactor = 0.7
  final val DefaultIconStroke = new BasicStroke(1.5f)
}

/**
  * very simplistic plaf that is more suitable for HiDPI
  */
abstract class ToggleButtonUI extends BasicButtonUI {
  import ToggleButtonUI._

  protected var iconWidth = 0
  protected var iconHeight = 0

  protected var xBase = 0
  protected var yBase = 0

  protected def paintIcon(c: JToggleButton, g: Graphics2D, x: Int, y: Int, w: Int, h: Int)

  protected def iconOutlineClr (c: JComponent): Color = modifyColor(c.getForeground, 0.7)
  protected def iconFillClr (c: JComponent): Color = modifyColor(c.getForeground, 0.3)

  override def paint(g: Graphics, c: JComponent): Unit = {
    val g2 = g.asInstanceOf[Graphics2D]
    val b = c.asInstanceOf[JToggleButton]
    val txt = b.getText
    val dim: Dimension = b.getSize

    val y = (dim.height - iconHeight) / 2
    val x = y

    //--- draw text
    if (txt != null && txt.nonEmpty) {
      g2.drawString(txt, xBase, yBase)
    }

    //--- draw selection symbol
    val icon = if (b.isSelected) b.getSelectedIcon else b.getIcon
    if (icon !=  null){
      icon.paintIcon(b, g2, x, y)
    } else {
      paintIcon(b, g2, x, y, iconWidth, iconHeight)
    }
  }

  override def getPreferredSize (c: JComponent): Dimension = {
    val b = c.asInstanceOf[JToggleButton]
    val txt = b.getText
    val icon = b.getIcon
    val in = b.getInsets

    var h = 0
    var w = 0

    if (icon != null){
      iconWidth = icon.getIconWidth
      iconHeight = icon.getIconHeight

      h = iconHeight
      w = iconWidth
    }

    val fnt = b.getFont
    val fm = b.getFontMetrics(fnt)
    val sw = fm.stringWidth(txt)
    val sh = fm.getHeight

    if (sh > h) h = sh
    w += sw

    yBase = (h - sh)/2 + fm.getMaxAscent + in.top
    xBase = (if (icon != null) icon.getIconWidth  else sh) + Math.max(b.getIconTextGap,DefaultTextGap)

    if (icon == null){
      iconWidth = (sh * DefaultIconHeightFactor).toInt | 1 // make odd
      iconHeight = iconWidth
    }

    w += in.left + in.right
    h += in.top + in.bottom

    new Dimension(w,h)
  }
}

class RadioButtonUI extends ToggleButtonUI {

  protected def paintIcon(c: JToggleButton, g: Graphics2D, x: Int, y: Int, w: Int, h: Int): Unit = {
    g.setColor(iconOutlineClr(c))
    g.drawOval(x,y,w,h)

    g.setColor( if (c.isSelected) c.getForeground else iconFillClr(c))
    g.fillOval(x+2,y+2,w-4,h-4)
  }
}

class CheckBoxUI extends ToggleButtonUI {

  protected def paintIcon(c: JToggleButton, g: Graphics2D, x: Int, y: Int, w: Int, h: Int): Unit = {
    g.setColor(iconOutlineClr(c))
    g.drawRect(x,y,w,h)

    g.setColor(iconFillClr(c))
    g.fillRect(x+1,y+1,w-1,h-1)

    if (c.isSelected) {
      g.setColor(c.getForeground)
      val x0 = x+3
      val x1 = x+w-3
      val y0 = y+3
      val y1 = y+w-3

      g.setStroke(ToggleButtonUI.DefaultIconStroke)
      g.drawLine(x0,y0,x1,y1)
      g.drawLine(x1,y0,x0,y1)
    }
  }
}