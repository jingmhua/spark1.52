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

package org.apache.spark.sql.catalyst

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.types.{DataType, StructType}

/**
 * An abstract class for row used internal in Spark SQL, which only contain the columns as
 * internal types.
  * Spark SQL中内部使用的行的抽象类,仅包含列作为内部类型
 */
abstract class InternalRow extends SpecializedGetters with Serializable {

  def numFields: Int

  // This is only use for test and will throw a null pointer exception if the position is null.
  //这仅用于测试，如果位置为null,则抛出空指针异常
  def getString(ordinal: Int): String = getUTF8String(ordinal).toString

  /**
   * Make a copy of the current [[InternalRow]] object.
    * 制作当前[[InternalRow]]对象的副本
   */
  def copy(): InternalRow

  /** Returns true if there are any NULL values in this row.
    * 如果此行中有任何NULL值,则返回true*/
  def anyNull: Boolean

  /* ---------------------- utility methods for Scala ---------------------- */

  /**
   * Return a Scala Seq representing the row. Elements are placed in the same order in the Seq.
    * 返回表示行的Scala Seq,元素在Seq中以相同的顺序放置
   */
  def toSeq(fieldTypes: Seq[DataType]): Seq[Any] = {
    val len = numFields
    assert(len == fieldTypes.length)

    val values = new Array[Any](len)
    var i = 0
    while (i < len) {
      values(i) = get(i, fieldTypes(i))
      i += 1
    }
    values
  }

  def toSeq(schema: StructType): Seq[Any] = toSeq(schema.map(_.dataType))
}

object InternalRow {
  /**
   * This method can be used to construct a [[InternalRow]] with the given values.
    * 此方法可用于构造具有给定值的[[InternalRow]]
   */
  def apply(values: Any*): InternalRow = new GenericInternalRow(values.toArray)

  /**
   * This method can be used to construct a [[InternalRow]] from a [[Seq]] of values.
    * 该方法可用于从[[Seq]]值构造[[InternalRow]]
   */
  def fromSeq(values: Seq[Any]): InternalRow = new GenericInternalRow(values.toArray)

  /** Returns an empty [[InternalRow]]. */
  val empty = apply()
}
