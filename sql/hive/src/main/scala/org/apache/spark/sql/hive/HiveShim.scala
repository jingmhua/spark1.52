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

package org.apache.spark.sql.hive

import java.io.{InputStream, OutputStream}
import java.rmi.server.UID

import org.apache.avro.Schema

/* Implicit conversions */
import scala.collection.JavaConversions._
import scala.language.implicitConversions
import scala.reflect.ClassTag

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.{Input, Output}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hive.ql.exec.{UDF, Utilities}
import org.apache.hadoop.hive.ql.plan.{FileSinkDesc, TableDesc}
import org.apache.hadoop.hive.serde2.ColumnProjectionUtils
import org.apache.hadoop.hive.serde2.avro.{AvroGenericRecordWritable, AvroSerdeUtils}
import org.apache.hadoop.hive.serde2.objectinspector.primitive.HiveDecimalObjectInspector
import org.apache.hadoop.io.Writable

import org.apache.spark.Logging
import org.apache.spark.sql.types.Decimal
import org.apache.spark.util.Utils

private[hive] object HiveShim {
  // Precision and scale to pass for unlimited decimals; these are the same as the precision and
  //精度和比例传递无限小数; 这些与精度相同
  // scale Hive 0.13 infers for BigDecimals from sources that don't specify them (e.g. UDFs)
  val UNLIMITED_DECIMAL_PRECISION = 38
  val UNLIMITED_DECIMAL_SCALE = 18

  /*
   * This function in hive-0.13 become private, but we have to do this to walkaround hive bug
   * hive-0.13中的这个功能变得私有,但我们必须这样做才能解决hive bug
   */
  private def appendReadColumnNames(conf: Configuration, cols: Seq[String]) {
    val old: String = conf.get(ColumnProjectionUtils.READ_COLUMN_NAMES_CONF_STR, "")
    val result: StringBuilder = new StringBuilder(old)
    var first: Boolean = old.isEmpty

    for (col <- cols) {
      if (first) {
        first = false
      } else {
        result.append(',')
      }
      result.append(col)
    }
    conf.set(ColumnProjectionUtils.READ_COLUMN_NAMES_CONF_STR, result.toString)
  }

  /*
   * Cannot use ColumnProjectionUtils.appendReadColumns directly, if ids is null or empty
   * 如果ids为null或为空，则无法直接使用ColumnProjectionUtils.appendReadColumns
   */
  def appendReadColumns(conf: Configuration, ids: Seq[Integer], names: Seq[String]) {
    if (ids != null && ids.nonEmpty) {
      ColumnProjectionUtils.appendReadColumns(conf, ids)
    }
    if (names != null && names.nonEmpty) {
      appendReadColumnNames(conf, names)
    }
  }

  /*
   * Bug introduced in hive-0.13. AvroGenericRecordWritable has a member recordReaderID that
   * is needed to initialize before serialization.
   */
  def prepareWritable(w: Writable, serDeProps: Seq[(String, String)]): Writable = {
    w match {
      case w: AvroGenericRecordWritable =>
        w.setRecordReaderID(new UID())
        // In Hive 1.1, the record's schema may need to be initialized manually or a NPE will
        // be thrown.
        if (w.getFileSchema() == null) {
          serDeProps
            .find(_._1 == AvroSerdeUtils.AvroTableProperties.SCHEMA_LITERAL.getPropName())
            .foreach { kv =>
              w.setFileSchema(new Schema.Parser().parse(kv._2))
            }
        }
      case _ =>
    }
    w
  }

  def toCatalystDecimal(hdoi: HiveDecimalObjectInspector, data: Any): Decimal = {
    if (hdoi.preferWritable()) {
      Decimal(hdoi.getPrimitiveWritableObject(data).getHiveDecimal().bigDecimalValue,
        hdoi.precision(), hdoi.scale())
    } else {
      Decimal(hdoi.getPrimitiveJavaObject(data).bigDecimalValue(), hdoi.precision(), hdoi.scale())
    }
  }

  /**
   * This class provides the UDF creation and also the UDF instance serialization and
   * de-serialization cross process boundary.
    * 此类提供UDF创建以及UDF实例序列化和反序列化跨进程边界。
   *
   * Detail discussion can be found at https://github.com/apache/spark/pull/3640
   *
   * @param functionClassName UDF class name
   */
  private[hive] case class HiveFunctionWrapper(var functionClassName: String)
    extends java.io.Externalizable {

    // for Serialization
    def this() = this(null)

    @transient
    def deserializeObjectByKryo[T: ClassTag](
        kryo: Kryo,
        in: InputStream,
        clazz: Class[_]): T = {
      val inp = new Input(in)
      val t: T = kryo.readObject(inp, clazz).asInstanceOf[T]
      inp.close()
      t
    }

    @transient
    def serializeObjectByKryo(
        kryo: Kryo,
        plan: Object,
        out: OutputStream) {
      val output: Output = new Output(out)
      kryo.writeObject(output, plan)
      output.close()
    }

    def deserializePlan[UDFType](is: java.io.InputStream, clazz: Class[_]): UDFType = {
      deserializeObjectByKryo(Utilities.runtimeSerializationKryo.get(), is, clazz)
        .asInstanceOf[UDFType]
    }

    def serializePlan(function: AnyRef, out: java.io.OutputStream): Unit = {
      serializeObjectByKryo(Utilities.runtimeSerializationKryo.get(), function, out)
    }

    private var instance: AnyRef = null

    def writeExternal(out: java.io.ObjectOutput) {
      // output the function name
      //输出函数名称
      out.writeUTF(functionClassName)

      // Write a flag if instance is null or not
      //如果实例为null，则写一个标志
      out.writeBoolean(instance != null)
      if (instance != null) {
        // Some of the UDF are serializable, but some others are not
        //一些UDF是可序列化的,但有些则不是
        // Hive Utilities can handle both cases
        //Hive Utilities可以处理这两种情况
        val baos = new java.io.ByteArrayOutputStream()
        serializePlan(instance, baos)
        val functionInBytes = baos.toByteArray

        // output the function bytes
        out.writeInt(functionInBytes.length)
        out.write(functionInBytes, 0, functionInBytes.length)
      }
    }

    def readExternal(in: java.io.ObjectInput) {
      // read the function name
      //读取函数名称
      functionClassName = in.readUTF()

      if (in.readBoolean()) {
        // if the instance is not null
        // read the function in bytes
        val functionInBytesLength = in.readInt()
        val functionInBytes = new Array[Byte](functionInBytesLength)
        in.read(functionInBytes, 0, functionInBytesLength)

        // deserialize the function object via Hive Utilities
        //通过Hive Utilities反序列化函数对象
        instance = deserializePlan[AnyRef](new java.io.ByteArrayInputStream(functionInBytes),
          Utils.getContextOrSparkClassLoader.loadClass(functionClassName))
      }
    }

    def createFunction[UDFType <: AnyRef](): UDFType = {
      if (instance != null) {
        instance.asInstanceOf[UDFType]
      } else {
        val func = Utils.getContextOrSparkClassLoader
          .loadClass(functionClassName).newInstance.asInstanceOf[UDFType]
        if (!func.isInstanceOf[UDF]) {
          // We cache the function if it's no the Simple UDF,
          // as we always have to create new instance for Simple UDF
          instance = func
        }
        func
      }
    }
  }

  /*
   * Bug introduced in hive-0.13. FileSinkDesc is serializable, but its member path is not.
   * 在hive-0.13中引入了Bug,FileSinkDesc是可序列化的,但其成员路径不是
   * Fix it through wrapper.
   */
  implicit def wrapperToFileSinkDesc(w: ShimFileSinkDesc): FileSinkDesc = {
    val f = new FileSinkDesc(new Path(w.dir), w.tableInfo, w.compressed)
    f.setCompressCodec(w.compressCodec)
    f.setCompressType(w.compressType)
    f.setTableInfo(w.tableInfo)
    f.setDestTableId(w.destTableId)
    f
  }

  /*
   * Bug introduced in hive-0.13. FileSinkDesc is serializable, but its member path is not.
   * Fix it through wrapper.
   */
  private[hive] class ShimFileSinkDesc(
      var dir: String,
      var tableInfo: TableDesc,
      var compressed: Boolean)
    extends Serializable with Logging {
    var compressCodec: String = _
    var compressType: String = _
    var destTableId: Int = _

    def setCompressed(compressed: Boolean) {
      this.compressed = compressed
    }

    def getDirName(): String = dir

    def setDestTableId(destTableId: Int) {
      this.destTableId = destTableId
    }

    def setTableInfo(tableInfo: TableDesc) {
      this.tableInfo = tableInfo
    }

    def setCompressCodec(intermediateCompressorCodec: String) {
      compressCodec = intermediateCompressorCodec
    }

    def setCompressType(intermediateCompressType: String) {
      compressType = intermediateCompressType
    }
  }
}
