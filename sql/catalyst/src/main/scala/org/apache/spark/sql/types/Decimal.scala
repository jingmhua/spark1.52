/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.types

import java.math.{RoundingMode, MathContext}

import org.apache.spark.annotation.DeveloperApi

/**
 * A mutable implementation of BigDecimal that can hold a Long if values are small enough.
 * BigDecimal的可变实现,如果值足够小,可以保存Long
 * The semantics of the fields are as follows:
  * 字段的语义如下：
 * - _precision and _scale represent the SQL precision and scale we are looking for
 * - If decimalVal is set, it represents the whole decimal value
 * - Otherwise, the decimal value is longVal / (10 ** _scale)
 */
final class Decimal extends Ordered[Decimal] with Serializable {
  import org.apache.spark.sql.types.Decimal._

  private var decimalVal: BigDecimal = null
  private var longVal: Long = 0L
  private var _precision: Int = 1
  private var _scale: Int = 0

  def precision: Int = _precision
  def scale: Int = _scale

  /**
   * Set this Decimal to the given Long. Will have precision 20 and scale 0.
    * 将此Decimal设置为给定的Long,将具有精度20和比例0
   */
  def set(longVal: Long): Decimal = {
    if (longVal <= -POW_10(MAX_LONG_DIGITS) || longVal >= POW_10(MAX_LONG_DIGITS)) {
      // We can't represent this compactly as a long without risking overflow
      this.decimalVal = BigDecimal(longVal)
      this.longVal = 0L
    } else {
      this.decimalVal = null
      this.longVal = longVal
    }
    this._precision = 20
    this._scale = 0
    this
  }

  /**
   * Set this Decimal to the given Int. Will have precision 10 and scale 0.
    * 将此Decimal设置为给定的Int,精度为10,刻度为0
   */
  def set(intVal: Int): Decimal = {
    this.decimalVal = null
    this.longVal = intVal
    this._precision = 10
    this._scale = 0
    this
  }

  /**
   * Set this Decimal to the given unscaled Long, with a given precision and scale.
    * 将此Decimal设置为给定的未缩放Long,具有给定的精度和比例
   */
  def set(unscaled: Long, precision: Int, scale: Int): Decimal = {
    if (setOrNull(unscaled, precision, scale) == null) {
      throw new IllegalArgumentException("Unscaled value too large for precision")
    }
    this
  }

  /**
   * Set this Decimal to the given unscaled Long, with a given precision and scale,
   * and return it, or return null if it cannot be set due to overflow.
    * 将此Decimal设置为给定的未缩放Long,具有给定的精度和小数,并返回它,如果由于溢出而无法设置,则返回null
   */
  def setOrNull(unscaled: Long, precision: Int, scale: Int): Decimal = {
    if (unscaled <= -POW_10(MAX_LONG_DIGITS) || unscaled >= POW_10(MAX_LONG_DIGITS)) {
      // We can't represent this compactly as a long without risking overflow
      if (precision < 19) {
        return null  // Requested precision is too low to represent this value
      }
      this.decimalVal = BigDecimal(unscaled, scale)
      this.longVal = 0L
    } else {
      val p = POW_10(math.min(precision, MAX_LONG_DIGITS))
      if (unscaled <= -p || unscaled >= p) {
        return null  // Requested precision is too low to represent this value
      }
      this.decimalVal = null
      this.longVal = unscaled
    }
    this._precision = precision
    this._scale = scale
    this
  }

  /**
   * Set this Decimal to the given BigDecimal value, with a given precision and scale.
    * 将此Decimal设置为给定的BigDecimal值,具有给定的精度和比例
   */
  def set(decimal: BigDecimal, precision: Int, scale: Int): Decimal = {
    this.decimalVal = decimal.setScale(scale, ROUNDING_MODE)
    require(decimalVal.precision <= precision, "Overflowed precision")
    this.longVal = 0L
    this._precision = precision
    this._scale = scale
    this
  }

  /**
   * Set this Decimal to the given BigDecimal value, inheriting its precision and scale.
    * 将此Decimal设置为给定的BigDecimal值,继承其精度和比例
   */
  def set(decimal: BigDecimal): Decimal = {
    this.decimalVal = decimal
    this.longVal = 0L
    this._precision = decimal.precision
    this._scale = decimal.scale
    this
  }

  /**
   * Set this Decimal to the given Decimal value.
    * 将此Decimal设置为给定的Decimal值
   */
  def set(decimal: Decimal): Decimal = {
    this.decimalVal = decimal.decimalVal
    this.longVal = decimal.longVal
    this._precision = decimal._precision
    this._scale = decimal._scale
    this
  }

  def toBigDecimal: BigDecimal = {
    if (decimalVal.ne(null)) {
      decimalVal
    } else {
      BigDecimal(longVal, _scale)
    }
  }

  def toJavaBigDecimal: java.math.BigDecimal = toBigDecimal.underlying()

  def toUnscaledLong: Long = {
    if (decimalVal.ne(null)) {
      decimalVal.underlying().unscaledValue().longValue()
    } else {
      longVal
    }
  }

  override def toString: String = toBigDecimal.toString()

  @DeveloperApi
  def toDebugString: String = {
    if (decimalVal.ne(null)) {
      s"Decimal(expanded,$decimalVal,$precision,$scale})"
    } else {
      s"Decimal(compact,$longVal,$precision,$scale})"
    }
  }

  def toDouble: Double = toBigDecimal.doubleValue()

  def toFloat: Float = toBigDecimal.floatValue()

  def toLong: Long = {
    if (decimalVal.eq(null)) {
      longVal / POW_10(_scale)
    } else {
      decimalVal.longValue()
    }
  }

  def toInt: Int = toLong.toInt

  def toShort: Short = toLong.toShort

  def toByte: Byte = toLong.toByte

  /**
   * Update precision and scale while keeping our value the same, and return true if successful.
    * 更新精度和比例,同时保持我们的值相同,如果成功则返回true
   *
   * @return true if successful, false if overflow would occur
   */
  def changePrecision(precision: Int, scale: Int): Boolean = {
    // fast path for UnsafeProjection UnsafeProjection的快速路径
    if (precision == this.precision && scale == this.scale) {
      return true
    }
    // First, update our longVal if we can, or transfer over to using a BigDecimal
    //首先,如果我们可以更新我们的longVal.或者转移到使用BigDecimal
    if (decimalVal.eq(null)) {
      if (scale < _scale) {
        // Easier case: we just need to divide our scale down
        //更容易的情况：我们只需要缩小我们的规模
        val diff = _scale - scale
        val droppedDigits = longVal % POW_10(diff)
        longVal /= POW_10(diff)
        if (math.abs(droppedDigits) * 2 >= POW_10(diff)) {
          longVal += (if (longVal < 0) -1L else 1L)
        }
      } else if (scale > _scale) {
        // We might be able to multiply longVal by a power of 10 and not overflow, but if not,
        // switch to using a BigDecimal
        //我们或许可以将longVal乘以10的幂并且不会溢出,但如果没有,则切换到使用BigDecimal
        val diff = scale - _scale
        val p = POW_10(math.max(MAX_LONG_DIGITS - diff, 0))
        if (diff <= MAX_LONG_DIGITS && longVal > -p && longVal < p) {
          // Multiplying longVal by POW_10(diff) will still keep it below MAX_LONG_DIGITS
          //将longVal乘以POW_10（差异）仍然会使其低于MAX_LONG_DIGITS
          longVal *= POW_10(diff)
        } else {
          // Give up on using Longs; switch to BigDecimal, which we'll modify below
          //放弃使用Longs; 切换到BigDecimal，我们将在下面修改
          decimalVal = BigDecimal(longVal, _scale)
        }
      }
      // In both cases, we will check whether our precision is okay below
      //在这两种情况下,我们将检查我们的精度是否正常
    }

    if (decimalVal.ne(null)) {
      // We get here if either we started with a BigDecimal, or we switched to one because we would
      // have overflowed our Long; in either case we must rescale decimalVal to the new scale.
      //如果我们以BigDecimal开始,或者我们切换到一个,因为我们会溢出Long;
      //在任何一种情况下,我们必须将decimalVal重新调整为新的比例。
      val newVal = decimalVal.setScale(scale, ROUNDING_MODE)
      if (newVal.precision > precision) {
        return false
      }
      decimalVal = newVal
    } else {
      // We're still using Longs, but we should check whether we match the new precision
      //我们仍在使用Longs，但我们应该检查是否匹配新的精度
      val p = POW_10(math.min(precision, MAX_LONG_DIGITS))
      if (longVal <= -p || longVal >= p) {
        // Note that we shouldn't have been able to fix this by switching to BigDecimal
        //请注意,我们不应该通过切换到BigDecimal来解决这个问题
        return false
      }
    }

    _precision = precision
    _scale = scale
    true
  }

  override def clone(): Decimal = new Decimal().set(this)

  override def compare(other: Decimal): Int = {
    if (decimalVal.eq(null) && other.decimalVal.eq(null) && _scale == other._scale) {
      if (longVal < other.longVal) -1 else if (longVal == other.longVal) 0 else 1
    } else {
      toBigDecimal.compare(other.toBigDecimal)
    }
  }

  override def equals(other: Any): Boolean = other match {
    case d: Decimal =>
      compare(d) == 0
    case _ =>
      false
  }

  override def hashCode(): Int = toBigDecimal.hashCode()

  def isZero: Boolean = if (decimalVal.ne(null)) decimalVal == BIG_DEC_ZERO else longVal == 0

  def + (that: Decimal): Decimal = {
    if (decimalVal.eq(null) && that.decimalVal.eq(null) && scale == that.scale) {
      Decimal(longVal + that.longVal, Math.max(precision, that.precision), scale)
    } else {
      Decimal(toBigDecimal + that.toBigDecimal)
    }
  }

  def - (that: Decimal): Decimal = {
    if (decimalVal.eq(null) && that.decimalVal.eq(null) && scale == that.scale) {
      Decimal(longVal - that.longVal, Math.max(precision, that.precision), scale)
    } else {
      Decimal(toBigDecimal - that.toBigDecimal)
    }
  }

  // HiveTypeCoercion will take care of the precision, scale of result
  //HiveTypeCoercion将处理结果的精度和规模
  def * (that: Decimal): Decimal =
    Decimal(toJavaBigDecimal.multiply(that.toJavaBigDecimal, MATH_CONTEXT))

  def / (that: Decimal): Decimal =
    if (that.isZero) null else Decimal(toJavaBigDecimal.divide(that.toJavaBigDecimal, MATH_CONTEXT))

  def % (that: Decimal): Decimal =
    if (that.isZero) null
    else Decimal(toJavaBigDecimal.remainder(that.toJavaBigDecimal, MATH_CONTEXT))

  def remainder(that: Decimal): Decimal = this % that

  def unary_- : Decimal = {
    if (decimalVal.ne(null)) {
      Decimal(-decimalVal, precision, scale)
    } else {
      Decimal(-longVal, precision, scale)
    }
  }

  def abs: Decimal = if (this.compare(Decimal.ZERO) < 0) this.unary_- else this
}

object Decimal {
  private val ROUNDING_MODE = BigDecimal.RoundingMode.HALF_UP

  /** Maximum number of decimal digits a Long can represent
    * Long可以表示的最大小数位数*/
  val MAX_LONG_DIGITS = 18

  private val POW_10 = Array.tabulate[Long](MAX_LONG_DIGITS + 1)(i => math.pow(10, i).toLong)

  private val BIG_DEC_ZERO = BigDecimal(0)

  private val MATH_CONTEXT = new MathContext(DecimalType.MAX_PRECISION, RoundingMode.HALF_UP)

  private[sql] val ZERO = Decimal(0)
  private[sql] val ONE = Decimal(1)

  def apply(value: Double): Decimal = new Decimal().set(value)

  def apply(value: Long): Decimal = new Decimal().set(value)

  def apply(value: Int): Decimal = new Decimal().set(value)

  def apply(value: BigDecimal): Decimal = new Decimal().set(value)

  def apply(value: java.math.BigDecimal): Decimal = new Decimal().set(value)

  def apply(value: BigDecimal, precision: Int, scale: Int): Decimal =
    new Decimal().set(value, precision, scale)

  def apply(value: java.math.BigDecimal, precision: Int, scale: Int): Decimal =
    new Decimal().set(value, precision, scale)

  def apply(unscaled: Long, precision: Int, scale: Int): Decimal =
    new Decimal().set(unscaled, precision, scale)

  def apply(value: String): Decimal = new Decimal().set(BigDecimal(value))

  // Evidence parameters for Decimal considered either as Fractional or Integral. We provide two
  // parameters inheriting from a common trait since both traits define mkNumericOps.
  // See scala.math's Numeric.scala for examples for Scala's built-in types.

  /** Common methods for Decimal evidence parameters */
  private[sql] trait DecimalIsConflicted extends Numeric[Decimal] {
    override def plus(x: Decimal, y: Decimal): Decimal = x + y
    override def times(x: Decimal, y: Decimal): Decimal = x * y
    override def minus(x: Decimal, y: Decimal): Decimal = x - y
    override def negate(x: Decimal): Decimal = -x
    override def toDouble(x: Decimal): Double = x.toDouble
    override def toFloat(x: Decimal): Float = x.toFloat
    override def toInt(x: Decimal): Int = x.toInt
    override def toLong(x: Decimal): Long = x.toLong
    override def fromInt(x: Int): Decimal = new Decimal().set(x)
    override def compare(x: Decimal, y: Decimal): Int = x.compare(y)
  }

  /** A [[scala.math.Fractional]] evidence parameter for Decimals. */
  private[sql] object DecimalIsFractional extends DecimalIsConflicted with Fractional[Decimal] {
    override def div(x: Decimal, y: Decimal): Decimal = x / y
  }

  /** A [[scala.math.Integral]] evidence parameter for Decimals. */
  private[sql] object DecimalAsIfIntegral extends DecimalIsConflicted with Integral[Decimal] {
    override def quot(x: Decimal, y: Decimal): Decimal = x / y
    override def rem(x: Decimal, y: Decimal): Decimal = x % y
  }
}
