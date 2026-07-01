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
package org.apache.gluten.expression

import org.apache.gluten.config.GlutenConfig
import org.apache.gluten.execution.WholeStageTransformerSuite

import org.apache.spark.SparkConf
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalyst.optimizer.{ConstantFolding, NullPropagation}
import org.apache.spark.sql.execution.ProjectExec
import org.apache.spark.sql.functions.udf

import java.io.File

case class MyStruct(a: Long, b: Array[Long])

case class MyStructWithNullValue(a: Option[Long], b: Array[Long])

class UDFPartialProjectSuite extends WholeStageTransformerSuite {
  private val columnarPartialProjectExecClassName =
    "org.apache.gluten.execution.ColumnarPartialProjectExec"

  disableFallbackCheck
  override protected val resourcePath: String = "/tpch-data-parquet"
  override protected val fileFormat: String = "parquet"

  override protected def sparkConf: SparkConf = {
    super.sparkConf
      .set("spark.shuffle.manager", "org.apache.spark.shuffle.sort.ColumnarShuffleManager")
      .set("spark.sql.files.maxPartitionBytes", "1g")
      .set("spark.sql.shuffle.partitions", "1")
      .set("spark.memory.offHeap.size", "2g")
      .set("spark.unsafe.exceptionOnMemoryLeak", "true")
      .set("spark.sql.autoBroadcastJoinThreshold", "-1")
      .set("spark.sql.sources.useV1SourceList", "avro")
      .set(
        "spark.sql.optimizer.excludedRules",
        ConstantFolding.ruleName + "," +
          NullPropagation.ruleName)
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    val table = "lineitem"
    val tableDir = getClass.getResource(resourcePath).getFile
    val tablePath = new File(tableDir, table).getAbsolutePath
    val tableDF = spark.read.format(fileFormat).load(tablePath)
    tableDF.createOrReplaceTempView(table)

    val plusOne = udf((x: Long) => x + 1)
    spark.udf.register("plus_one", plusOne)
    val noArgument = udf(() => 15)
    spark.udf.register("no_argument", noArgument)
    val concat = udf((x: String) => x + "_concat")
    spark.udf.register("concat_concat", concat)

  }

  ignore("test plus_one") {
    runQueryAndCompare("SELECT sum(plus_one(cast(l_orderkey as long))) from lineitem") {
      checkColumnarPartialProjectExec
    }
  }

  ignore("test subquery") {
    runQueryAndCompare(
      "select plus_one(" +
        "(select plus_one(count(*)) from (values (1)) t0(inner_c))) as col " +
        "from (values (2),(3)) t1(outer_c)") {
      checkColumnarPartialProjectExec
    }
  }

  ignore("test plus_one with column used twice") {
    runQueryAndCompare(
      "SELECT sum(plus_one(cast(l_orderkey as long)) + hash(l_orderkey)) from lineitem") {
      checkColumnarPartialProjectExec
    }
  }

  ignore("test plus_one without cast") {
    runQueryAndCompare("SELECT sum(plus_one(l_orderkey) + hash(l_orderkey)) from lineitem") {
      checkColumnarPartialProjectExec
    }
  }

  test("test plus_one with many columns") {
    runQueryAndCompare(
      "SELECT sum(plus_one(cast(l_orderkey as long)) + hash(l_partkey))" +
        "from lineitem " +
        "where l_orderkey < 3") {
      checkColumnarPartialProjectExec
    }
  }

  testWithMinSparkVersion("test plus_one in nested project lists", "3.4") {
    val sql = """
                |select plus_one(col1) as col2, l_partkey from (
                | select plus_one(l_orderkey) as col1, l_partkey from lineitem
                |)""".stripMargin
    runQueryAndCompare(sql) {
      checkColumnarPartialProjectExec
    }

    val df = spark.sql(sql)
    assert(countColumnarPartialProjectExec(df) == 2)
  }

  test("test plus_one with many columns in project") {
    runQueryAndCompare("SELECT plus_one(cast(l_orderkey as long)), hash(l_partkey) from lineitem") {
      checkColumnarPartialProjectExec
    }
  }

  ignore("test function no argument") {
    runQueryAndCompare("""SELECT no_argument(), l_orderkey
                         | from lineitem limit 100""".stripMargin) {
      checkColumnarPartialProjectExec
    }
  }

  test("test nondeterministic function input_file_name") {
    val df = spark.sql("""SELECT input_file_name(), l_orderkey
                         | from lineitem limit 100""".stripMargin)
    df.collect()
    assert(
      df.queryExecution.executedPlan
        .find(loadPlanClass(columnarPartialProjectExecClassName).isInstance)
        .isEmpty)
  }

  test("udf in agg simple") {
    runQueryAndCompare("""select sum(hash(plus_one(l_extendedprice)) + hash(l_orderkey) ) as revenue
                         | from   lineitem""".stripMargin) {
      checkColumnarPartialProjectExec
    }
  }

  test("udf in agg") {
    runQueryAndCompare("""select sum(hash(plus_one(l_extendedprice)) * l_discount
                         | + hash(l_orderkey) + hash(l_comment)) as revenue
                         | from   lineitem""".stripMargin) {
      checkColumnarPartialProjectExec
    }
  }

  test("test concat with string") {
    runQueryAndCompare("SELECT concat_concat(l_comment), hash(l_partkey) from lineitem") {
      checkColumnarPartialProjectExec
    }
  }

  test("udf with array") {
    spark.udf.register("array_plus_one", udf((arr: Array[Int]) => arr.map(_ + 1)))
    runQueryAndCompare("""
                         |SELECT
                         |  l_partkey,
                         |  sort_array(array_plus_one(array_data)) as orderkey_arr_plus_one
                         |FROM (
                         | SELECT l_partkey, collect_list(l_orderkey) as array_data
                         | FROM lineitem
                         | GROUP BY l_partkey
                         |)
                         |""".stripMargin) {
      checkColumnarPartialProjectExec
    }
  }

  test("udf with map") {
    spark.udf.register(
      "map_value_plus_one",
      udf((m: Map[String, Long]) => m.map { case (key, value) => key -> (value + 1) }))
    runQueryAndCompare("""
                         |SELECT
                         |  l_partkey,
                         |  map_value_plus_one(map_data)
                         |FROM (
                         | SELECT l_partkey,
                         | map(
                         |   concat('hello', l_orderkey % 2), l_orderkey,
                         |   concat('world', l_orderkey % 2), l_orderkey
                         | ) as map_data
                         | FROM lineitem
                         |)
                         |""".stripMargin) {
      checkColumnarPartialProjectExec
    }
  }

  test("udf with struct and array") {
    spark.udf.register("struct_plus_one", udf((m: MyStruct) => MyStruct(m.a + 1, m.b.map(_ + 1))))
    runQueryAndCompare("""
                         |SELECT
                         |  l_partkey,
                         |  struct_plus_one(struct_data)
                         |FROM (
                         | SELECT l_partkey,
                         | struct(
                         |   l_orderkey % 2 as a,
                         |   array(l_orderkey % 2, l_orderkey % 2 + 1, l_orderkey % 2 + 2) as b
                         | ) as struct_data
                         | FROM lineitem
                         |)
                         |""".stripMargin) {
      checkColumnarPartialProjectExec
    }
  }
  // only SparkVersion >= 3.4 support columnar native writer
  testWithSpecifiedSparkVersion(
    "only the child and parent of the project both support Columnar," +
      "just add ColumnarPartialProjectExec for the project",
    "3.4",
    "3.5") {
    Seq("false", "true").foreach {
      enableNativeScanAndWriter =>
        withSQLConf(
          GlutenConfig.NATIVE_WRITER_ENABLED.key -> enableNativeScanAndWriter,
          GlutenConfig.COLUMNAR_BATCHSCAN_ENABLED.key -> enableNativeScanAndWriter
        ) {
          withTable("t1") {
            spark.sql("""
                        |create table if not exists t1 (revenue double) using parquet
                        |""".stripMargin)
            runQueryAndCompare(""" insert overwrite t1
                                 |    select (plus_one(l_extendedprice) * l_discount
                                 |      + hash(l_orderkey) + hash(l_comment)) as revenue
                                 |    from   lineitem
                                 |""".stripMargin) {

              if (enableNativeScanAndWriter.toBoolean) {
                checkColumnarPartialProjectExec
              } else {
                checkSparkPlan[ProjectExec]
              }
            }
          }
        }
    }
  }

  test("test struct data with null fields") {
    spark.udf.register(
      "struct_plus_one",
      udf(
        (m: MyStructWithNullValue) =>
          MyStructWithNullValue(if (m.a.isEmpty) None else Some(m.a.get + 1), m.b.map(_ + 1))))
    runQueryAndCompare("""
                         |SELECT
                         |  l_partkey,
                         |  struct_plus_one(struct_data)
                         |FROM (
                         | SELECT l_partkey,
                         | struct(
                         |   CASE WHEN l_orderkey % 2 == 0 THEN l_orderkey ELSE null END as a,
                         |   array(l_orderkey % 2, l_orderkey % 2 + 1, l_orderkey % 2 + 2) as b
                         | ) as struct_data
                         | FROM lineitem
                         |)
                         |""".stripMargin) {
      checkColumnarPartialProjectExec
    }
  }

  private def checkColumnarPartialProjectExec(df: DataFrame): Unit = {
    val planClass = loadPlanClass(columnarPartialProjectExecClassName)
    val executedPlan = getExecutedPlan(df)
    assert(
      executedPlan.exists(planClass.isInstance),
      s"Expect ${planClass.getSimpleName} exists in executedPlan:\n ${executedPlan.last}"
    )
  }

  private def countColumnarPartialProjectExec(df: DataFrame): Int = {
    val planClass = loadPlanClass(columnarPartialProjectExecClassName)
    getExecutedPlan(df).count(planClass.isInstance)
  }

  private def loadPlanClass(className: String): Class[_] = {
    try {
      // scalastyle:off classforname
      Class.forName(className)
      // scalastyle:on classforname
    } catch {
      case e: ClassNotFoundException =>
        fail(s"$className is not available on current backend classpath", e)
    }
  }
}
