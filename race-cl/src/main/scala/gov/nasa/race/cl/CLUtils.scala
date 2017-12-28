/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.cl

import gov.nasa.race._
import java.nio._

import org.lwjgl.PointerBuffer
import org.lwjgl.opencl.CL10._
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.memUTF8
import org.lwjgl.system.{MemoryStack, MemoryUtil}

import scala.reflect.ClassTag


/**
  * general OpenCL utilities for LWJGL
  *
  * NOTE - LWJGL promotes the use of MemoryStack as a means to save heap object allocation, but be aware
  * this depends on escape analysis, i.e. those are really stack objects that have to stay inside function scope.
  * If you need permanent buffers (allocated outside Java heap), use org.lwjgl.system.MemoryUtil
  *
  * NOTE also - while LWJGL appears to use plain java.nio.Buffers they do treat the buffer objects (not the
  * buffer content) as invariant, hence all access has to be absolute (with explicit index). They also use
  * sun.misc.Unsafe to access the physical buffer - see https://blog.lwjgl.org/memory-management-in-lwjgl-3/
  */
object CLUtils {

  //--- generic return value error checker for cl.. functions
  @inline def checkCLError(ec: Int) = if (ec != CL_SUCCESS) throw new RuntimeException(f"OpenCL error [0x$ec%X]")

  implicit class CLErrCheck (val ec: Int) extends AnyVal {
    @inline def ?(): Unit = checkCLError(ec)
  }

  //--- syntactic sugar for buffer access
  implicit class IntBuf (val b: IntBuffer) extends AnyVal {
    @inline def apply (i: Int) = b.get(i)
    @inline def update (i: Int, v: Int) = b.put(i,v)
    @inline def toInt = b.get(0)
  }
  implicit class LongBuf (val b: LongBuffer) extends AnyVal {
    @inline def apply (i: Int) = b.get(i)
    @inline def update (i: Int, v: Long) = b.put(i,v)
    @inline def toLong = b.get(0)
  }
  implicit class FloatBuf (val b: FloatBuffer) extends AnyVal {
    @inline def apply (i: Int) = b.get(i)
    @inline def update (i: Int, v: Float) = b.put(i,v)
    @inline def toFloat = b.get(0)
  }
  implicit class DoubleBuf (val b: DoubleBuffer) extends AnyVal {
    @inline def apply (i: Int) = b.get(i)
    @inline def update (i: Int, v: Double) = b.put(i,v)
    @inline def toDouble = b.get(0)

  }
  implicit class PtrBuf (val b: PointerBuffer) extends AnyVal {
    @inline def apply (i: Int) = b.get(i)
    @inline def update (i: Int, p: Long) = b.put(i,p)

    def foreach (f: Long=>Unit) = {
      for (i <- 0 until b.limit) f(b.get(i))
    }

    def toArray: Array[Long] = applyTo(new Array[Long](b.limit)) { a =>
      loopFromTo(0, a.length) { i => a(i) = b.get(i) }
    }

    def mapToArray[T: ClassTag](f: (Long)=>T): Array[T] = applyTo(new Array[T](b.limit)) { a =>
      loopFromTo(0,a.length) { i => a(i) = f(b(i)) }
    }

    def mapToArrayWithIndex[T: ClassTag](f: (Int,Long)=>T): Array[T] = {
      applyTo(new Array[T](b.limit)) { a =>
        loopFromTo(0,a.length) { i =>
          a(i) = f(i, b(i))
        }
      }
    }
  }

  final val NullByteBuffer = null.asInstanceOf[ByteBuffer]
  final val EmptyByteBuffer = MemoryUtil.memAlloc(0)

  final val NullIntBuffer = null.asInstanceOf[IntBuffer]
  final val EmptyIntBuffer = MemoryUtil.memAllocInt(0)

  final val NullPointerBuffer = null.asInstanceOf[PointerBuffer]
  final val EmptyPointerBuffer = MemoryUtil.memAllocPointer(0)

  //--- syntactic sugar for stack based memory allocation

  implicit class MemStack (val stack: MemoryStack) extends AnyVal {
    @inline def allocInt = stack.callocInt(1)
    @inline def allocLong = stack.callocLong(1)
    @inline def allocPointer = stack.callocPointer(1)

    @inline def allocPointerBuffer(ps: Long*) = applyTo(stack.mallocPointer(ps.size)) { pb =>
      for ((p, i) <- ps.zipWithIndex) pb(i) = p
    }

    def allocPointerBuffer[T] (ts: Iterable[T])(f: T=>Long) = {
      val n = ts.size
      val pb = stack.mallocPointer(n)
      var i = 0
      ts.foreach { t =>
        pb(i) = f(t)
        i += 1
      }
      pb
    }

    def getCLPointerBuffer(f: (IntBuffer,PointerBuffer)=>Int): PointerBuffer = {
      val pn = stack.allocInt
      f(pn,NullPointerBuffer).?
      val n = pn.toInt
      if (n > 0) {
        val pb = stack.mallocPointer(n)
        f(NullIntBuffer,pb).?
        pb
      } else {
        EmptyPointerBuffer
      }
    }
  }

  //--- getters for platform and device infos

  def getInfoInt (id: Long, param_name: Int)(f: (Long,Int,IntBuffer,PointerBuffer)=>Int): Int = {
    tryWithResource(stackPush) { stack =>
      val pd = stack.allocInt
      f(id,param_name,pd,null).?
      pd.toInt
    }
  }

  def getDeviceInfoInt(cl_device_id: Long, param_name: Int): Int = {
    getInfoInt(cl_device_id,param_name)(clGetDeviceInfo)
  }

  def getKernelInfoInt(cl_kernel_id: Long, param_name: Int): Int = {
    getInfoInt(cl_kernel_id,param_name)(clGetKernelInfo)
  }


  def getInfoLong (id: Long, param_name: Int)(f: (Long,Int,LongBuffer,PointerBuffer)=>Int): Long = {
    tryWithResource(stackPush) { stack =>
      val pd = stack.allocLong
      f(id,param_name,pd,null).?
      pd.toLong
    }
  }

  def getDeviceInfoLong(cl_device_id: Long, param_name: Int): Long = {
    getInfoLong(cl_device_id,param_name)(clGetDeviceInfo)
  }

  def getInfoPointer (id: Long, param_name: Int)(f: (Long,Int,PointerBuffer,PointerBuffer)=>Int): Long = {
    tryWithResource(stackPush) { stack =>
      val pd = stack.allocPointer
      f(id,param_name,pd,null).?
      pd(0)
    }
  }

  def getDeviceInfoPointer(cl_device_id: Long, param_name: Int): Long = {
    getInfoPointer(cl_device_id,param_name)(clGetDeviceInfo)
  }

  def getInfoStringUTF8 (id: Long, param_name: Int)(f: (Long,Int,ByteBuffer,PointerBuffer)=>Int): String = {
    tryWithResource(stackPush) { stack =>
      val pp = stack.allocPointer
      f(id, param_name, NullByteBuffer, pp).?
      val bytes = pp(0).toInt

      val buffer = stack.malloc(bytes)
      f(id, param_name, buffer, null).?
      memUTF8(buffer, bytes -1)
    }
  }

  def getPlatformInfoStringUTF8 (cl_platform_id: Long, param_name: Int): String = {
    getInfoStringUTF8(cl_platform_id,param_name)(clGetPlatformInfo)
  }

  def getDeviceInfoStringUTF8 (cl_device_id: Long, param_name: Int): String = {
    getInfoStringUTF8(cl_device_id,param_name)(clGetDeviceInfo)
  }

  def getKernelInfoStringUTF8 (cl_kernel_id: Long, param_name: Int): String = {
    getInfoStringUTF8(cl_kernel_id,param_name)(clGetKernelInfo)
  }
}
