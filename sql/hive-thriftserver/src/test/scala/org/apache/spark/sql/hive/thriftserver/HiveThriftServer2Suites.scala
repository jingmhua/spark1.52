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

package org.apache.spark.sql.hive.thriftserver

import java.io.File
import java.net.URL
import java.sql.{Date, DriverManager, SQLException, Statement}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise, future}
import scala.util.{Random, Try}

import com.google.common.base.Charsets.UTF_8
import com.google.common.io.Files
import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hive.jdbc.HiveDriver
import org.apache.hive.service.auth.PlainSaslHelper
import org.apache.hive.service.cli.GetInfoType
import org.apache.hive.service.cli.thrift.TCLIService.Client
import org.apache.hive.service.cli.thrift.ThriftCLIServiceClient
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TSocket
import org.scalatest.BeforeAndAfterAll

import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.sql.test.ProcessTestUtils.ProcessOutputCapturer
import org.apache.spark.util.Utils
import org.apache.spark.{Logging, SparkFunSuite}

object TestData {
  def getTestDataFilePath(name: String): URL = {
    Thread.currentThread().getContextClassLoader.getResource(s"data/files/$name")
  }

  val smallKv = getTestDataFilePath("small_kv.txt")
  val smallKvWithNull = getTestDataFilePath("small_kv_with_null.txt")
}

class HiveThriftBinaryServerSuite extends HiveThriftJdbcTest {
  override def mode: ServerMode.Value = ServerMode.binary

  private def withCLIServiceClient(f: ThriftCLIServiceClient => Unit): Unit = {
    // Transport creation logic below mimics HiveConnection.createBinaryTransport
    //传输创建逻辑以下模仿HiveConnection.createBinaryTransport
    val rawTransport = new TSocket("localhost", serverPort)
    val user = System.getProperty("user.name")
    val transport = PlainSaslHelper.getPlainTransport(user, "anonymous", rawTransport)
    val protocol = new TBinaryProtocol(transport)
    val client = new ThriftCLIServiceClient(new Client(protocol))

    transport.open()
    try f(client) finally transport.close()
  }

  test("GetInfo Thrift API") {
    withCLIServiceClient { client =>
      val user = System.getProperty("user.name")
      val sessionHandle = client.openSession(user, "")

      assertResult("Spark SQL", "Wrong GetInfo(CLI_DBMS_NAME) result") {
        client.getInfo(sessionHandle, GetInfoType.CLI_DBMS_NAME).getStringValue
      }

      assertResult("Spark SQL", "Wrong GetInfo(CLI_SERVER_NAME) result") {
        client.getInfo(sessionHandle, GetInfoType.CLI_SERVER_NAME).getStringValue
      }

      assertResult(true, "Spark version shouldn't be \"Unknown\"") {
        val version = client.getInfo(sessionHandle, GetInfoType.CLI_DBMS_VER).getStringValue
        logInfo(s"Spark version: $version")
        version != "Unknown"
      }
    }
  }
  //JDBC查询执行
  test("JDBC query execution") {
    withJdbcStatement { statement =>
      val queries = Seq(
        "SET spark.sql.shuffle.partitions=3",
        "DROP TABLE IF EXISTS test",
        "CREATE TABLE test(key INT, val STRING)",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test",
        "CACHE TABLE test")

      queries.foreach(statement.execute)

      assertResult(5, "Row count mismatch") {
        val resultSet = statement.executeQuery("SELECT COUNT(*) FROM test")
        resultSet.next()
        resultSet.getInt(1)
      }
    }
  }
  //检查Hive版本
  test("Checks Hive version") {
    withJdbcStatement { statement =>
      val resultSet = statement.executeQuery("SET spark.sql.hive.version")
      resultSet.next()
      assert(resultSet.getString(1) === "spark.sql.hive.version")
      assert(resultSet.getString(2) === HiveContext.hiveExecutionVersion)
    }
  }
  //结果集包含NULL
  test("SPARK-3004 regression: result set containing NULL") {
    withJdbcStatement { statement =>
      val queries = Seq(
        "DROP TABLE IF EXISTS test_null",
        "CREATE TABLE test_null(key INT, val STRING)",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKvWithNull}' OVERWRITE INTO TABLE test_null")

      queries.foreach(statement.execute)

      val resultSet = statement.executeQuery("SELECT * FROM test_null WHERE key IS NULL")

      (0 until 5).foreach { _ =>
        resultSet.next()
        assert(resultSet.getInt(1) === 0)
        assert(resultSet.wasNull())
      }

      assert(!resultSet.next())
    }
  }
  //结果集迭代器问题
  test("SPARK-4292 regression: result set iterator issue") {
    withJdbcStatement { statement =>
      val queries = Seq(
        "DROP TABLE IF EXISTS test_4292",
        "CREATE TABLE test_4292(key INT, val STRING)",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test_4292")

      queries.foreach(statement.execute)

      val resultSet = statement.executeQuery("SELECT key FROM test_4292")

      Seq(238, 86, 311, 27, 165).foreach { key =>
        resultSet.next()
        assert(resultSet.getInt(1) === key)
      }

      statement.executeQuery("DROP TABLE IF EXISTS test_4292")
    }
  }
  //日期类型支持
  test("SPARK-4309 regression: Date type support") {
    withJdbcStatement { statement =>
      val queries = Seq(
        "DROP TABLE IF EXISTS test_date",
        "CREATE TABLE test_date(key INT, value STRING)",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test_date")

      queries.foreach(statement.execute)

      assertResult(Date.valueOf("2011-01-01")) {
        val resultSet = statement.executeQuery(
          "SELECT CAST('2011-01-01' as date) FROM test_date LIMIT 1")
        resultSet.next()
        resultSet.getDate(1)
      }
    }
  }
  //复杂类型支持
  test("SPARK-4407 regression: Complex type support") {
    withJdbcStatement { statement =>
      val queries = Seq(
        "DROP TABLE IF EXISTS test_map",
        "CREATE TABLE test_map(key INT, value STRING)",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test_map")

      queries.foreach(statement.execute)

      assertResult("""{238:"val_238"}""") {
        val resultSet = statement.executeQuery("SELECT MAP(key, value) FROM test_map LIMIT 1")
        resultSet.next()
        resultSet.getString(1)
      }

      assertResult("""["238","val_238"]""") {
        val resultSet = statement.executeQuery(
          "SELECT ARRAY(CAST(key AS STRING), value) FROM test_map LIMIT 1")
        resultSet.next()
        resultSet.getString(1)
      }
    }
  }
  //测试多个会话
  test("test multiple session") {
    import org.apache.spark.sql.SQLConf
    var defaultV1: String = null
    var defaultV2: String = null

    withMultipleConnectionJdbcStatement(
      // create table
      { statement =>

        val queries = Seq(
            "DROP TABLE IF EXISTS test_map",
            "CREATE TABLE test_map(key INT, value STRING)",
            s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test_map",
            "CACHE TABLE test_table AS SELECT key FROM test_map ORDER BY key DESC")

        queries.foreach(statement.execute)

        val rs1 = statement.executeQuery("SELECT key FROM test_table ORDER BY KEY DESC")
        val buf1 = new collection.mutable.ArrayBuffer[Int]()
        while (rs1.next()) {
          buf1 += rs1.getInt(1)
        }
        rs1.close()

        val rs2 = statement.executeQuery("SELECT key FROM test_map ORDER BY KEY DESC")
        val buf2 = new collection.mutable.ArrayBuffer[Int]()
        while (rs2.next()) {
          buf2 += rs2.getInt(1)
        }
        rs2.close()

        assert(buf1 === buf2)
      },

      // first session, we get the default value of the session status
      //第一个会话,我们得到会话状态的默认值
      { statement =>

        val rs1 = statement.executeQuery(s"SET ${SQLConf.SHUFFLE_PARTITIONS.key}")
        rs1.next()
        defaultV1 = rs1.getString(1)
        assert(defaultV1 != "200")
        rs1.close()

        val rs2 = statement.executeQuery("SET hive.cli.print.header")
        rs2.next()

        defaultV2 = rs2.getString(1)
        assert(defaultV1 != "true")
        rs2.close()
      },

      // second session, we update the session status
      //我们更新会话状态
      { statement =>

        val queries = Seq(
          s"SET ${SQLConf.SHUFFLE_PARTITIONS.key}=291",
          "SET hive.cli.print.header=true"
        )

        queries.map(statement.execute)
        val rs1 = statement.executeQuery(s"SET ${SQLConf.SHUFFLE_PARTITIONS.key}")
        rs1.next()
        assert("spark.sql.shuffle.partitions" === rs1.getString(1))
        assert("291" === rs1.getString(2))
        rs1.close()

        val rs2 = statement.executeQuery("SET hive.cli.print.header")
        rs2.next()
        assert("hive.cli.print.header" === rs2.getString(1))
        assert("true" === rs2.getString(2))
        rs2.close()
      },

      // third session, we get the latest session status, supposed to be the
      // default value
      //第三节,我们得到最新的会话状态,应该是默认值
      { statement =>

        val rs1 = statement.executeQuery(s"SET ${SQLConf.SHUFFLE_PARTITIONS.key}")
        rs1.next()
        assert(defaultV1 === rs1.getString(1))
        rs1.close()

        val rs2 = statement.executeQuery("SET hive.cli.print.header")
        rs2.next()
        assert(defaultV2 === rs2.getString(1))
        rs2.close()
      },

      // accessing the cached data in another session
      //访问另一个会话中的缓存数据
      { statement =>

        val rs1 = statement.executeQuery("SELECT key FROM test_table ORDER BY KEY DESC")
        val buf1 = new collection.mutable.ArrayBuffer[Int]()
        while (rs1.next()) {
          buf1 += rs1.getInt(1)
        }
        rs1.close()

        val rs2 = statement.executeQuery("SELECT key FROM test_map ORDER BY KEY DESC")
        val buf2 = new collection.mutable.ArrayBuffer[Int]()
        while (rs2.next()) {
          buf2 += rs2.getInt(1)
        }
        rs2.close()

        assert(buf1 === buf2)
        statement.executeQuery("UNCACHE TABLE test_table")

        // TODO need to figure out how to determine if the data loaded from cache
        val rs3 = statement.executeQuery("SELECT key FROM test_map ORDER BY KEY DESC")
        val buf3 = new collection.mutable.ArrayBuffer[Int]()
        while (rs3.next()) {
          buf3 += rs3.getInt(1)
        }
        rs3.close()

        assert(buf1 === buf3)
      },

      // accessing the uncached table
      //访问未缓存的表
      { statement =>

        // TODO need to figure out how to determine if the data loaded from cache
        val rs1 = statement.executeQuery("SELECT key FROM test_table ORDER BY KEY DESC")
        val buf1 = new collection.mutable.ArrayBuffer[Int]()
        while (rs1.next()) {
          buf1 += rs1.getInt(1)
        }
        rs1.close()

        val rs2 = statement.executeQuery("SELECT key FROM test_map ORDER BY KEY DESC")
        val buf2 = new collection.mutable.ArrayBuffer[Int]()
        while (rs2.next()) {
          buf2 += rs2.getInt(1)
        }
        rs2.close()

        assert(buf1 === buf2)
      }
    )
  }

  // This test often hangs and then times out, leaving the hanging processes.
  //这个测试经常挂起来,然后再次出现,留下挂起的过程,
  // Let's ignore it and improve the test.
  //我们忽略它并改进测试
  //测试jdbc取消
  ignore("test jdbc cancel") {
    withJdbcStatement { statement =>
      val queries = Seq(
        "DROP TABLE IF EXISTS test_map",
        "CREATE TABLE test_map(key INT, value STRING)",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test_map")

      queries.foreach(statement.execute)

      val largeJoin = "SELECT COUNT(*) FROM test_map " +
        List.fill(10)("join test_map").mkString(" ")
      val f = future { Thread.sleep(100); statement.cancel(); }
      val e = intercept[SQLException] {
        statement.executeQuery(largeJoin)
      }
      assert(e.getMessage contains "cancelled")
      Await.result(f, 3.minute)

      // cancel is a noop
      statement.executeQuery("SET spark.sql.hive.thriftServer.async=false")
      val sf = future { Thread.sleep(100); statement.cancel(); }
      val smallJoin = "SELECT COUNT(*) FROM test_map " +
        List.fill(4)("join test_map").mkString(" ")
      val rs1 = statement.executeQuery(smallJoin)
      Await.result(sf, 3.minute)
      rs1.next()
      assert(rs1.getInt(1) === math.pow(5, 5))
      rs1.close()

      val rs2 = statement.executeQuery("SELECT COUNT(*) FROM test_map")
      rs2.next()
      assert(rs2.getInt(1) === 5)
      rs2.close()
    }
  }
  //测试添加jar
  test("test add jar") {
    withMultipleConnectionJdbcStatement(
      {
        statement =>
          val jarFile =
            "../hive/src/test/resources/hive-hcatalog-core-0.13.1.jar"
              .split("/")
              .mkString(File.separator)

          statement.executeQuery(s"ADD JAR $jarFile")
      },

      {
        statement =>
          val queries = Seq(
            "DROP TABLE IF EXISTS smallKV",
            "CREATE TABLE smallKV(key INT, val STRING)",
            s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE smallKV",
            "DROP TABLE IF EXISTS addJar",
            """CREATE TABLE addJar(key string)
              |ROW FORMAT SERDE 'org.apache.hive.hcatalog.data.JsonSerDe'
            """.stripMargin) //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|

          queries.foreach(statement.execute)

          statement.executeQuery(
            """
              |INSERT INTO TABLE addJar SELECT 'k1' as key FROM smallKV limit 1
            """.stripMargin)//String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|

          val actualResult =
            statement.executeQuery("SELECT key FROM addJar")
          val actualResultBuffer = new collection.mutable.ArrayBuffer[String]()
          while (actualResult.next()) {
            actualResultBuffer += actualResult.getString(1)
          }
          actualResult.close()

          val expectedResult =
            statement.executeQuery("SELECT 'k1'")
          val expectedResultBuffer = new collection.mutable.ArrayBuffer[String]()
          while (expectedResult.next()) {
            expectedResultBuffer += expectedResult.getString(1)
          }
          expectedResult.close()

          assert(expectedResultBuffer === actualResultBuffer)

          statement.executeQuery("DROP TABLE IF EXISTS addJar")
          statement.executeQuery("DROP TABLE IF EXISTS smallKV")
      }
    )
  }
  //通过SET -v检查Hive版本
  test("Checks Hive version via SET -v") {
    withJdbcStatement { statement =>
      val resultSet = statement.executeQuery("SET -v")

      val conf = mutable.Map.empty[String, String]
      while (resultSet.next()) {
        conf += resultSet.getString(1) -> resultSet.getString(2)
      }

      assert(conf.get("spark.sql.hive.version") === Some("1.2.1"))
    }
  }
  //通过SET检查Hive版本
  test("Checks Hive version via SET") {
    withJdbcStatement { statement =>
      val resultSet = statement.executeQuery("SET")

      val conf = mutable.Map.empty[String, String]
      while (resultSet.next()) {
        conf += resultSet.getString(1) -> resultSet.getString(2)
      }

      assert(conf.get("spark.sql.hive.version") === Some("1.2.1"))
    }
  }
  //具有输入路径的ADD JAR具有URL方案
  test("SPARK-11595 ADD JAR with input path having URL scheme") {
    withJdbcStatement { statement =>
      statement.executeQuery("SET spark.sql.hive.thriftServer.async=true")

      val jarPath = "../hive/src/test/resources/TestUDTF.jar"
      val jarURL = s"file://${System.getProperty("user.dir")}/$jarPath"

      Seq(
        s"ADD JAR $jarURL",
        s"""CREATE TEMPORARY FUNCTION udtf_count2
            |AS 'org.apache.spark.sql.hive.execution.GenericUDTFCount2'
         """.stripMargin //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
      ).foreach(statement.execute)

      val rs1 = statement.executeQuery("DESCRIBE FUNCTION udtf_count2")

      assert(rs1.next())
      assert(rs1.getString(1) === "Function: udtf_count2")

      assert(rs1.next())
      assertResult("Class: org.apache.spark.sql.hive.execution.GenericUDTFCount2") {
        rs1.getString(1)
      }

      assert(rs1.next())
      assert(rs1.getString(1) === "Usage: To be added.")

      val dataPath = "../hive/src/test/resources/data/files/kv1.txt"

      Seq(
        s"CREATE TABLE test_udtf(key INT, value STRING)",
        s"LOAD DATA LOCAL INPATH '$dataPath' OVERWRITE INTO TABLE test_udtf"
      ).foreach(statement.execute)

      val rs2 = statement.executeQuery(
        "SELECT key, cc FROM test_udtf LATERAL VIEW udtf_count2(value) dd AS cc")

      assert(rs2.next())
      assert(rs2.getInt(1) === 97)
      assert(rs2.getInt(2) === 500)

      assert(rs2.next())
      assert(rs2.getInt(1) === 97)
      assert(rs2.getInt(2) === 500)
    }
  }
}

class HiveThriftHttpServerSuite extends HiveThriftJdbcTest {
  override def mode: ServerMode.Value = ServerMode.http
  //JDBC查询执行
  test("JDBC query execution") {
    withJdbcStatement { statement =>
      val queries = Seq(
        "SET spark.sql.shuffle.partitions=3",
        "DROP TABLE IF EXISTS test",
        "CREATE TABLE test(key INT, val STRING)",
        s"LOAD DATA LOCAL INPATH '${TestData.smallKv}' OVERWRITE INTO TABLE test",
        "CACHE TABLE test")

      queries.foreach(statement.execute)

      assertResult(5, "Row count mismatch") {
        val resultSet = statement.executeQuery("SELECT COUNT(*) FROM test")
        resultSet.next()
        resultSet.getInt(1)
      }
    }
  }
  //检查Hive版本
  test("Checks Hive version") {
    withJdbcStatement { statement =>
      val resultSet = statement.executeQuery("SET spark.sql.hive.version")
      resultSet.next()
      assert(resultSet.getString(1) === "spark.sql.hive.version")
      assert(resultSet.getString(2) === HiveContext.hiveExecutionVersion)
    }
  }
}

object ServerMode extends Enumeration {
  val binary, http = Value
}

abstract class HiveThriftJdbcTest extends HiveThriftServer2Test {
  Utils.classForName(classOf[HiveDriver].getCanonicalName)

  private def jdbcUri = if (mode == ServerMode.http) {
    s"""jdbc:hive2://localhost:$serverPort/
       |default?
       |hive.server2.transport.mode=http;
       |hive.server2.thrift.http.path=cliservice
     """.stripMargin.split("\n").mkString.trim //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
  } else {
    s"jdbc:hive2://localhost:$serverPort/"
  }

  def withMultipleConnectionJdbcStatement(fs: (Statement => Unit)*) {
    val user = System.getProperty("user.name")
    val connections = fs.map { _ => DriverManager.getConnection(jdbcUri, user, "") }
    val statements = connections.map(_.createStatement())

    try {
      statements.zip(fs).foreach { case (s, f) => f(s) }
    } finally {
      statements.foreach(_.close())
      connections.foreach(_.close())
    }
  }

  def withJdbcStatement(f: Statement => Unit) {
    withMultipleConnectionJdbcStatement(f)
  }
}

abstract class HiveThriftServer2Test extends SparkFunSuite with BeforeAndAfterAll with Logging {
  def mode: ServerMode.Value
  //stripSuffix去掉<string>字串中结尾的字符
  private val CLASS_NAME = HiveThriftServer2.getClass.getCanonicalName.stripSuffix("$")
  private val LOG_FILE_MARK = s"starting $CLASS_NAME, logging to "

  protected val startScript = "../../sbin/start-thriftserver.sh".split("/").mkString(File.separator)
  protected val stopScript = "../../sbin/stop-thriftserver.sh".split("/").mkString(File.separator)

  private var listeningPort: Int = _
  protected def serverPort: Int = listeningPort

  protected def user = System.getProperty("user.name")

  protected var warehousePath: File = _
  protected var metastorePath: File = _
  protected def metastoreJdbcUri = s"jdbc:derby:;databaseName=$metastorePath;create=true"

  private val pidDir: File = Utils.createTempDir("thriftserver-pid")
  private var logPath: File = _
  private var logTailingProcess: Process = _
  private var diagnosisBuffer: ArrayBuffer[String] = ArrayBuffer.empty[String]

  protected def serverStartCommand(port: Int) = {
    val portConf = if (mode == ServerMode.binary) {
      ConfVars.HIVE_SERVER2_THRIFT_PORT
    } else {
      ConfVars.HIVE_SERVER2_THRIFT_HTTP_PORT
    }

    val driverClassPath = {
      // Writes a temporary log4j.properties and prepend it to driver classpath, so that it
      // overrides all other potential log4j configurations contained in other dependency jar files.
      //写入一个临时log4j.properties并将其添加到驱动程序类路径中,以便它覆盖其他依赖项jar文件中包含的所有其他潜在的log4j配置
      val tempLog4jConf = Utils.createTempDir().getCanonicalPath

      Files.write(
        """log4j.rootCategory=DEBUG, console
          |log4j.appender.console=org.apache.log4j.ConsoleAppender
          |log4j.appender.console.target=System.err
          |log4j.appender.console.layout=org.apache.log4j.PatternLayout
          |log4j.appender.console.layout.ConversionPattern=%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n
        """.stripMargin,//String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
        new File(s"$tempLog4jConf/log4j.properties"),
        UTF_8)

      tempLog4jConf
    }

    s"""$startScript
       |  --master local
       |  --hiveconf ${ConfVars.METASTORECONNECTURLKEY}=$metastoreJdbcUri
       |  --hiveconf ${ConfVars.METASTOREWAREHOUSE}=$warehousePath
       |  --hiveconf ${ConfVars.HIVE_SERVER2_THRIFT_BIND_HOST}=localhost
       |  --hiveconf ${ConfVars.HIVE_SERVER2_TRANSPORT_MODE}=$mode
       |  --hiveconf $portConf=$port
       |  --driver-class-path $driverClassPath
       |  --driver-java-options -Dlog4j.debug
       |  --conf spark.ui.enabled=false
     """.stripMargin.split("\\s+").toSeq
    //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
  }

  /**
   * String to scan for when looking for the the thrift binary endpoint running.
   * This can change across Hive versions.
    * 查找节俭二进制端点运行时要扫描的字符串,这可以改变Hive版本
   */
  val THRIFT_BINARY_SERVICE_LIVE = "Starting ThriftBinaryCLIService on port"

  /**
   * String to scan for when looking for the the thrift HTTP endpoint running.
   * This can change across Hive versions.
    * 字符串在查找节俭HTTP端点运行时进行扫描,这可以通过Hive版本进行更改
   */
  val THRIFT_HTTP_SERVICE_LIVE = "Started ThriftHttpCLIService in http"

  val SERVER_STARTUP_TIMEOUT = 3.minutes

  private def startThriftServer(port: Int, attempt: Int) = {
    warehousePath = Utils.createTempDir()
    warehousePath.delete()
    metastorePath = Utils.createTempDir()
    metastorePath.delete()
    logPath = null
    logTailingProcess = null

    val command = serverStartCommand(port)

    diagnosisBuffer ++=
      s"""
         |### Attempt $attempt ###
         |HiveThriftServer2 command line: $command
         |Listening port: $port
         |System user: $user
       """.stripMargin.split("\n")
    //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|

    logInfo(s"Trying to start HiveThriftServer2: port=$port, mode=$mode, attempt=$attempt")

    logPath = {
      val lines = Utils.executeAndGetOutput(
        command = command,
        extraEnvironment = Map(
          // Disables SPARK_TESTING to exclude log4j.properties in test directories.
          //禁用SPARK_TESTING以排除测试目录中的log4j.properties
          "SPARK_TESTING" -> "0",
          // Points SPARK_PID_DIR to SPARK_HOME, otherwise only 1 Thrift server instance can be
          // started at a time, which is not Jenkins friendly.
          //点SPARK_PID_DIR到SPARK_HOME,否则一次只能启动1个Thrift服务器实例,这不是Jenkins友好的
          "SPARK_PID_DIR" -> pidDir.getCanonicalPath),
        redirectStderr = true)

      lines.split("\n").collectFirst {
        case line if line.contains(LOG_FILE_MARK) => new File(line.drop(LOG_FILE_MARK.length))
      }.getOrElse {
        throw new RuntimeException("Failed to find HiveThriftServer2 log file.")
      }
    }

    val serverStarted = Promise[Unit]()

    // Ensures that the following "tail" command won't fail.
    //确保以下“尾”命令不会失败
    logPath.createNewFile()
    val successLines = Seq(THRIFT_BINARY_SERVICE_LIVE, THRIFT_HTTP_SERVICE_LIVE)

    logTailingProcess = {
      val command = s"/usr/bin/env tail -n +0 -f ${logPath.getCanonicalPath}".split(" ")
      // Using "-n +0" to make sure all lines in the log file are checked.
      //使用“-n +0”来确保日志文件中的所有行被检查
      val builder = new ProcessBuilder(command: _*)
      val captureOutput = (line: String) => diagnosisBuffer.synchronized {
        diagnosisBuffer += line

        successLines.foreach { r =>
          if (line.contains(r)) {
            serverStarted.trySuccess(())
          }
        }
      }

        val process = builder.start()

      new ProcessOutputCapturer(process.getInputStream, captureOutput).start()
      new ProcessOutputCapturer(process.getErrorStream, captureOutput).start()
      process
    }

    Await.result(serverStarted.future, SERVER_STARTUP_TIMEOUT)
  }

  private def stopThriftServer(): Unit = {
    // The `spark-daemon.sh' script uses kill, which is not synchronous, have to wait for a while.
    //`spark-daemon.sh'脚本使用kill,而不是同步的,不得不等待一段时间
    Utils.executeAndGetOutput(
      command = Seq(stopScript),
      extraEnvironment = Map("SPARK_PID_DIR" -> pidDir.getCanonicalPath))
    Thread.sleep(3.seconds.toMillis)

    warehousePath.delete()
    warehousePath = null

    metastorePath.delete()
    metastorePath = null

    Option(logPath).foreach(_.delete())
    logPath = null

    Option(logTailingProcess).foreach(_.destroy())
    logTailingProcess = null
  }

  private def dumpLogs(): Unit = {
    logError(
      s"""
         |=====================================
         |HiveThriftServer2Suite failure output
         |=====================================
         |${diagnosisBuffer.mkString("\n")}
         |=========================================
         |End HiveThriftServer2Suite failure output
         |=========================================
       """.stripMargin)
    //String.stripMargin 移除每行字符串开头的空格和第一个遇到的垂直分割符|
  }

  override protected def beforeAll(): Unit = {
    // Chooses a random port between 10000 and 19999
    listeningPort = 10000 + Random.nextInt(10000)
    diagnosisBuffer.clear()

    // Retries up to 3 times with different port numbers if the server fails to start
    //如果服务器无法启动,请重试最多3次不同的端口号
    (1 to 3).foldLeft(Try(startThriftServer(listeningPort, 0))) { case (started, attempt) =>
      started.orElse {
        listeningPort += 1
        stopThriftServer()
        Try(startThriftServer(listeningPort, attempt))
      }
    }.recover {
      case cause: Throwable =>
        dumpLogs()
        throw cause
    }.get

    logInfo(s"HiveThriftServer2 started successfully")
  }

  override protected def afterAll(): Unit = {
    stopThriftServer()
    logInfo("HiveThriftServer2 stopped")
  }
}
