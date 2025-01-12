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

import java.text.DecimalFormat
import java.util.Arrays
import java.util.{Map => JMap, HashMap}
import java.util.Locale

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

////////////////////////////////////////////////////////////////////////////////////////////////////
// This file defines expressions for string operations.此文件定义字符串操作的表达式
////////////////////////////////////////////////////////////////////////////////////////////////////


/**
 * An expression that concatenates multiple input strings into a single string.
  * 将多个输入字符串连接成单个字符串的表达式
 * If any input is null, concat returns null.如果任何输入为null,则concat返回null
 */
case class Concat(children: Seq[Expression]) extends Expression with ImplicitCastInputTypes {

  override def inputTypes: Seq[AbstractDataType] = Seq.fill(children.size)(StringType)
  override def dataType: DataType = StringType

  override def nullable: Boolean = children.exists(_.nullable)
  override def foldable: Boolean = children.forall(_.foldable)

  override def eval(input: InternalRow): Any = {
    val inputs = children.map(_.eval(input).asInstanceOf[UTF8String])
    UTF8String.concat(inputs : _*)
  }

  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val evals = children.map(_.gen(ctx))
    val inputs = evals.map { eval =>
      s"${eval.isNull} ? null : ${eval.primitive}"
    }.mkString(", ")
    evals.map(_.code).mkString("\n") + s"""
      boolean ${ev.isNull} = false;
      UTF8String ${ev.primitive} = UTF8String.concat($inputs);
      if (${ev.primitive} == null) {
        ${ev.isNull} = true;
      }
    """
  }
}


/**
 * An expression that concatenates multiple input strings or array of strings into a single string,
 * using a given separator (the first child).
  * 使用给定分隔符(第一个子级)将多个输入字符串或字符串数组连接成单个字符串的表达式。
 *
 * Returns null if the separator is null. Otherwise, concat_ws skips all null values.
  * 如果分隔符为null,则返回null,否则,concat_ws会跳过所有空值
 */
case class ConcatWs(children: Seq[Expression])
  extends Expression with ImplicitCastInputTypes with CodegenFallback {

  require(children.nonEmpty, s"$prettyName requires at least one argument.")

  override def prettyName: String = "concat_ws"

  /** The 1st child (separator) is str, and rest are either str or array of str.
    * 第一个子（分隔符）是str，其余是str或str的数组*/
  override def inputTypes: Seq[AbstractDataType] = {
    val arrayOrStr = TypeCollection(ArrayType(StringType), StringType)
    StringType +: Seq.fill(children.size - 1)(arrayOrStr)
  }

  override def dataType: DataType = StringType

  override def nullable: Boolean = children.head.nullable
  override def foldable: Boolean = children.forall(_.foldable)

  override def eval(input: InternalRow): Any = {
    val flatInputs = children.flatMap { child =>
      child.eval(input) match {
        case s: UTF8String => Iterator(s)
        case arr: ArrayData => arr.toArray[UTF8String](StringType)
        case null => Iterator(null.asInstanceOf[UTF8String])
      }
    }
    UTF8String.concatWs(flatInputs.head, flatInputs.tail : _*)
  }

  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    if (children.forall(_.dataType == StringType)) {
      // All children are strings. In that case we can construct a fixed size array.
      val evals = children.map(_.gen(ctx))

      val inputs = evals.map { eval =>
        s"${eval.isNull} ? (UTF8String) null : ${eval.primitive}"
      }.mkString(", ")

      evals.map(_.code).mkString("\n") + s"""
        UTF8String ${ev.primitive} = UTF8String.concatWs($inputs);
        boolean ${ev.isNull} = ${ev.primitive} == null;
      """
    } else {
      // Contains a mix of strings and array<string>s. Fall back to interpreted mode for now.
      super.genCode(ctx, ev)
    }
  }
}

trait String2StringExpression extends ImplicitCastInputTypes {
  self: UnaryExpression =>

  def convert(v: UTF8String): UTF8String

  override def dataType: DataType = StringType
  override def inputTypes: Seq[DataType] = Seq(StringType)

  protected override def nullSafeEval(input: Any): Any =
    convert(input.asInstanceOf[UTF8String])
}

/**
 * A function that converts the characters of a string to uppercase.
  * 将字符串的字符转换为大写的函数
 */
@ExpressionDescription(
  usage = "_FUNC_(str) - Returns str with all characters changed to uppercase",
  extended = "> SELECT _FUNC_('SparkSql');\n 'SPARKSQL'")
case class Upper(child: Expression)
  extends UnaryExpression with String2StringExpression {

  override def convert(v: UTF8String): UTF8String = v.toUpperCase

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, c => s"($c).toUpperCase()")
  }
}

/**
 * A function that converts the characters of a string to lowercase.
  * 将字符串的字符转换为小写的函数
 */
@ExpressionDescription(
  usage = "_FUNC_(str) - Returns str with all characters changed to lowercase",
  extended = "> SELECT _FUNC_('SparkSql');\n'sparksql'")
case class Lower(child: Expression) extends UnaryExpression with String2StringExpression {

  override def convert(v: UTF8String): UTF8String = v.toLowerCase

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, c => s"($c).toLowerCase()")
  }
}

/** A base trait for functions that compare two strings, returning a boolean.
  * 比较两个字符串的函数的基本特征,返回一个布尔值*/
trait StringPredicate extends Predicate with ImplicitCastInputTypes {
  self: BinaryExpression =>

  def compare(l: UTF8String, r: UTF8String): Boolean

  override def inputTypes: Seq[DataType] = Seq(StringType, StringType)

  protected override def nullSafeEval(input1: Any, input2: Any): Any =
    compare(input1.asInstanceOf[UTF8String], input2.asInstanceOf[UTF8String])

  override def toString: String = s"$nodeName($left, $right)"
}

/**
 * A function that returns true if the string `left` contains the string `right`.
  * 如果字符串“left”包含字符串“right”,则返回true的函数
 */
case class Contains(left: Expression, right: Expression)
    extends BinaryExpression with StringPredicate {
  override def compare(l: UTF8String, r: UTF8String): Boolean = l.contains(r)
  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (c1, c2) => s"($c1).contains($c2)")
  }
}

/**
 * A function that returns true if the string `left` starts with the string `right`.
  * 如果字符串“left”以字符串“right”开头,则返回true的函数
 */
case class StartsWith(left: Expression, right: Expression)
    extends BinaryExpression with StringPredicate {
  override def compare(l: UTF8String, r: UTF8String): Boolean = l.startsWith(r)
  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (c1, c2) => s"($c1).startsWith($c2)")
  }
}

/**
 * A function that returns true if the string `left` ends with the string `right`.
  * 如果字符串“left”以字符串“right”结尾,则返回true的函数
 */
case class EndsWith(left: Expression, right: Expression)
    extends BinaryExpression with StringPredicate {
  override def compare(l: UTF8String, r: UTF8String): Boolean = l.endsWith(r)
  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (c1, c2) => s"($c1).endsWith($c2)")
  }
}

object StringTranslate {

  def buildDict(matchingString: UTF8String, replaceString: UTF8String)
    : JMap[Character, Character] = {
    val matching = matchingString.toString()
    val replace = replaceString.toString()
    val dict = new HashMap[Character, Character]()
    var i = 0
    while (i < matching.length()) {
      val rep = if (i < replace.length()) replace.charAt(i) else '\0'
      if (null == dict.get(matching.charAt(i))) {
        dict.put(matching.charAt(i), rep)
      }
      i += 1
    }
    dict
  }
}

/**
 * A function translate any character in the `srcExpr` by a character in `replaceExpr`.
 * The characters in `replaceExpr` is corresponding to the characters in `matchingExpr`.
 * The translate will happen when any character in the string matching with the character
 * in the `matchingExpr`.
  * 函数用`replaceExpr`中的字符翻译`srcExpr`中的任何字符,
  * `replaceExpr`中的字符对应于`matchingExpr`中的字符,
  * 当字符串中的任何字符与`matchingExpr`中的字符匹配时,将发生翻译。
 */
case class StringTranslate(srcExpr: Expression, matchingExpr: Expression, replaceExpr: Expression)
  extends TernaryExpression with ImplicitCastInputTypes {

  @transient private var lastMatching: UTF8String = _
  @transient private var lastReplace: UTF8String = _
  @transient private var dict: JMap[Character, Character] = _

  override def nullSafeEval(srcEval: Any, matchingEval: Any, replaceEval: Any): Any = {
    if (matchingEval != lastMatching || replaceEval != lastReplace) {
      lastMatching = matchingEval.asInstanceOf[UTF8String].clone()
      lastReplace = replaceEval.asInstanceOf[UTF8String].clone()
      dict = StringTranslate.buildDict(lastMatching, lastReplace)
    }
    srcEval.asInstanceOf[UTF8String].translate(dict)
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val termLastMatching = ctx.freshName("lastMatching")
    val termLastReplace = ctx.freshName("lastReplace")
    val termDict = ctx.freshName("dict")
    val classNameDict = classOf[JMap[Character, Character]].getCanonicalName

    ctx.addMutableState("UTF8String", termLastMatching, s"${termLastMatching} = null;")
    ctx.addMutableState("UTF8String", termLastReplace, s"${termLastReplace} = null;")
    ctx.addMutableState(classNameDict, termDict, s"${termDict} = null;")

    nullSafeCodeGen(ctx, ev, (src, matching, replace) => {
      val check = if (matchingExpr.foldable && replaceExpr.foldable) {
        s"${termDict} == null"
      } else {
        s"!${matching}.equals(${termLastMatching}) || !${replace}.equals(${termLastReplace})"
      }
      s"""if ($check) {
        // Not all of them is literal or matching or replace value changed
        ${termLastMatching} = ${matching}.clone();
        ${termLastReplace} = ${replace}.clone();
        ${termDict} = org.apache.spark.sql.catalyst.expressions.StringTranslate
          .buildDict(${termLastMatching}, ${termLastReplace});
      }
      ${ev.primitive} = ${src}.translate(${termDict});
      """
    })
  }

  override def dataType: DataType = StringType
  override def inputTypes: Seq[DataType] = Seq(StringType, StringType, StringType)
  override def children: Seq[Expression] = srcExpr :: matchingExpr :: replaceExpr :: Nil
  override def prettyName: String = "translate"
}

/**
 * A function that returns the index (1-based) of the given string (left) in the comma-
 * delimited list (right). Returns 0, if the string wasn't found or if the given
 * string (left) contains a comma.
  * 一个函数,用于返回逗号分隔列表(右)中给定字符串(左)的索引(从1开始),
  * 如果未找到字符串或给定字符串(左)包含逗号,则返回0。
 */
case class FindInSet(left: Expression, right: Expression) extends BinaryExpression
    with ImplicitCastInputTypes {

  override def inputTypes: Seq[AbstractDataType] = Seq(StringType, StringType)

  override protected def nullSafeEval(word: Any, set: Any): Any =
    set.asInstanceOf[UTF8String].findInSet(word.asInstanceOf[UTF8String])

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, (word, set) =>
      s"${ev.primitive} = $set.findInSet($word);"
    )
  }

  override def dataType: DataType = IntegerType
}

/**
 * A function that trim the spaces from both ends for the specified string.
  * 一个函数,用于修剪指定字符串两端的空格
 */
case class StringTrim(child: Expression)
  extends UnaryExpression with String2StringExpression {

  def convert(v: UTF8String): UTF8String = v.trim()

  override def prettyName: String = "trim"

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, c => s"($c).trim()")
  }
}

/**
 * A function that trim the spaces from left end for given string.
  * 对于给定字符串,从左端修剪空格的函数
 */
case class StringTrimLeft(child: Expression)
  extends UnaryExpression with String2StringExpression {

  def convert(v: UTF8String): UTF8String = v.trimLeft()

  override def prettyName: String = "ltrim"

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, c => s"($c).trimLeft()")
  }
}

/**
 * A function that trim the spaces from right end for given string.
  * 一个函数,用于修剪给定字符串的右端空格
 */
case class StringTrimRight(child: Expression)
  extends UnaryExpression with String2StringExpression {

  def convert(v: UTF8String): UTF8String = v.trimRight()

  override def prettyName: String = "rtrim"

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, c => s"($c).trimRight()")
  }
}

/**
 * A function that returns the position of the first occurrence of substr in the given string.
 * Returns null if either of the arguments are null and
 * returns 0 if substr could not be found in str.
  *
  * 一个函数,返回给定字符串中第一次出现substr的位置,如果任一参数为null,则返回null;如果在str中找不到substr,则返回0
 *
 * NOTE: that this is not zero based, but 1-based index. The first character in str has index 1.
  * 注意：这不是基于零的,而是基于1的索引, str中的第一个字符具有索引1。
 */
case class StringInstr(str: Expression, substr: Expression)
  extends BinaryExpression with ImplicitCastInputTypes {

  override def left: Expression = str
  override def right: Expression = substr
  override def dataType: DataType = IntegerType
  override def inputTypes: Seq[DataType] = Seq(StringType, StringType)

  override def nullSafeEval(string: Any, sub: Any): Any = {
    string.asInstanceOf[UTF8String].indexOf(sub.asInstanceOf[UTF8String], 0) + 1
  }

  override def prettyName: String = "instr"

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (l, r) =>
      s"($l).indexOf($r, 0) + 1")
  }
}

/**
 * Returns the substring from string str before count occurrences of the delimiter delim.
  * 在分隔符delim的计数出现之前,从字符串str返回子字符串
 * If count is positive, everything the left of the final delimiter (counting from left) is
 * returned. If count is negative, every to the right of the final delimiter (counting from the
 * right) is returned. substring_index performs a case-sensitive match when searching for delim.
  * 如果count为正数,则返回最终分隔符左边的所有内容(从左边开始计算),
  * 如果count为负数,则返回最终分隔符(从右侧开始)的右侧,substring_index在搜索delim时执行区分大小写的匹配。
 */
case class SubstringIndex(strExpr: Expression, delimExpr: Expression, countExpr: Expression)
 extends TernaryExpression with ImplicitCastInputTypes {

  override def dataType: DataType = StringType
  override def inputTypes: Seq[DataType] = Seq(StringType, StringType, IntegerType)
  override def children: Seq[Expression] = Seq(strExpr, delimExpr, countExpr)
  override def prettyName: String = "substring_index"

  override def nullSafeEval(str: Any, delim: Any, count: Any): Any = {
    str.asInstanceOf[UTF8String].subStringIndex(
      delim.asInstanceOf[UTF8String],
      count.asInstanceOf[Int])
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (str, delim, count) => s"$str.subStringIndex($delim, $count)")
  }
}

/**
 * A function that returns the position of the first occurrence of substr
 * in given string after position pos.
  * 一个函数,它返回位置pos之后给定字符串中第一次出现substr的位置
 */
case class StringLocate(substr: Expression, str: Expression, start: Expression)
  extends TernaryExpression with ImplicitCastInputTypes with CodegenFallback {

  def this(substr: Expression, str: Expression) = {
    this(substr, str, Literal(0))
  }

  override def children: Seq[Expression] = substr :: str :: start :: Nil
  override def dataType: DataType = IntegerType
  override def inputTypes: Seq[DataType] = Seq(StringType, StringType, IntegerType)

  override def eval(input: InternalRow): Any = {
    val s = start.eval(input)
    if (s == null) {
      // if the start position is null, we need to return 0, (conform to Hive)
      //如果起始位置为null,我们需要返回0(符合Hive)
      0
    } else {
      val r = substr.eval(input)
      if (r == null) {
        null
      } else {
        val l = str.eval(input)
        if (l == null) {
          null
        } else {
          l.asInstanceOf[UTF8String].indexOf(
            r.asInstanceOf[UTF8String],
            s.asInstanceOf[Int]) + 1
        }
      }
    }
  }

  override def prettyName: String = "locate"
}

/**
 * Returns str, left-padded with pad to a length of len.
  * 返回str,左边填充pad,长度为len,
 */
case class StringLPad(str: Expression, len: Expression, pad: Expression)
  extends TernaryExpression with ImplicitCastInputTypes {

  override def children: Seq[Expression] = str :: len :: pad :: Nil
  override def dataType: DataType = StringType
  override def inputTypes: Seq[DataType] = Seq(StringType, IntegerType, StringType)

  override def nullSafeEval(str: Any, len: Any, pad: Any): Any = {
    str.asInstanceOf[UTF8String].lpad(len.asInstanceOf[Int], pad.asInstanceOf[UTF8String])
  }

  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (str, len, pad) => s"$str.lpad($len, $pad)")
  }

  override def prettyName: String = "lpad"
}

/**
 * Returns str, right-padded with pad to a length of len.
  * 返回str,右边填充pad,长度为len
 */
case class StringRPad(str: Expression, len: Expression, pad: Expression)
  extends TernaryExpression with ImplicitCastInputTypes {

  override def children: Seq[Expression] = str :: len :: pad :: Nil
  override def dataType: DataType = StringType
  override def inputTypes: Seq[DataType] = Seq(StringType, IntegerType, StringType)

  override def nullSafeEval(str: Any, len: Any, pad: Any): Any = {
    str.asInstanceOf[UTF8String].rpad(len.asInstanceOf[Int], pad.asInstanceOf[UTF8String])
  }

  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (str, len, pad) => s"$str.rpad($len, $pad)")
  }

  override def prettyName: String = "rpad"
}

/**
 * Returns the input formatted according do printf-style format strings
  * 返回根据do printf样式格式字符串格式化的输入
 */
case class FormatString(children: Expression*) extends Expression with ImplicitCastInputTypes {

  require(children.nonEmpty, "format_string() should take at least 1 argument")

  override def foldable: Boolean = children.forall(_.foldable)
  override def nullable: Boolean = children(0).nullable
  override def dataType: DataType = StringType

  override def inputTypes: Seq[AbstractDataType] =
    StringType :: List.fill(children.size - 1)(AnyDataType)

  override def eval(input: InternalRow): Any = {
    val pattern = children(0).eval(input)
    if (pattern == null) {
      null
    } else {
      val sb = new StringBuffer()
      val formatter = new java.util.Formatter(sb, Locale.US)

      val arglist = children.tail.map(_.eval(input).asInstanceOf[AnyRef])
      formatter.format(pattern.asInstanceOf[UTF8String].toString, arglist: _*)

      UTF8String.fromString(sb.toString)
    }
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val pattern = children.head.gen(ctx)

    val argListGen = children.tail.map(x => (x.dataType, x.gen(ctx)))
    val argListCode = argListGen.map(_._2.code + "\n")

    val argListString = argListGen.foldLeft("")((s, v) => {
      val nullSafeString =
        if (ctx.boxedType(v._1) != ctx.javaType(v._1)) {
          // Java primitives get boxed in order to allow null values.
          s"(${v._2.isNull}) ? (${ctx.boxedType(v._1)}) null : " +
            s"new ${ctx.boxedType(v._1)}(${v._2.primitive})"
        } else {
          s"(${v._2.isNull}) ? null : ${v._2.primitive}"
        }
      s + "," + nullSafeString
    })

    val form = ctx.freshName("formatter")
    val formatter = classOf[java.util.Formatter].getName
    val sb = ctx.freshName("sb")
    val stringBuffer = classOf[StringBuffer].getName
    s"""
      ${pattern.code}
      boolean ${ev.isNull} = ${pattern.isNull};
      ${ctx.javaType(dataType)} ${ev.primitive} = ${ctx.defaultValue(dataType)};
      if (!${ev.isNull}) {
        ${argListCode.mkString}
        $stringBuffer $sb = new $stringBuffer();
        $formatter $form = new $formatter($sb, ${classOf[Locale].getName}.US);
        $form.format(${pattern.primitive}.toString() $argListString);
        ${ev.primitive} = UTF8String.fromString($sb.toString());
      }
     """
  }

  override def prettyName: String = "format_string"
}

/**
 * Returns string, with the first letter of each word in uppercase.
  * 返回字符串,每个单词的第一个字母为大写,单词由空格分隔
 * Words are delimited by whitespace.
 */
case class InitCap(child: Expression) extends UnaryExpression with ImplicitCastInputTypes {

  override def inputTypes: Seq[DataType] = Seq(StringType)
  override def dataType: DataType = StringType

  override def nullSafeEval(string: Any): Any = {
    string.asInstanceOf[UTF8String].toTitleCase
  }
  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, str => s"$str.toTitleCase()")
  }
}

/**
 * Returns the string which repeat the given string value n times.
  * 返回重复给定字符串值n次的字符串
 */
case class StringRepeat(str: Expression, times: Expression)
  extends BinaryExpression with ImplicitCastInputTypes {

  override def left: Expression = str
  override def right: Expression = times
  override def dataType: DataType = StringType
  override def inputTypes: Seq[DataType] = Seq(StringType, IntegerType)

  override def nullSafeEval(string: Any, n: Any): Any = {
    string.asInstanceOf[UTF8String].repeat(n.asInstanceOf[Integer])
  }

  override def prettyName: String = "repeat"

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (l, r) => s"($l).repeat($r)")
  }
}

/**
 * Returns the reversed given string.
  * 返回反转的给定字符串
 */
case class StringReverse(child: Expression) extends UnaryExpression with String2StringExpression {
  override def convert(v: UTF8String): UTF8String = v.reverse()

  override def prettyName: String = "reverse"

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, c => s"($c).reverse()")
  }
}

/**
 * Returns a n spaces string.
  * 返回n个空格字符串
 */
case class StringSpace(child: Expression)
  extends UnaryExpression with ImplicitCastInputTypes {

  override def dataType: DataType = StringType
  override def inputTypes: Seq[DataType] = Seq(IntegerType)

  override def nullSafeEval(s: Any): Any = {
    val length = s.asInstanceOf[Int]
    UTF8String.blankString(if (length < 0) 0 else length)
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, (length) =>
      s"""${ev.primitive} = UTF8String.blankString(($length < 0) ? 0 : $length);""")
  }

  override def prettyName: String = "space"
}

object Substring {
  def subStringBinarySQL(bytes: Array[Byte], pos: Int, len: Int): Array[Byte] = {
    if (pos > bytes.length) {
      return Array[Byte]()
    }

    var start = if (pos > 0) {
      pos - 1
    } else if (pos < 0) {
      bytes.length + pos
    } else {
      0
    }

    val end = if ((bytes.length - start) < len) {
      bytes.length
    } else {
      start + len
    }

    start = Math.max(start, 0)  // underflow
    if (start < end) {
      Arrays.copyOfRange(bytes, start, end)
    } else {
      Array[Byte]()
    }
  }
}
/**
 * A function that takes a substring of its first argument starting at a given position.
 * Defined for String and Binary types.
  * 一个函数,它接受从给定位置开始的第一个参数的子字符串,为String和Binary类型定义
 */
case class Substring(str: Expression, pos: Expression, len: Expression)
  extends TernaryExpression with ImplicitCastInputTypes {

  def this(str: Expression, pos: Expression) = {
    this(str, pos, Literal(Integer.MAX_VALUE))
  }

  override def dataType: DataType = str.dataType

  override def inputTypes: Seq[AbstractDataType] =
    Seq(TypeCollection(StringType, BinaryType), IntegerType, IntegerType)

  override def children: Seq[Expression] = str :: pos :: len :: Nil

  override def nullSafeEval(string: Any, pos: Any, len: Any): Any = {
    str.dataType match {
      case StringType => string.asInstanceOf[UTF8String]
        .substringSQL(pos.asInstanceOf[Int], len.asInstanceOf[Int])
      case BinaryType => Substring.subStringBinarySQL(string.asInstanceOf[Array[Byte]],
        pos.asInstanceOf[Int], len.asInstanceOf[Int])
    }
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {

    val cls = classOf[Substring].getName
    defineCodeGen(ctx, ev, (string, pos, len) => {
      str.dataType match {
        case StringType => s"$string.substringSQL($pos, $len)"
        case BinaryType => s"$cls.subStringBinarySQL($string, $pos, $len)"
      }
    })
  }
}

/**
 * A function that return the length of the given string or binary expression.
  * 返回给定字符串或二进制表达式长度的函数
 */
case class Length(child: Expression) extends UnaryExpression with ExpectsInputTypes {
  override def dataType: DataType = IntegerType
  override def inputTypes: Seq[AbstractDataType] = Seq(TypeCollection(StringType, BinaryType))

  protected override def nullSafeEval(value: Any): Any = child.dataType match {
    case StringType => value.asInstanceOf[UTF8String].numChars
    case BinaryType => value.asInstanceOf[Array[Byte]].length
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    child.dataType match {
      case StringType => defineCodeGen(ctx, ev, c => s"($c).numChars()")
      case BinaryType => defineCodeGen(ctx, ev, c => s"($c).length")
    }
  }
}

/**
 * A function that return the Levenshtein distance between the two given strings.
  * 返回两个给定字符串之间的Levenshtein距离的函数
 */
case class Levenshtein(left: Expression, right: Expression) extends BinaryExpression
    with ImplicitCastInputTypes {

  override def inputTypes: Seq[AbstractDataType] = Seq(StringType, StringType)

  override def dataType: DataType = IntegerType
  protected override def nullSafeEval(leftValue: Any, rightValue: Any): Any =
    leftValue.asInstanceOf[UTF8String].levenshteinDistance(rightValue.asInstanceOf[UTF8String])

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, (left, right) =>
      s"${ev.primitive} = $left.levenshteinDistance($right);")
  }
}

/**
 * A function that return soundex code of the given string expression.
  * 返回给定字符串表达式的soundex代码的函数
 */
case class SoundEx(child: Expression) extends UnaryExpression with ExpectsInputTypes {

  override def dataType: DataType = StringType

  override def inputTypes: Seq[DataType] = Seq(StringType)

  override def nullSafeEval(input: Any): Any = input.asInstanceOf[UTF8String].soundex()

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, c => s"$c.soundex()")
  }
}

/**
 * Returns the numeric value of the first character of str.
  * 返回str的第一个字符的数值
 */
case class Ascii(child: Expression) extends UnaryExpression with ImplicitCastInputTypes {

  override def dataType: DataType = IntegerType
  override def inputTypes: Seq[DataType] = Seq(StringType)

  protected override def nullSafeEval(string: Any): Any = {
    val bytes = string.asInstanceOf[UTF8String].getBytes
    if (bytes.length > 0) {
      bytes(0).asInstanceOf[Int]
    } else {
      0
    }
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, (child) => {
      val bytes = ctx.freshName("bytes")
      s"""
        byte[] $bytes = $child.getBytes();
        if ($bytes.length > 0) {
          ${ev.primitive} = (int) $bytes[0];
        } else {
          ${ev.primitive} = 0;
        }
       """})
  }
}

/**
 * Converts the argument from binary to a base 64 string.
  * 将参数从二进制转换为基本64字符串
 */
case class Base64(child: Expression) extends UnaryExpression with ImplicitCastInputTypes {

  override def dataType: DataType = StringType
  override def inputTypes: Seq[DataType] = Seq(BinaryType)

  protected override def nullSafeEval(bytes: Any): Any = {
    UTF8String.fromBytes(
      org.apache.commons.codec.binary.Base64.encodeBase64(
        bytes.asInstanceOf[Array[Byte]]))
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, (child) => {
      s"""${ev.primitive} = UTF8String.fromBytes(
            org.apache.commons.codec.binary.Base64.encodeBase64($child));
       """})
  }

}

/**
 * Converts the argument from a base 64 string to BINARY.
  * 将参数从base 64字符串转换为BINARY
 */
case class UnBase64(child: Expression) extends UnaryExpression with ImplicitCastInputTypes {

  override def dataType: DataType = BinaryType
  override def inputTypes: Seq[DataType] = Seq(StringType)

  protected override def nullSafeEval(string: Any): Any =
    org.apache.commons.codec.binary.Base64.decodeBase64(string.asInstanceOf[UTF8String].toString)

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, (child) => {
      s"""
         ${ev.primitive} = org.apache.commons.codec.binary.Base64.decodeBase64($child.toString());
       """})
  }
}

/**
 * Decodes the first argument into a String using the provided character set
 * (one of 'US-ASCII', 'ISO-8859-1', 'UTF-8', 'UTF-16BE', 'UTF-16LE', 'UTF-16').
 * If either argument is null, the result will also be null.
  * 使用提供的字符集('US-ASCII'，'ISO-8859-1'，'UTF-8'，'UTF-16BE'，'UTF-16LE'，'UTF-)
  * 将第一个参数解码为String 16'),如果任一参数为null，结果也将为null。
 */
case class Decode(bin: Expression, charset: Expression)
  extends BinaryExpression with ImplicitCastInputTypes {

  override def left: Expression = bin
  override def right: Expression = charset
  override def dataType: DataType = StringType
  override def inputTypes: Seq[DataType] = Seq(BinaryType, StringType)

  protected override def nullSafeEval(input1: Any, input2: Any): Any = {
    val fromCharset = input2.asInstanceOf[UTF8String].toString
    UTF8String.fromString(new String(input1.asInstanceOf[Array[Byte]], fromCharset))
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, (bytes, charset) =>
      s"""
        try {
          ${ev.primitive} = UTF8String.fromString(new String($bytes, $charset.toString()));
        } catch (java.io.UnsupportedEncodingException e) {
          org.apache.spark.unsafe.Platform.throwException(e);
        }
      """)
  }
}

/**
 * Encodes the first argument into a BINARY using the provided character set
 * (one of 'US-ASCII', 'ISO-8859-1', 'UTF-8', 'UTF-16BE', 'UTF-16LE', 'UTF-16').
 * If either argument is null, the result will also be null.
*/
case class Encode(value: Expression, charset: Expression)
  extends BinaryExpression with ImplicitCastInputTypes {

  override def left: Expression = value
  override def right: Expression = charset
  override def dataType: DataType = BinaryType
  override def inputTypes: Seq[DataType] = Seq(StringType, StringType)

  protected override def nullSafeEval(input1: Any, input2: Any): Any = {
    val toCharset = input2.asInstanceOf[UTF8String].toString
    input1.asInstanceOf[UTF8String].toString.getBytes(toCharset)
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, (string, charset) =>
      s"""
        try {
          ${ev.primitive} = $string.toString().getBytes($charset.toString());
        } catch (java.io.UnsupportedEncodingException e) {
          org.apache.spark.unsafe.Platform.throwException(e);
        }""")
  }
}

/**
 * Formats the number X to a format like '#,###,###.##', rounded to D decimal places,
 * and returns the result as a string. If D is 0, the result has no decimal point or
 * fractional part.
  * 将数字X格式化为'＃，###，###。##'等格式,舍入到D小数位,并将结果作为字符串返回,
  * 如果D为0,则结果没有小数点或小数部分。
 */
case class FormatNumber(x: Expression, d: Expression)
  extends BinaryExpression with ExpectsInputTypes {

  override def left: Expression = x
  override def right: Expression = d
  override def dataType: DataType = StringType
  override def inputTypes: Seq[AbstractDataType] = Seq(NumericType, IntegerType)

  // Associated with the pattern, for the last d value, and we will update the
  // pattern (DecimalFormat) once the new coming d value differ with the last one.
  //与模式相关联,对于最后一个d值,一旦新的d值与最后一个值不同,我们将更新模式(DecimalFormat)
  @transient
  private var lastDValue: Int = -100

  // A cached DecimalFormat, for performance concern, we will change it
  // only if the d value changed.
  //缓存的DecimalFormat,出于性能考虑,我们只有在d值发生变化时才会更改它
  @transient
  private val pattern: StringBuffer = new StringBuffer()

  @transient
  private val numberFormat: DecimalFormat = new DecimalFormat("")

  override protected def nullSafeEval(xObject: Any, dObject: Any): Any = {
    val dValue = dObject.asInstanceOf[Int]
    if (dValue < 0) {
      return null
    }

    if (dValue != lastDValue) {
      // construct a new DecimalFormat only if a new dValue
      //只有在新的dValue时才构造一个新的DecimalFormat
      pattern.delete(0, pattern.length)
      pattern.append("#,###,###,###,###,###,##0")

      // decimal place
      if (dValue > 0) {
        pattern.append(".")

        var i = 0
        while (i < dValue) {
          i += 1
          pattern.append("0")
        }
      }
      val dFormat = new DecimalFormat(pattern.toString)
      lastDValue = dValue

      numberFormat.applyPattern(dFormat.toPattern)
    }

    x.dataType match {
      case ByteType => UTF8String.fromString(numberFormat.format(xObject.asInstanceOf[Byte]))
      case ShortType => UTF8String.fromString(numberFormat.format(xObject.asInstanceOf[Short]))
      case FloatType => UTF8String.fromString(numberFormat.format(xObject.asInstanceOf[Float]))
      case IntegerType => UTF8String.fromString(numberFormat.format(xObject.asInstanceOf[Int]))
      case LongType => UTF8String.fromString(numberFormat.format(xObject.asInstanceOf[Long]))
      case DoubleType => UTF8String.fromString(numberFormat.format(xObject.asInstanceOf[Double]))
      case _: DecimalType =>
        UTF8String.fromString(numberFormat.format(xObject.asInstanceOf[Decimal].toJavaBigDecimal))
    }
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, (num, d) => {

      def typeHelper(p: String): String = {
        x.dataType match {
          case _ : DecimalType => s"""$p.toJavaBigDecimal()"""
          case _ => s"$p"
        }
      }

      val sb = classOf[StringBuffer].getName
      val df = classOf[DecimalFormat].getName
      val lastDValue = ctx.freshName("lastDValue")
      val pattern = ctx.freshName("pattern")
      val numberFormat = ctx.freshName("numberFormat")
      val i = ctx.freshName("i")
      val dFormat = ctx.freshName("dFormat")
      ctx.addMutableState("int", lastDValue, s"$lastDValue = -100;")
      ctx.addMutableState(sb, pattern, s"$pattern = new $sb();")
      ctx.addMutableState(df, numberFormat, s"""$numberFormat = new $df("");""")

      s"""
        if ($d >= 0) {
          $pattern.delete(0, $pattern.length());
          if ($d != $lastDValue) {
            $pattern.append("#,###,###,###,###,###,##0");

            if ($d > 0) {
              $pattern.append(".");
              for (int $i = 0; $i < $d; $i++) {
                $pattern.append("0");
              }
            }
            $df $dFormat = new $df($pattern.toString());
            $lastDValue = $d;
            $numberFormat.applyPattern($dFormat.toPattern());
          }
          ${ev.primitive} = UTF8String.fromString($numberFormat.format(${typeHelper(num)}));
        } else {
          ${ev.primitive} = null;
          ${ev.isNull} = true;
        }
       """
    })
  }

  override def prettyName: String = "format_number"
}
