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
package org.apache.gluten.test

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.{QueryExecution, SparkPlan}
import org.apache.spark.sql.util.QueryExecutionListener

trait NativeWriteCheckerBase {

  protected def spark: SparkSession

  protected def normalizeExecutedPlan(qe: QueryExecution): SparkPlan = qe.executedPlan

  protected def isNativeWritePlan(plan: SparkPlan): Boolean

  protected def waitUntilListenerBusEmpty(): Unit

  protected def onNativeWriteQueryFailure(
      funcName: String,
      qe: QueryExecution,
      error: Exception): Unit = {}

  protected def withNativeWriteCheck(expectedNativeWrite: Boolean)(block: => Unit): Unit = {
    var nativeUsed = false
    val queryListener = new QueryExecutionListener {
      override def onFailure(funcName: String, qe: QueryExecution, e: Exception): Unit = {
        onNativeWriteQueryFailure(funcName, qe, e)
      }

      override def onSuccess(funcName: String, qe: QueryExecution, duration: Long): Unit = {
        if (!nativeUsed) {
          nativeUsed = isNativeWritePlan(normalizeExecutedPlan(qe))
        }
      }
    }

    try {
      spark.listenerManager.register(queryListener)
      block
      waitUntilListenerBusEmpty()
      assert(
        nativeUsed == expectedNativeWrite,
        s"Expected native write usage to be $expectedNativeWrite, but was $nativeUsed")
    } finally {
      spark.listenerManager.unregister(queryListener)
    }
  }

  protected def checkNativeWrite(sqlStr: String, expectNative: Boolean = true): Unit = {
    withNativeWriteCheck(expectNative) {
      spark.sql(sqlStr)
    }
  }
}
