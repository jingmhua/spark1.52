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

package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.errors.attachTree
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeGenContext, GeneratedExpressionCode}
import org.apache.spark.sql.types._

/**
 * A bound reference points to a specific slot in the input tuple, allowing the actual value
 * to be retrieved more efficiently.  However, since operations like column pruning can change
 * the layout of intermediate tuples, BindReferences should be run after all such transformations.
  * 绑定引用指向输入元组中的特定槽,允许更有效地检索实际值,
  * 但是,由于列修剪等操作可以更改中间元组的布局,因此应在所有此类转换后运行BindReferences
 */
case class BoundReference(ordinal: Int, dataType: DataType, nullable: Boolean)
  extends LeafExpression with NamedExpression {

  override def toString: String = s"input[$ordinal, $dataType]"

  // Use special getter for primitive types (for UnsafeRow)
  //对原始类型使用特殊的getter(对于UnsafeRow)
  override def eval(input: InternalRow): Any = {
    if (input.isNullAt(ordinal)) {
      null
    } else {
      dataType match {
        case BooleanType => input.getBoolean(ordinal)
        case ByteType => input.getByte(ordinal)
        case ShortType => input.getShort(ordinal)
        case IntegerType | DateType => input.getInt(ordinal)
        case LongType | TimestampType => input.getLong(ordinal)
        case FloatType => input.getFloat(ordinal)
        case DoubleType => input.getDouble(ordinal)
        case StringType => input.getUTF8String(ordinal)
        case BinaryType => input.getBinary(ordinal)
        case CalendarIntervalType => input.getInterval(ordinal)
        case t: DecimalType => input.getDecimal(ordinal, t.precision, t.scale)
        case t: StructType => input.getStruct(ordinal, t.size)
        case _: ArrayType => input.getArray(ordinal)
        case _: MapType => input.getMap(ordinal)
        case _ => input.get(ordinal, dataType)
      }
    }
  }

  override def name: String = s"i[$ordinal]"

  override def toAttribute: Attribute = throw new UnsupportedOperationException

  override def qualifiers: Seq[String] = throw new UnsupportedOperationException

  override def exprId: ExprId = throw new UnsupportedOperationException

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val javaType = ctx.javaType(dataType)
    val value = ctx.getValue("i", dataType, ordinal.toString)
    s"""
      boolean ${ev.isNull} = i.isNullAt($ordinal);
      $javaType ${ev.primitive} = ${ev.isNull} ? ${ctx.defaultValue(dataType)} : ($value);
    """
  }
}

object BindReferences extends Logging {

  def bindReference[A <: Expression](
      expression: A,
      input: Seq[Attribute],
      allowFailures: Boolean = false): A = {
    expression.transform { case a: AttributeReference =>
      attachTree(a, "Binding attribute") {
        val ordinal = input.indexWhere(_.exprId == a.exprId)
        if (ordinal == -1) {
          if (allowFailures) {
            a
          } else {
            sys.error(s"Couldn't find $a in ${input.mkString("[", ",", "]")}")
          }
        } else {
          BoundReference(ordinal, a.dataType, a.nullable)
        }
      }
    }.asInstanceOf[A] // Kind of a hack, but safe.  TODO: Tighten return type when possible.
  }
}
