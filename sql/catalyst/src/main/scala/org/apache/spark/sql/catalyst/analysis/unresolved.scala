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

package org.apache.spark.sql.catalyst.analysis

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.catalyst.errors
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.LeafNode
import org.apache.spark.sql.catalyst.trees.TreeNode
import org.apache.spark.sql.types.DataType

/**
 * Thrown when an invalid attempt is made to access a property of a tree that has yet to be fully
 * resolved.
  * 当无效尝试访问尚未完全解析的树的属性时抛出
 */
class UnresolvedException[TreeType <: TreeNode[_]](tree: TreeType, function: String) extends
  errors.TreeNodeException(tree, s"Invalid call to $function on unresolved object", null)

/**
 * Holds the name of a relation that has yet to be looked up in a [[Catalog]].
  * 保存尚未在[[Catalog]]中查找的关系的名称
 */
case class UnresolvedRelation(
    tableIdentifier: Seq[String],
    alias: Option[String] = None) extends LeafNode {

  /** Returns a `.` separated name for this relation. 返回此关系的`.`分隔名称*/
  def tableName: String = tableIdentifier.mkString(".")

  override def output: Seq[Attribute] = Nil

  override lazy val resolved = false
}

/**
 * Holds the name of an attribute that has yet to be resolved.
  * 保存尚未解析的属性的名称
 */
case class UnresolvedAttribute(nameParts: Seq[String]) extends Attribute with Unevaluable {

  def name: String =
    nameParts.map(n => if (n.contains(".")) s"`$n`" else n).mkString(".")

  override def exprId: ExprId = throw new UnresolvedException(this, "exprId")
  override def dataType: DataType = throw new UnresolvedException(this, "dataType")
  override def nullable: Boolean = throw new UnresolvedException(this, "nullable")
  override def qualifiers: Seq[String] = throw new UnresolvedException(this, "qualifiers")
  override lazy val resolved = false

  override def newInstance(): UnresolvedAttribute = this
  override def withNullability(newNullability: Boolean): UnresolvedAttribute = this
  override def withQualifiers(newQualifiers: Seq[String]): UnresolvedAttribute = this
  override def withName(newName: String): UnresolvedAttribute = UnresolvedAttribute.quoted(newName)

  override def toString: String = s"'$name"
}

object UnresolvedAttribute {
  /**
   * Creates an [[UnresolvedAttribute]], parsing segments separated by dots ('.').
    * 创建[[UnresolvedAttribute]],解析以点（'.'）分隔的段
   */
  def apply(name: String): UnresolvedAttribute = new UnresolvedAttribute(name.split("\\."))

  /**
   * Creates an [[UnresolvedAttribute]], from a single quoted string (for example using backticks in
   * HiveQL.  Since the string is consider quoted, no processing is done on the name.
    * 从单引号字符串创建[[UnresolvedAttribute]]
    * 例如在HiveQL中使用反引号,由于字符串被引用,因此不对名称进行处理
   */
  def quoted(name: String): UnresolvedAttribute = new UnresolvedAttribute(Seq(name))

  /**
   * Creates an [[UnresolvedAttribute]] from a string in an embedded language.  In this case
   * we treat it as a quoted identifier, except for '.', which must be further quoted using
   * backticks if it is part of a column name.
   */
  def quotedString(name: String): UnresolvedAttribute =
    new UnresolvedAttribute(parseAttributeName(name))

  /**
   * Used to split attribute name by dot with backticks rule.用于通过带反引号规则的点分割属性名称
   * Backticks must appear in pairs, and the quoted string must be a complete name part,
   * which means `ab..c`e.f is not allowed.
   * Escape character is not supported now, so we can't use backtick inside name part.
    * 反引号必须成对出现,引用的字符串必须是完整的名称部分,
    * 这意味着不允许使用`ab..c`e.f,这意味着不允许使用`ab..c`e.f
   */
  def parseAttributeName(name: String): Seq[String] = {
    def e = new AnalysisException(s"syntax error in attribute name: $name")
    val nameParts = scala.collection.mutable.ArrayBuffer.empty[String]
    val tmp = scala.collection.mutable.ArrayBuffer.empty[Char]
    var inBacktick = false
    var i = 0
    while (i < name.length) {
      val char = name(i)
      if (inBacktick) {
        if (char == '`') {
          inBacktick = false
          if (i + 1 < name.length && name(i + 1) != '.') throw e
        } else {
          tmp += char
        }
      } else {
        if (char == '`') {
          if (tmp.nonEmpty) throw e
          inBacktick = true
        } else if (char == '.') {
          if (name(i - 1) == '.' || i == name.length - 1) throw e
          nameParts += tmp.mkString
          tmp.clear()
        } else {
          tmp += char
        }
      }
      i += 1
    }
    if (inBacktick) throw e
    nameParts += tmp.mkString
    nameParts.toSeq
  }
}

case class UnresolvedFunction(
    name: String,
    children: Seq[Expression],
    isDistinct: Boolean)
  extends Expression with Unevaluable {

  override def dataType: DataType = throw new UnresolvedException(this, "dataType")
  override def foldable: Boolean = throw new UnresolvedException(this, "foldable")
  override def nullable: Boolean = throw new UnresolvedException(this, "nullable")
  override lazy val resolved = false

  override def toString: String = s"'$name(${children.mkString(",")})"
}

/**
 * Represents all of the input attributes to a given relational operator, for example in
 * "SELECT * FROM ...". A [[Star]] gets automatically expanded during analysis.
 */
abstract class Star extends LeafExpression with NamedExpression {

  override def name: String = throw new UnresolvedException(this, "name")
  override def exprId: ExprId = throw new UnresolvedException(this, "exprId")
  override def dataType: DataType = throw new UnresolvedException(this, "dataType")
  override def nullable: Boolean = throw new UnresolvedException(this, "nullable")
  override def qualifiers: Seq[String] = throw new UnresolvedException(this, "qualifiers")
  override def toAttribute: Attribute = throw new UnresolvedException(this, "toAttribute")
  override lazy val resolved = false

  def expand(input: Seq[Attribute], resolver: Resolver): Seq[NamedExpression]
}


/**
 * Represents all of the input attributes to a given relational operator, for example in
  * 表示给定关系运算符的所有输入属性,例如
 * "SELECT * FROM ...".
 *
 * @param table an optional table that should be the target of the expansion.  If omitted all
 *              tables' columns are produced.
 */
case class UnresolvedStar(table: Option[String]) extends Star with Unevaluable {

  override def expand(input: Seq[Attribute], resolver: Resolver): Seq[NamedExpression] = {
    val expandedAttributes: Seq[Attribute] = table match {
      // If there is no table specified, use all input attributes.
      case None => input
      // If there is a table, pick out attributes that are part of this table.
      case Some(t) => input.filter(_.qualifiers.filter(resolver(_, t)).nonEmpty)
    }
    expandedAttributes.zip(input).map {
      case (n: NamedExpression, _) => n
      case (e, originalAttribute) =>
        Alias(e, originalAttribute.name)(qualifiers = originalAttribute.qualifiers)
    }
  }

  override def toString: String = table.map(_ + ".").getOrElse("") + "*"
}

/**
 * Used to assign new names to Generator's output, such as hive udtf.
  * 用于为Generator的输出分配新名称,例如hive udtf
 * For example the SQL expression "stack(2, key, value, key, value) as (a, b)" could be represented
 * as follows:
 *  MultiAlias(stack_function, Seq(a, b))
 * 例如,SQL表达式“stack（2，key，value，key，value）as（a，b）”可以表示如下：MultiAlias（stack_function，Seq（a，b））
 * @param child the computation being performed
 * @param names the names to be associated with each output of computing [[child]].
 */
case class MultiAlias(child: Expression, names: Seq[String])
  extends UnaryExpression with NamedExpression with CodegenFallback {

  override def name: String = throw new UnresolvedException(this, "name")

  override def exprId: ExprId = throw new UnresolvedException(this, "exprId")

  override def dataType: DataType = throw new UnresolvedException(this, "dataType")

  override def nullable: Boolean = throw new UnresolvedException(this, "nullable")

  override def qualifiers: Seq[String] = throw new UnresolvedException(this, "qualifiers")

  override def toAttribute: Attribute = throw new UnresolvedException(this, "toAttribute")

  override lazy val resolved = false

  override def toString: String = s"$child AS $names"

}

/**
 * Represents all the resolved input attributes to a given relational operator. This is used
 * in the data frame DSL.
 *
 * @param expressions Expressions to expand.
 */
case class ResolvedStar(expressions: Seq[NamedExpression]) extends Star with Unevaluable {
  override def expand(input: Seq[Attribute], resolver: Resolver): Seq[NamedExpression] = expressions
  override def toString: String = expressions.mkString("ResolvedStar(", ", ", ")")
}

/**
 * Extracts a value or values from an Expression
 *
 * @param child The expression to extract value from,
 *              can be Map, Array, Struct or array of Structs.
 * @param extraction The expression to describe the extraction,
 *                   can be key of Map, index of Array, field name of Struct.
 */
case class UnresolvedExtractValue(child: Expression, extraction: Expression)
  extends UnaryExpression with Unevaluable {

  override def dataType: DataType = throw new UnresolvedException(this, "dataType")
  override def foldable: Boolean = throw new UnresolvedException(this, "foldable")
  override def nullable: Boolean = throw new UnresolvedException(this, "nullable")
  override lazy val resolved = false

  override def toString: String = s"$child[$extraction]"
}

/**
 * Holds the expression that has yet to be aliased.
  * 保存尚未别名的表达式
 */
case class UnresolvedAlias(child: Expression)
  extends UnaryExpression with NamedExpression with Unevaluable {

  override def toAttribute: Attribute = throw new UnresolvedException(this, "toAttribute")
  override def qualifiers: Seq[String] = throw new UnresolvedException(this, "qualifiers")
  override def exprId: ExprId = throw new UnresolvedException(this, "exprId")
  override def nullable: Boolean = throw new UnresolvedException(this, "nullable")
  override def dataType: DataType = throw new UnresolvedException(this, "dataType")
  override def name: String = throw new UnresolvedException(this, "name")

  override lazy val resolved = false
}
