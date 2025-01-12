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

package org.apache.spark.mllib.stat

import org.apache.spark.SparkFunSuite
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.util.TestingUtils._
/**
 * 多元变量在线概述
 */
class MultivariateOnlineSummarizerSuite extends SparkFunSuite {

  test("basic error handing") {//基本的错误处理
    val summarizer = new MultivariateOnlineSummarizer

    assert(summarizer.count === 0, "should be zero since nothing is added.")
    //从空总结得到数矩阵的非零元素应该抛出异常
    withClue("Getting numNonzeros from empty summarizer should throw exception.") {
      intercept[IllegalArgumentException] {
        summarizer.numNonzeros //向量每列非零元素的个数
      }
    }
    //从空值方差应该抛出异常
    withClue("Getting variance from empty summarizer should throw exception.") {
      intercept[IllegalArgumentException] {
        summarizer.variance //样本方差 构成样本的单项数值与平均值之间的差的平方和除以n-1
      }
    }
    //从空总结得到的意思是应该抛出异常
    withClue("Getting mean from empty summarizer should throw exception.") {
      intercept[IllegalArgumentException] {
        summarizer.mean //均值
      }
    }
    //得到最大空值应该抛出异常
    withClue("Getting max from empty summarizer should throw exception.") {
      intercept[IllegalArgumentException] {
        summarizer.max //最大值
      }
    }
    //从最小空值应该抛出异常
    withClue("Getting min from empty summarizer should throw exception.") {
      intercept[IllegalArgumentException] {
        summarizer.min //最小值
      }
    }

    summarizer.add(Vectors.dense(-1.0, 2.0, 6.0)).add(Vectors.sparse(3, Seq((0, -2.0), (1, 6.0))))
    //添加一个新的具有不同数组大小的密集样本应抛出异常
    withClue("Adding a new dense sample with different array size should throw exception.") {
      intercept[IllegalArgumentException] {
        summarizer.add(Vectors.dense(3.0, 1.0)) 
      }
    }
  //添加一个新的具有不同数组大小的稀疏示例应该抛出异常
    withClue("Adding a new sparse sample with different array size should throw exception.") {
      intercept[IllegalArgumentException] {
        summarizer.add(Vectors.sparse(5, Seq((0, -2.0), (1, 6.0))))
      }
    }

    val summarizer2 = (new MultivariateOnlineSummarizer).add(Vectors.dense(1.0, -2.0, 0.0, 4.0))
    withClue("Merging a new summarizer with different dimensions should throw exception.") {
      intercept[IllegalArgumentException] {
        summarizer.merge(summarizer2)
      }
    }
  }

  test("dense vector input") {//密集向量的输入
    // For column 2, the maximum will be 0.0, and it's not explicitly added since we ignore all
    //对于列2,最大值将是0,它没有显式添加,因为我们忽略了所有的零
    // the zeros; it's a case we need to test. For column 3, the minimum will be 0.0 which we
    // need to test as well.
    //这是一个我们需要测试的案例.对于列3,最低将是0,我们需要测试以及
    val summarizer = (new MultivariateOnlineSummarizer)
      //密度矩阵,零值也存储
      .add(Vectors.dense(-1.0, 0.0, 6.0))
      .add(Vectors.dense(3.0, -3.0, 0.0))
    //每列的均值
    assert(summarizer.mean ~== Vectors.dense(1.0, -1.5, 3.0) absTol 1E-5, "mean mismatch")
    //向量最小值
    assert(summarizer.min ~== Vectors.dense(-1.0, -3, 0.0) absTol 1E-5, "min mismatch")
    //向量最大值
    assert(summarizer.max ~== Vectors.dense(3.0, 0.0, 6.0) absTol 1E-5, "max mismatch")
    //向量每列非零元素的个数
    assert(summarizer.numNonzeros ~== Vectors.dense(2, 1, 1) absTol 1E-5, "numNonzeros mismatch")
    //样本方差 构成样本的单项数值与平均值之间的差的平方和除以n-1
    assert(summarizer.variance ~== Vectors.dense(8.0, 4.5, 18.0) absTol 1E-5, "variance mismatch")
    //矩阵数
    assert(summarizer.count === 2)
  }

  test("sparse vector input") {//稀疏向量输入
    val summarizer = (new MultivariateOnlineSummarizer)
      //创建稀疏矩阵,指定元素的个数、索引及非零值,数组方式
      .add(Vectors.sparse(3, Seq((0, -1.0), (2, 6.0))))
      .add(Vectors.sparse(3, Seq((0, 3.0), (1, -3.0))))
    //每列的均值
    assert(summarizer.mean ~== Vectors.dense(1.0, -1.5, 3.0) absTol 1E-5, "mean mismatch")
    //向量最小值
    assert(summarizer.min ~== Vectors.dense(-1.0, -3, 0.0) absTol 1E-5, "min mismatch")
    //向量最大值
    assert(summarizer.max ~== Vectors.dense(3.0, 0.0, 6.0) absTol 1E-5, "max mismatch")
    //向量每列非零元素的个数
    assert(summarizer.numNonzeros ~== Vectors.dense(2, 1, 1) absTol 1E-5, "numNonzeros mismatch")
    //样本方差 构成样本的单项数值与平均值之间的差的平方和除以n-1
    assert(summarizer.variance ~== Vectors.dense(8.0, 4.5, 18.0) absTol 1E-5, "variance mismatch")
    //矩阵数
    assert(summarizer.count === 2)
  }

  test("mixing dense and sparse vector input") {//密集和稀疏向量混合输入
    val summarizer = (new MultivariateOnlineSummarizer)
      .add(Vectors.sparse(3, Seq((0, -2.0), (1, 2.3))))
      .add(Vectors.dense(0.0, -1.0, -3.0))
      .add(Vectors.sparse(3, Seq((1, -5.1))))
      .add(Vectors.dense(3.8, 0.0, 1.9))
      .add(Vectors.dense(1.7, -0.6, 0.0))
      .add(Vectors.sparse(3, Seq((1, 1.9), (2, 0.0))))
    //每列的均值
    assert(summarizer.mean ~==
      Vectors.dense(0.583333333333, -0.416666666666, -0.183333333333) absTol 1E-5, "mean mismatch")
    //每列的最小值
    assert(summarizer.min ~== Vectors.dense(-2.0, -5.1, -3) absTol 1E-5, "min mismatch")
    //每列的最大值
    assert(summarizer.max ~== Vectors.dense(3.8, 2.3, 1.9) absTol 1E-5, "max mismatch")
    //向量每列非零元素的个数
    assert(summarizer.numNonzeros ~== Vectors.dense(3, 5, 2) absTol 1E-5, "numNonzeros mismatch")
    //向量每列样本方差 构成样本的单项数值与平均值之间的差的平方和除以n-1
    assert(summarizer.variance ~==
      Vectors.dense(3.857666666666, 7.0456666666666, 2.48166666666666) absTol 1E-5,
      "variance mismatch")
    //矩阵数
    assert(summarizer.count === 6)
  }

  test("merging two summarizers") {//合并两个摘要
    val summarizer1 = (new MultivariateOnlineSummarizer)
      .add(Vectors.sparse(3, Seq((0, -2.0), (1, 2.3))))
      .add(Vectors.dense(0.0, -1.0, -3.0))

    val summarizer2 = (new MultivariateOnlineSummarizer)
      .add(Vectors.sparse(3, Seq((1, -5.1))))
      .add(Vectors.dense(3.8, 0.0, 1.9))
      .add(Vectors.dense(1.7, -0.6, 0.0))
      .add(Vectors.sparse(3, Seq((1, 1.9), (2, 0.0))))

    val summarizer = summarizer1.merge(summarizer2)

    assert(summarizer.mean ~==
      Vectors.dense(0.583333333333, -0.416666666666, -0.183333333333) absTol 1E-5, "mean mismatch")

    assert(summarizer.min ~== Vectors.dense(-2.0, -5.1, -3) absTol 1E-5, "min mismatch")

    assert(summarizer.max ~== Vectors.dense(3.8, 2.3, 1.9) absTol 1E-5, "max mismatch")

    assert(summarizer.numNonzeros ~== Vectors.dense(3, 5, 2) absTol 1E-5, "numNonzeros mismatch")
    //向量每列样本方差 构成样本的单项数值与平均值之间的差的平方和除以n-1
    assert(summarizer.variance ~==
      Vectors.dense(3.857666666666, 7.0456666666666, 2.48166666666666) absTol 1E-5,
      "variance mismatch")

    assert(summarizer.count === 6)
  }

  test("merging summarizer with empty summarizer") {//总结总结合并空
    // If one of two is non-empty, this should return the non-empty summarizer.
    //如果一个非空,本应该返回非空值
    // If both of them are empty, then just return the empty summarizer.
    //如果它们都是空的,就返回空值
    val summarizer1 = (new MultivariateOnlineSummarizer)
      .add(Vectors.dense(0.0, -1.0, -3.0)).merge(new MultivariateOnlineSummarizer)
    assert(summarizer1.count === 1)

    val summarizer2 = (new MultivariateOnlineSummarizer)
      .merge((new MultivariateOnlineSummarizer).add(Vectors.dense(0.0, -1.0, -3.0)))
    assert(summarizer2.count === 1)

    val summarizer3 = (new MultivariateOnlineSummarizer).merge(new MultivariateOnlineSummarizer)
    assert(summarizer3.count === 0)

    assert(summarizer1.mean ~== Vectors.dense(0.0, -1.0, -3.0) absTol 1E-5, "mean mismatch")//平均配

    assert(summarizer2.mean ~== Vectors.dense(0.0, -1.0, -3.0) absTol 1E-5, "mean mismatch")//平均配

    assert(summarizer1.min ~== Vectors.dense(0.0, -1.0, -3.0) absTol 1E-5, "min mismatch")

    assert(summarizer2.min ~== Vectors.dense(0.0, -1.0, -3.0) absTol 1E-5, "min mismatch")

    assert(summarizer1.max ~== Vectors.dense(0.0, -1.0, -3.0) absTol 1E-5, "max mismatch")

    assert(summarizer2.max ~== Vectors.dense(0.0, -1.0, -3.0) absTol 1E-5, "max mismatch")

    assert(summarizer1.numNonzeros ~== Vectors.dense(0, 1, 1) absTol 1E-5, "numNonzeros mismatch")

    assert(summarizer2.numNonzeros ~== Vectors.dense(0, 1, 1) absTol 1E-5, "numNonzeros mismatch")
     //向量每列样本方差 构成样本的单项数值与平均值之间的差的平方和除以n-1
    assert(summarizer1.variance ~== Vectors.dense(0, 0, 0) absTol 1E-5, "variance mismatch")

    assert(summarizer2.variance ~== Vectors.dense(0, 0, 0) absTol 1E-5, "variance mismatch")
  }

  test("merging summarizer when one side has zero mean (SPARK-4355)") {//合并总结当一侧有零均值
    val s0 = new MultivariateOnlineSummarizer()
      .add(Vectors.dense(2.0))
      .add(Vectors.dense(2.0))
    val s1 = new MultivariateOnlineSummarizer()
      .add(Vectors.dense(1.0))
      .add(Vectors.dense(-1.0))
    s0.merge(s1)
    assert(s0.mean(0) ~== 1.0 absTol 1e-14)
  }
}
