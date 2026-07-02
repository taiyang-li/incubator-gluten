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
package org.apache.gluten.execution

import org.apache.spark.sql.catalyst.catalog.ExternalCatalogUtils

import org.apache.paimon.data.InternalRow
import org.apache.paimon.io.DataFileMeta
import org.apache.paimon.spark.PaimonScan
import org.apache.paimon.table.FileStoreTable
import org.apache.paimon.table.source.DataSplit
import org.apache.paimon.utils.InternalRowPartitionComputer

import scala.collection.JavaConverters._

class PaimonSparkShimImpl extends PaimonSparkShim {

  override def hasBeforeFiles(split: DataSplit): Boolean = {
    try {
      val method = split.getClass.getMethod("beforeFiles")
      !method.invoke(split).asInstanceOf[java.util.Collection[_]].isEmpty
    } catch {
      case _: NoSuchMethodException => false
    }
  }

  override def isChainSplit(split: DataSplit): Boolean = false

  override def getSplitPartition(split: DataSplit): InternalRow = split.partition()

  override def getBucketPath(split: DataSplit, file: DataFileMeta): String = split.bucketPath()

  override def getInternalPartitionComputer(scan: PaimonScan): InternalRowPartitionComputer = {
    val table = scan.table.asInstanceOf[FileStoreTable]
    new InternalRowPartitionComputer(
      ExternalCatalogUtils.DEFAULT_PARTITION_NAME,
      table.schema().logicalPartitionType(),
      table.partitionKeys().asScala.toArray,
      false)
  }
}
