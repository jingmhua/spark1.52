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

// scalastyle:off println
package org.apache.spark.examples.mllib

import java.io.File

import com.google.common.io.Files
import scopt.OptionParser

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.stat.MultivariateOnlineSummarizer
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SQLContext, DataFrame}

/**
 * 如何使用SQL数据集的一个例子
 * An example of how to use [[org.apache.spark.sql.DataFrame]] as a Dataset for ML. Run with
 * {{{
 * ./bin/run-example org.apache.spark.examples.mllib.DatasetExample [options]
 * }}}
 * If you use it as a template to create your own app, please use `spark-submit` to submit your app.
 * 如果你使用它作为一个模板来创建自己的应用程序
 */
object DatasetExample {

  case class Params(
      input: String = "../data/mllib/sample_libsvm_data.txt",
      /**
 *  libSVM的数据格式
 *  <label> <index1>:<value1> <index2>:<value2> ...
 *  其中<label>是训练数据集的目标值,对于分类,它是标识某类的整数(支持多个类);对于回归,是任意实数
 *  <index>是以1开始的整数,可以是不连续
 *  <value>为实数,也就是我们常说的自变量
 */
      dataFormat: String = "libsvm") extends AbstractParams[Params]

  def main(args: Array[String]) {
    val defaultParams = Params()

    val parser = new OptionParser[Params]("DatasetExample") {
      head("Dataset: an example app using DataFrame as a Dataset for ML.")
      opt[String]("input")
        .text(s"input path to dataset")
        .action((x, c) => c.copy(input = x))
      opt[String]("dataFormat")
      /**
       *  libSVM的数据格式
       *  <label> <index1>:<value1> <index2>:<value2> ...
       *  其中<label>是训练数据集的目标值,对于分类,它是标识某类的整数(支持多个类);对于回归,是任意实数
       *  <index>是以1开始的整数,可以是不连续
       *  <value>为实数,也就是我们常说的自变量
       */
        .text("data format: libsvm (default), dense (deprecated in Spark v1.1)")
        .action((x, c) => c.copy(input = x))
      checkConfig { params =>
        success
      }
    }

    parser.parse(args, defaultParams).map { params =>
      run(params)
    }.getOrElse {
      sys.exit(1)
    }
  }

  def run(params: Params) {

    val conf = new SparkConf().setAppName(s"DatasetExample with $params").setMaster("local[*]")
    val sc = new SparkContext(conf)
    val sqlContext = new SQLContext(sc)
    import sqlContext.implicits._  // for implicit conversions 隐式转换

    // Load input data 加载输入数据
    val origData: RDD[LabeledPoint] = params.dataFormat match {
      //稠密数据
      case "dense" => MLUtils.loadLabeledPoints(sc, params.input)
      //libsvm数据
      /**
       *  libSVM的数据格式
       *  <label> <index1>:<value1> <index2>:<value2> ...
       *  其中<label>是训练数据集的目标值,对于分类,它是标识某类的整数(支持多个类);对于回归,是任意实数
       *  <index>是以1开始的整数,可以是不连续
       *  <value>为实数,也就是我们常说的自变量
       */
      case "libsvm" => MLUtils.loadLibSVMFile(sc, params.input)
    }
    //Loaded 100 instances from file: ../data/mllib/sample_libsvm_data.txt
    println(s"Loaded ${origData.count()} instances from file: ${params.input}")

    // Convert input data to DataFrame explicitly.
    //显示转换输入数据集
    val df: DataFrame = origData.toDF()
    /**
     Inferred schema:
    {
      "type" : "struct",
      "fields" : [ {
        "name" : "label",
        "type" : "double",
        "nullable" : false,
        "metadata" : { }
      }, {
        "name" : "features",
        "type" : {
          "type" : "udt",
          "class" : "org.apache.spark.mllib.linalg.VectorUDT",
          "pyClass" : "pyspark.mllib.linalg.VectorUDT",
          "sqlType" : {
            "type" : "struct",
            "fields" : [ {
              "name" : "type",
              "type" : "byte",
              "nullable" : false,
              "metadata" : { }
            }, {
              "name" : "size",
              "type" : "integer",
              "nullable" : true,
              "metadata" : { }
            }, {
              "name" : "indices",
              "type" : {
                "type" : "array",
                "elementType" : "integer",
                "containsNull" : false
              },
              "nullable" : true,
              "metadata" : { }
            }, {
              "name" : "values",
              "type" : {
                "type" : "array",
                "elementType" : "double",
                "containsNull" : false
              },
              "nullable" : true,
              "metadata" : { }
            } ]
          }
        },
        "nullable" : true,
        "metadata" : { }
      } ]
    }*/
    println(s"Inferred schema:\n${df.schema.prettyJson}")
    //Converted to DataFrame with 100 records
    println(s"Converted to DataFrame with ${df.count()} records")

    // Select columns
    //选择列
    val labelsDf: DataFrame = df.select("label")
    /**
      +-----+--------------------+
      |label|            features|
      +-----+--------------------+
      |  0.0|(692,[127,128,129...|
      |  1.0|(692,[158,159,160...|
      |  1.0|(692,[124,125,126...|
      |  1.0|(692,[152,153,154...|
      |  1.0|(692,[151,152,153...|
      +-----+--------------------+*/
    df.show(5)
    val labels: RDD[Double] = labelsDf.map { case Row(v: Double) => v }
    val numLabels = labels.count()
    //fold操作用于对RDD中的元素进行迭代操作,并且利用了一个变量保存迭代过程中的中间结果
    val meanLabelFold = labels.fold(0.0)(_ + _)
    val meanLabel=meanLabelFold/ numLabels
    //100==57.0==0.57
    println(numLabels+"=="+meanLabelFold+"=="+meanLabel)
    //Selected label column with average value 0.57
    //选定标签列平均值0.57
    println(s"Selected label column with average value $meanLabel")

    val featuresDf: DataFrame = df.select("features")
    /**
      +--------------------+
      |            features|
      +--------------------+
      |(692,[127,128,129...|
      |(692,[158,159,160...|
      |(692,[124,125,126...|
      |(692,[152,153,154...|
      |(692,[151,152,153...|
      +--------------------+*/
    featuresDf.show(5)
    val features: RDD[Vector] = featuresDf.map { case Row(v: Vector) => 
      //(692,[127,128,129,155,156,157,158,182,183,,253.0,138.0])
      //println(v.toString())
      v
      }
    //聚合 aggregate
    val featureSummary = features.aggregate(new MultivariateOnlineSummarizer())(
      (summary, feat) => summary.add(feat),
      (sum1, sum2) => sum1.merge(sum2))
    //选定的特征列的平均值
    println(s"Selected features column with average values:\n ${featureSummary.mean.toString}")
    //创建临时目录
    val tmpDir = Files.createTempDir()
    //在JVM进程退出的时候删除文件,通常用在临时文件的删除.
    tmpDir.deleteOnExit()
    val outputDir = new File(tmpDir, "dataset").toString
    //Saving to C:\Users\liushuhua\AppData\Local\Temp\1484552385342-0\dataset as Parquet file
    println(s"Saving to $outputDir as Parquet file.")
    df.write.parquet(outputDir)
    //Loading Parquet file with UDT from C:\Users\liushuhua\AppData\Local\Temp\1484552385342-0\dataset.
    println(s"Loading Parquet file with UDT from $outputDir.")
    val newDataset = sqlContext.read.parquet(outputDir)

    println(s"Schema from Parquet: ${newDataset.schema.prettyJson}")
    val newFeatures = newDataset.select("features").map { case Row(v: Vector) => v }
    val newFeaturesSummary = newFeatures.aggregate(new MultivariateOnlineSummarizer())(
      (summary, feat) => summary.add(feat),
      (sum1, sum2) => sum1.merge(sum2))
    //Selected features column with average values:
    //具有平均值的选定要素列：
    println(s"Selected features column with average values:\n ${newFeaturesSummary.mean.toString}")

    sc.stop()
  }

}
// scalastyle:on println
