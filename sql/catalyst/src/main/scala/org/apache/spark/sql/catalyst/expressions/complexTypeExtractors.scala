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

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.expressions.codegen.{GeneratedExpressionCode, CodeGenContext}
import org.apache.spark.sql.types._

////////////////////////////////////////////////////////////////////////////////////////////////////
// This file defines all the expressions to extract values out of complex types.
//此文件定义了从复杂类型中提取值的所有表达式
// For example, getting a field out of an array, map, or struct.
//例如,从数组,映射或结构中获取字段
////////////////////////////////////////////////////////////////////////////////////////////////////


object ExtractValue {
  /**
   * Returns the resolved `ExtractValue`. It will return one kind of concrete `ExtractValue`,
   * depend on the type of `child` and `extraction`.
   *
   *   `child`      |    `extraction`    |    concrete `ExtractValue`
   * ----------------------------------------------------------------
   *    Struct      |   Literal String   |        GetStructField
   * Array[Struct]  |   Literal String   |     GetArrayStructFields
   *    Array       |   Integral type    |         GetArrayItem
   *     Map        |   map key type     |         GetMapValue
   */
  def apply(
      child: Expression,
      extraction: Expression,
      resolver: Resolver): Expression = {

    (child.dataType, extraction) match {
      case (StructType(fields), NonNullLiteral(v, StringType)) =>
        val fieldName = v.toString
        val ordinal = findField(fields, fieldName, resolver)
        GetStructField(child, fields(ordinal).copy(name = fieldName), ordinal)

      case (ArrayType(StructType(fields), containsNull), NonNullLiteral(v, StringType)) =>
        val fieldName = v.toString
        val ordinal = findField(fields, fieldName, resolver)
        GetArrayStructFields(child, fields(ordinal).copy(name = fieldName),
          ordinal, fields.length, containsNull)

      case (_: ArrayType, _) => GetArrayItem(child, extraction)

      case (MapType(kt, _, _), _) => GetMapValue(child, extraction)

      case (otherType, _) =>
        val errorMsg = otherType match {
          case StructType(_) =>
            s"Field name should be String Literal, but it's $extraction"
          case other =>
            s"Can't extract value from $child"
        }
        throw new AnalysisException(errorMsg)
    }
  }

  /**
   * Find the ordinal of StructField, report error if no desired field or over one
   * desired fields are found.
    * 找到StructField的序数,如果找不到所需的字段或超过一个所需字段,则报告错误
   */
  private def findField(fields: Array[StructField], fieldName: String, resolver: Resolver): Int = {
    val checkField = (f: StructField) => resolver(f.name, fieldName)
    val ordinal = fields.indexWhere(checkField)
    if (ordinal == -1) {
      throw new AnalysisException(
        s"No such struct field $fieldName in ${fields.map(_.name).mkString(", ")}")
    } else if (fields.indexWhere(checkField, ordinal + 1) != -1) {
      throw new AnalysisException(
        s"Ambiguous reference to fields ${fields.filter(checkField).mkString(", ")}")
    } else {
      ordinal
    }
  }
}

/**
 * Returns the value of fields in the Struct `child`.
 * 返回Struct“child”中的字段值
 * No need to do type checking since it is handled by [[ExtractValue]].
  * 需进行类型检查，因为它由[[ExtractValue]]处理
 */
case class GetStructField(child: Expression, field: StructField, ordinal: Int)
  extends UnaryExpression {

  override def dataType: DataType = field.dataType
  override def nullable: Boolean = child.nullable || field.nullable
  override def toString: String = s"$child.${field.name}"

  protected override def nullSafeEval(input: Any): Any =
    input.asInstanceOf[InternalRow].get(ordinal, field.dataType)

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, eval => {
      s"""
        if ($eval.isNullAt($ordinal)) {
          ${ev.isNull} = true;
        } else {
          ${ev.primitive} = ${ctx.getValue(eval, dataType, ordinal.toString)};
        }
      """
    })
  }
}

/**
 * Returns the array of value of fields in the Array of Struct `child`.
 * 返回Struct“child”数组中字段值的数组
 * No need to do type checking since it is handled by [[ExtractValue]].
  * 无需进行类型检查,因为它由[[ExtractValue]]处理
 */
case class GetArrayStructFields(
    child: Expression,
    field: StructField,
    ordinal: Int,
    numFields: Int,
    containsNull: Boolean) extends UnaryExpression {

  override def dataType: DataType = ArrayType(field.dataType, containsNull)
  override def nullable: Boolean = child.nullable || containsNull || field.nullable
  override def toString: String = s"$child.${field.name}"

  protected override def nullSafeEval(input: Any): Any = {
    val array = input.asInstanceOf[ArrayData]
    val length = array.numElements()
    val result = new Array[Any](length)
    var i = 0
    while (i < length) {
      if (array.isNullAt(i)) {
        result(i) = null
      } else {
        val row = array.getStruct(i, numFields)
        if (row.isNullAt(ordinal)) {
          result(i) = null
        } else {
          result(i) = row.get(ordinal, field.dataType)
        }
      }
      i += 1
    }
    new GenericArrayData(result)
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val arrayClass = classOf[GenericArrayData].getName
    nullSafeCodeGen(ctx, ev, eval => {
      s"""
        final int n = $eval.numElements();
        final Object[] values = new Object[n];
        for (int j = 0; j < n; j++) {
          if ($eval.isNullAt(j)) {
            values[j] = null;
          } else {
            final InternalRow row = $eval.getStruct(j, $numFields);
            if (row.isNullAt($ordinal)) {
              values[j] = null;
            } else {
              values[j] = ${ctx.getValue("row", field.dataType, ordinal.toString)};
            }
          }
        }
        ${ev.primitive} = new $arrayClass(values);
      """
    })
  }
}

/**
 * Returns the field at `ordinal` in the Array `child`.
 * 返回数组`child`中`ordinal`的字段
 * We need to do type checking here as `ordinal` expression maybe unresolved.
  * 我们需要在这里进行类型检查，因为`ordinal`表达式可能尚未解析
 */
case class GetArrayItem(child: Expression, ordinal: Expression)
  extends BinaryExpression with ExpectsInputTypes {

  // We have done type checking for child in `ExtractValue`, so only need to check the `ordinal`.
  //我们在`ExtractValue`中对子进行了类型检查，因此只需要检查`ordinal`
  override def inputTypes: Seq[AbstractDataType] = Seq(AnyDataType, IntegralType)

  override def toString: String = s"$child[$ordinal]"

  override def left: Expression = child
  override def right: Expression = ordinal

  /** `Null` is returned for invalid ordinals.
    * 对于无效的序数,返回“Null”*/
  override def nullable: Boolean = true

  override def dataType: DataType = child.dataType.asInstanceOf[ArrayType].elementType

  protected override def nullSafeEval(value: Any, ordinal: Any): Any = {
    val baseValue = value.asInstanceOf[ArrayData]
    val index = ordinal.asInstanceOf[Number].intValue()
    if (index >= baseValue.numElements() || index < 0) {
      null
    } else {
      baseValue.get(index, dataType)
    }
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, (eval1, eval2) => {
      s"""
        final int index = (int) $eval2;
        if (index >= $eval1.numElements() || index < 0 || $eval1.isNullAt(index)) {
          ${ev.isNull} = true;
        } else {
          ${ev.primitive} = ${ctx.getValue(eval1, dataType, "index")};
        }
      """
    })
  }
}

/**
 * Returns the value of key `key` in Map `child`.
 *
 * We need to do type checking here as `key` expression maybe unresolved.
  * 我们需要在这里进行类型检查,因为`key`表达式可能尚未解析
 */
case class GetMapValue(child: Expression, key: Expression)
  extends BinaryExpression with ExpectsInputTypes {

  private def keyType = child.dataType.asInstanceOf[MapType].keyType

  // We have done type checking for child in `ExtractValue`, so only need to check the `key`.
  //我们在`ExtractValue`中对子进行了类型检查,所以只需要检查`key`
  override def inputTypes: Seq[AbstractDataType] = Seq(AnyDataType, keyType)

  override def toString: String = s"$child[$key]"

  override def left: Expression = child
  override def right: Expression = key

  /** `Null` is returned for invalid ordinals. 对于无效的序数,返回“Null”。*/
  override def nullable: Boolean = true

  override def dataType: DataType = child.dataType.asInstanceOf[MapType].valueType

  // todo: current search is O(n), improve it.
  protected override def nullSafeEval(value: Any, ordinal: Any): Any = {
    val map = value.asInstanceOf[MapData]
    val length = map.numElements()
    val keys = map.keyArray()

    var i = 0
    var found = false
    while (i < length && !found) {
      if (keys.get(i, keyType) == ordinal) {
        found = true
      } else {
        i += 1
      }
    }

    if (!found) {
      null
    } else {
      map.valueArray().get(i, dataType)
    }
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val index = ctx.freshName("index")
    val length = ctx.freshName("length")
    val keys = ctx.freshName("keys")
    val found = ctx.freshName("found")
    val key = ctx.freshName("key")
    nullSafeCodeGen(ctx, ev, (eval1, eval2) => {
      s"""
        final int $length = $eval1.numElements();
        final ArrayData $keys = $eval1.keyArray();

        int $index = 0;
        boolean $found = false;
        while ($index < $length && !$found) {
          final ${ctx.javaType(keyType)} $key = ${ctx.getValue(keys, keyType, index)};
          if (${ctx.genEqual(keyType, key, eval2)}) {
            $found = true;
          } else {
            $index++;
          }
        }

        if ($found) {
          ${ev.primitive} = ${ctx.getValue(eval1 + ".valueArray()", dataType, index)};
        } else {
          ${ev.isNull} = true;
        }
      """
    })
  }
}
