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

package org.apache.spark.graphx.util

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import scala.collection.mutable.HashSet
import scala.language.existentials

import org.apache.spark.util.Utils

import com.esotericsoftware.reflectasm.shaded.org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor}
import com.esotericsoftware.reflectasm.shaded.org.objectweb.asm.Opcodes._


/**
 * Includes an utility function to test whether a function accesses a specific attribute
 * of an object.
  * 包含一个实用程序函数,用于测试函数是否访问对象的特定属性
 */
private[graphx] object BytecodeUtils {

  /**
   * Test whether the given closure invokes the specified method in the specified class.
    * 测试给定的闭包是否在指定的类中调用指定的方法
   */
  def invokedMethod(closure: AnyRef, targetClass: Class[_], targetMethod: String): Boolean = {
    if (_invokedMethod(closure.getClass, "apply", targetClass, targetMethod)) {
      true
    } else {
      // look at closures enclosed in this closure
      //看看封闭在这个封闭物中的封闭物
      for (f <- closure.getClass.getDeclaredFields
           if f.getType.getName.startsWith("scala.Function")) {
        f.setAccessible(true)
        if (invokedMethod(f.get(closure), targetClass, targetMethod)) {
          return true
        }
      }
      return false
    }
  }

  private def _invokedMethod(cls: Class[_], method: String,
      targetClass: Class[_], targetMethod: String): Boolean = {

    val seen = new HashSet[(Class[_], String)]
    var stack = List[(Class[_], String)]((cls, method))

    while (stack.nonEmpty) {
      val (c, m) = stack.head
      stack = stack.tail
      seen.add((c, m))
      val finder = new MethodInvocationFinder(c.getName, m)
      getClassReader(c).accept(finder, 0)
      for (classMethod <- finder.methodsInvoked) {
        if (classMethod._1 == targetClass && classMethod._2 == targetMethod) {
          return true
        } else if (!seen.contains(classMethod)) {
          stack = classMethod :: stack
        }
      }
    }
    return false
  }

  /**
   * Get an ASM class reader for a given class from the JAR that loaded it.
    * 从加载它的JAR获取给定类的ASM类阅读器
   */
  private def getClassReader(cls: Class[_]): ClassReader = {
    // Copy data over, before delegating to ClassReader - else we can run out of open file handles.
    //在委托给ClassReader之前复制数据 - 否则我们可能会用完打开的文件句柄
    val className = cls.getName.replaceFirst("^.*\\.", "") + ".class"
    val resourceStream = cls.getResourceAsStream(className)
    // todo: Fixme - continuing with earlier behavior ...
    if (resourceStream == null) return new ClassReader(resourceStream)

    val baos = new ByteArrayOutputStream(128)
    Utils.copyStream(resourceStream, baos, true)
    new ClassReader(new ByteArrayInputStream(baos.toByteArray))
  }

  /**
   * Given the class name, return whether we should look into the class or not. This is used to
   * skip examing a large quantity of Java or Scala classes that we know for sure wouldn't access
   * the closures. Note that the class name is expected in ASM style (i.e. use "/" instead of ".").
    *
    * 给出类名,返回是否应该查看类,这用于跳过检查我们知道肯定不会访问闭包的大量Java或Scala类,
    * 请注意,类名称应采用ASM样式（即使用“/”而不是“。”）。
   */
  private def skipClass(className: String): Boolean = {
    val c = className
    c.startsWith("java/") || c.startsWith("scala/") || c.startsWith("javax/")
  }

  /**
   * Find the set of methods invoked by the specified method in the specified class.
   * For example, after running the visitor,
   *   MethodInvocationFinder("spark/graph/Foo", "test")
   * its methodsInvoked variable will contain the set of methods invoked directly by
   * Foo.test(). Interface invocations are not returned as part of the result set because we cannot
   * determine the actual metod invoked by inspecting the bytecode.
   */
  private class MethodInvocationFinder(className: String, methodName: String)
    extends ClassVisitor(ASM4) {

    val methodsInvoked = new HashSet[(Class[_], String)]

    override def visitMethod(access: Int, name: String, desc: String,
                             sig: String, exceptions: Array[String]): MethodVisitor = {
      if (name == methodName) {
        new MethodVisitor(ASM4) {
          override def visitMethodInsn(op: Int, owner: String, name: String, desc: String) {
            if (op == INVOKEVIRTUAL || op == INVOKESPECIAL || op == INVOKESTATIC) {
              if (!skipClass(owner)) {
                methodsInvoked.add((Utils.classForName(owner.replace("/", ".")), name))
              }
            }
          }
        }
      } else {
        null
      }
    }
  }
}
