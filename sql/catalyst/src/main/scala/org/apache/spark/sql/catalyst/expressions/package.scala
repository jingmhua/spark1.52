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

/**
 * A set of classes that can be used to represent trees of relational expressions.  A key goal of
 * the expression library is to hide the details of naming and scoping from developers who want to
 * manipulate trees of relational operators. As such, the library defines a special type of
 * expression, a [[NamedExpression]] in addition to the standard collection of expressions.
  *
  * 一组可用于表示关系表达式树的类,表达式库的一个关键目标是隐藏想要操纵关系运算符树的开发人员的命名和作用域的细节。 因此，除了标准的表达式集合之外，库还定义了一种特殊类型的表达式，[[NamedExpression]]。
 *
 * ==Standard Expressions==
 * A library of standard expressions (e.g., [[Add]], [[EqualTo]]), aggregates (e.g., SUM, COUNT),
 * and other computations (e.g. UDFs). Each expression type is capable of determining its output
 * schema as a function of its children's output schema.
 *
 * ==Named Expressions==
 * Some expression are named and thus can be referenced by later operators in the dataflow graph.
 * The two types of named expressions are [[AttributeReference]]s and [[Alias]]es.
 * [[AttributeReference]]s refer to attributes of the input tuple for a given operator and form
 * the leaves of some expression trees.  Aliases assign a name to intermediate computations.
 * For example, in the SQL statement `SELECT a+b AS c FROM ...`, the expressions `a` and `b` would
 * be represented by `AttributeReferences` and `c` would be represented by an `Alias`.
 *
 * During [[analysis]], all named expressions are assigned a globally unique expression id, which
 * can be used for equality comparisons.  While the original names are kept around for debugging
 * purposes, they should never be used to check if two attributes refer to the same value, as
 * plan transformations can result in the introduction of naming ambiguity. For example, consider
 * a plan that contains subqueries, both of which are reading from the same table.  If an
 * optimization removes the subqueries, scoping information would be destroyed, eliminating the
 * ability to reason about which subquery produced a given attribute.
 *
 * ==Evaluation==
 * The result of expressions can be evaluated using the `Expression.apply(Row)` method.
  * 可以使用`Expression.apply（Row）`方法计算表达式的结果
 */
package object expressions  {

  /**
   * Used as input into expressions whose output does not depend on any input value.
    * 用作输出到输出不依赖于任何输入值的表达式
   */
  val EmptyRow: InternalRow = null

  /**
   * Converts a [[InternalRow]] to another Row given a sequence of expression that define each
   * column of the new row. If the schema of the input row is specified, then the given expression
   * will be bound to that schema.
    * 给定一个表达式序列,将[[InternalRow]]转换为另一行,该表达式定义新行的每一列,
    * 如果指定了输入行的模式,则给定的表达式将绑定到该模式。
   */
  abstract class Projection extends (InternalRow => InternalRow)

  /**
   * Converts a [[InternalRow]] to another Row given a sequence of expression that define each
   * column of the new row. If the schema of the input row is specified, then the given expression
   * will be bound to that schema.
    *
    * 给定一个表达式序列,将[[InternalRow]]转换为另一行,该表达式定义新行的每一列,
    * 如果指定了输入行的模式,则给定的表达式将绑定到该模式。
   *
   * In contrast to a normal projection, a MutableProjection reuses the same underlying row object
   * each time an input row is added.  This significantly reduces the cost of calculating the
   * projection, but means that it is not safe to hold on to a reference to a [[InternalRow]] after
   * `next()` has been called on the [[Iterator]] that produced it. Instead, the user must call
   * `InternalRow.copy()` and hold on to the returned [[InternalRow]] before calling `next()`.
   */
  abstract class MutableProjection extends Projection {
    def currentValue: InternalRow

    /** Uses the given row to store the output of the projection.
      * 使用给定的行存储投影的输出*/
    def target(row: MutableRow): MutableProjection
  }
}
