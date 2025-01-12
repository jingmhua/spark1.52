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

import java.util.regex.{MatchResult, Pattern}

import org.apache.commons.lang3.StringEscapeUtils

import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.util.StringUtils
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String


trait StringRegexExpression extends ImplicitCastInputTypes {
  self: BinaryExpression =>

  def escape(v: String): String
  def matches(regex: Pattern, str: String): Boolean

  override def dataType: DataType = BooleanType
  override def inputTypes: Seq[DataType] = Seq(StringType, StringType)

  // try cache the pattern for Literal 尝试缓存Literal的模式
  private lazy val cache: Pattern = right match {
    case x @ Literal(value: String, StringType) => compile(value)
    case _ => null
  }

  protected def compile(str: String): Pattern = if (str == null) {
    null
  } else {
    // Let it raise exception if couldn't compile the regex string
    //如果无法编译正则表达式字符串,请引发异常
    Pattern.compile(escape(str))
  }

  protected def pattern(str: String) = if (cache == null) compile(str) else cache

  protected override def nullSafeEval(input1: Any, input2: Any): Any = {
    val regex = pattern(input2.asInstanceOf[UTF8String].toString)
    if(regex == null) {
      null
    } else {
      matches(regex, input1.asInstanceOf[UTF8String].toString)
    }
  }
}


/**
 * Simple RegEx pattern matching function
  * 简单的RegEx模式匹配功能
 */
case class Like(left: Expression, right: Expression)
  extends BinaryExpression with StringRegexExpression with CodegenFallback {

  override def escape(v: String): String = StringUtils.escapeLikeRegex(v)

  override def matches(regex: Pattern, str: String): Boolean = regex.matcher(str).matches()

  override def toString: String = s"$left LIKE $right"

  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val patternClass = classOf[Pattern].getName
    //stripSuffix去掉<string>字串中结尾的字符
    val escapeFunc = StringUtils.getClass.getName.stripSuffix("$") + ".escapeLikeRegex"
    val pattern = ctx.freshName("pattern")

    if (right.foldable) {
      val rVal = right.eval()
      if (rVal != null) {
        val regexStr =
          StringEscapeUtils.escapeJava(escape(rVal.asInstanceOf[UTF8String].toString()))
        ctx.addMutableState(patternClass, pattern,
          s"""$pattern = ${patternClass}.compile("$regexStr");""")

        // We don't use nullSafeCodeGen here because we don't want to re-evaluate right again.
        val eval = left.gen(ctx)
        s"""
          ${eval.code}
          boolean ${ev.isNull} = ${eval.isNull};
          ${ctx.javaType(dataType)} ${ev.primitive} = ${ctx.defaultValue(dataType)};
          if (!${ev.isNull}) {
            ${ev.primitive} = $pattern.matcher(${eval.primitive}.toString()).matches();
          }
        """
      } else {
        s"""
          boolean ${ev.isNull} = true;
          ${ctx.javaType(dataType)} ${ev.primitive} = ${ctx.defaultValue(dataType)};
        """
      }
    } else {
      nullSafeCodeGen(ctx, ev, (eval1, eval2) => {
        s"""
          String rightStr = ${eval2}.toString();
          ${patternClass} $pattern = ${patternClass}.compile($escapeFunc(rightStr));
          ${ev.primitive} = $pattern.matcher(${eval1}.toString()).matches();
        """
      })
    }
  }
}


case class RLike(left: Expression, right: Expression)
  extends BinaryExpression with StringRegexExpression with CodegenFallback {

  override def escape(v: String): String = v
  override def matches(regex: Pattern, str: String): Boolean = regex.matcher(str).find(0)
  override def toString: String = s"$left RLIKE $right"

  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val patternClass = classOf[Pattern].getName
    val pattern = ctx.freshName("pattern")

    if (right.foldable) {
      val rVal = right.eval()
      if (rVal != null) {
        val regexStr =
          StringEscapeUtils.escapeJava(rVal.asInstanceOf[UTF8String].toString())
        ctx.addMutableState(patternClass, pattern,
          s"""$pattern = ${patternClass}.compile("$regexStr");""")

        // We don't use nullSafeCodeGen here because we don't want to re-evaluate right again.
        val eval = left.gen(ctx)
        s"""
          ${eval.code}
          boolean ${ev.isNull} = ${eval.isNull};
          ${ctx.javaType(dataType)} ${ev.primitive} = ${ctx.defaultValue(dataType)};
          if (!${ev.isNull}) {
            ${ev.primitive} = $pattern.matcher(${eval.primitive}.toString()).find(0);
          }
        """
      } else {
        s"""
          boolean ${ev.isNull} = true;
          ${ctx.javaType(dataType)} ${ev.primitive} = ${ctx.defaultValue(dataType)};
        """
      }
    } else {
      nullSafeCodeGen(ctx, ev, (eval1, eval2) => {
        s"""
          String rightStr = ${eval2}.toString();
          ${patternClass} $pattern = ${patternClass}.compile(rightStr);
          ${ev.primitive} = $pattern.matcher(${eval1}.toString()).find(0);
        """
      })
    }
  }
}


/**
 * Splits str around pat (pattern is a regular expression).
  * plits str around pat(pattern是一个正则表达式)
 */
case class StringSplit(str: Expression, pattern: Expression)
  extends BinaryExpression with ImplicitCastInputTypes {

  override def left: Expression = str
  override def right: Expression = pattern
  override def dataType: DataType = ArrayType(StringType)
  override def inputTypes: Seq[DataType] = Seq(StringType, StringType)

  override def nullSafeEval(string: Any, regex: Any): Any = {
    val strings = string.asInstanceOf[UTF8String].split(regex.asInstanceOf[UTF8String], -1)
    new GenericArrayData(strings.asInstanceOf[Array[Any]])
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val arrayClass = classOf[GenericArrayData].getName
    nullSafeCodeGen(ctx, ev, (str, pattern) =>
      // Array in java is covariant, so we don't need to cast UTF8String[] to Object[].
      s"""${ev.primitive} = new $arrayClass($str.split($pattern, -1));""")
  }

  override def prettyName: String = "split"
}


/**
 * Replace all substrings of str that match regexp with rep.
  * 将与regexp匹配的str的所有子字符串替换为rep
 *
 * NOTE: this expression is not THREAD-SAFE, as it has some internal mutable status.
 */
case class RegExpReplace(subject: Expression, regexp: Expression, rep: Expression)
  extends TernaryExpression with ImplicitCastInputTypes {

  // last regex in string, we will update the pattern iff regexp value changed.
  //在字符串的最后一个正则表达式中,我们将更新正则表达式的模式更改
  @transient private var lastRegex: UTF8String = _
  // last regex pattern, we cache it for performance concern
  //最后一个正则表达式模式,我们缓存它以解决性能问题
  @transient private var pattern: Pattern = _
  // last replacement string, we don't want to convert a UTF8String => java.langString every time.
  //最后一个替换字符串,我们不希望每次都转换UTF8String => java.langString
  @transient private var lastReplacement: String = _
  @transient private var lastReplacementInUTF8: UTF8String = _
  // result buffer write by Matcher
  @transient private val result: StringBuffer = new StringBuffer

  override def nullSafeEval(s: Any, p: Any, r: Any): Any = {
    if (!p.equals(lastRegex)) {
      // regex value changed
      lastRegex = p.asInstanceOf[UTF8String].clone()
      pattern = Pattern.compile(lastRegex.toString)
    }
    if (!r.equals(lastReplacementInUTF8)) {
      // replacement string changed
      lastReplacementInUTF8 = r.asInstanceOf[UTF8String].clone()
      lastReplacement = lastReplacementInUTF8.toString
    }
    val m = pattern.matcher(s.toString())
    result.delete(0, result.length())

    while (m.find) {
      m.appendReplacement(result, lastReplacement)
    }
    m.appendTail(result)

    UTF8String.fromString(result.toString)
  }

  override def dataType: DataType = StringType
  override def inputTypes: Seq[AbstractDataType] = Seq(StringType, StringType, StringType)
  override def children: Seq[Expression] = subject :: regexp :: rep :: Nil
  override def prettyName: String = "regexp_replace"

  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val termLastRegex = ctx.freshName("lastRegex")
    val termPattern = ctx.freshName("pattern")

    val termLastReplacement = ctx.freshName("lastReplacement")
    val termLastReplacementInUTF8 = ctx.freshName("lastReplacementInUTF8")

    val termResult = ctx.freshName("result")

    val classNamePattern = classOf[Pattern].getCanonicalName
    val classNameStringBuffer = classOf[java.lang.StringBuffer].getCanonicalName

    ctx.addMutableState("UTF8String", termLastRegex, s"${termLastRegex} = null;")
    ctx.addMutableState(classNamePattern, termPattern, s"${termPattern} = null;")
    ctx.addMutableState("String", termLastReplacement, s"${termLastReplacement} = null;")
    ctx.addMutableState("UTF8String",
      termLastReplacementInUTF8, s"${termLastReplacementInUTF8} = null;")
    ctx.addMutableState(classNameStringBuffer,
      termResult, s"${termResult} = new $classNameStringBuffer();")

    nullSafeCodeGen(ctx, ev, (subject, regexp, rep) => {
    s"""
      if (!$regexp.equals(${termLastRegex})) {
        // regex value changed
        ${termLastRegex} = $regexp.clone();
        ${termPattern} = ${classNamePattern}.compile(${termLastRegex}.toString());
      }
      if (!$rep.equals(${termLastReplacementInUTF8})) {
        // replacement string changed
        ${termLastReplacementInUTF8} = $rep.clone();
        ${termLastReplacement} = ${termLastReplacementInUTF8}.toString();
      }
      ${termResult}.delete(0, ${termResult}.length());
      java.util.regex.Matcher m = ${termPattern}.matcher($subject.toString());

      while (m.find()) {
        m.appendReplacement(${termResult}, ${termLastReplacement});
      }
      m.appendTail(${termResult});
      ${ev.primitive} = UTF8String.fromString(${termResult}.toString());
      ${ev.isNull} = false;
    """
    })
  }
}

/**
 * Extract a specific(idx) group identified by a Java regex.
  * 提取由Java正则表达式标识的特定(idx)组
 *
 * NOTE: this expression is not THREAD-SAFE, as it has some internal mutable status.
 */
case class RegExpExtract(subject: Expression, regexp: Expression, idx: Expression)
  extends TernaryExpression with ImplicitCastInputTypes {
  def this(s: Expression, r: Expression) = this(s, r, Literal(1))

  // last regex in string, we will update the pattern iff regexp value changed.
  //在字符串的最后一个正则表达式中,我们将更新正则表达式的模式更改
  @transient private var lastRegex: UTF8String = _
  // last regex pattern, we cache it for performance concern
  //最后一个正则表达式模式,我们缓存它以解决性能问题
  @transient private var pattern: Pattern = _

  override def nullSafeEval(s: Any, p: Any, r: Any): Any = {
    if (!p.equals(lastRegex)) {
      // regex value changed
      lastRegex = p.asInstanceOf[UTF8String].clone()
      pattern = Pattern.compile(lastRegex.toString)
    }
    val m = pattern.matcher(s.toString)
    if (m.find) {
      val mr: MatchResult = m.toMatchResult
      UTF8String.fromString(mr.group(r.asInstanceOf[Int]))
    } else {
      UTF8String.EMPTY_UTF8
    }
  }

  override def dataType: DataType = StringType
  override def inputTypes: Seq[AbstractDataType] = Seq(StringType, StringType, IntegerType)
  override def children: Seq[Expression] = subject :: regexp :: idx :: Nil
  override def prettyName: String = "regexp_extract"

  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val termLastRegex = ctx.freshName("lastRegex")
    val termPattern = ctx.freshName("pattern")
    val classNamePattern = classOf[Pattern].getCanonicalName

    ctx.addMutableState("UTF8String", termLastRegex, s"${termLastRegex} = null;")
    ctx.addMutableState(classNamePattern, termPattern, s"${termPattern} = null;")

    nullSafeCodeGen(ctx, ev, (subject, regexp, idx) => {
      s"""
      if (!$regexp.equals(${termLastRegex})) {
        // regex value changed
        ${termLastRegex} = $regexp.clone();
        ${termPattern} = ${classNamePattern}.compile(${termLastRegex}.toString());
      }
      java.util.regex.Matcher m =
        ${termPattern}.matcher($subject.toString());
      if (m.find()) {
        java.util.regex.MatchResult mr = m.toMatchResult();
        ${ev.primitive} = UTF8String.fromString(mr.group($idx));
        ${ev.isNull} = false;
      } else {
        ${ev.primitive} = UTF8String.EMPTY_UTF8;
        ${ev.isNull} = false;
      }"""
    })
  }
}
