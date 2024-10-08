/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.plan.rules.logical

import org.apache.flink.table.api._
import org.apache.flink.table.api.config.OptimizerConfigOptions
import org.apache.flink.table.planner.plan.optimize.program.FlinkStreamProgram
import org.apache.flink.table.planner.utils.TableTestBase

import org.junit.jupiter.api.Test

/** IncrementalAggregateTest Test for [[SplitAggregateRule]]. */
class SplitAggregateRuleTest extends TableTestBase {
  private val util = streamTestUtil()
  util.addTableSource[(Long, Int, String)]("MyTable", 'a, 'b, 'c)
  util.buildStreamProgram(FlinkStreamProgram.PHYSICAL)
  util.tableEnv.getConfig
    .set(OptimizerConfigOptions.TABLE_OPTIMIZER_DISTINCT_AGG_SPLIT_ENABLED, Boolean.box(true))

  @Test
  def testSingleDistinctAgg(): Unit = {
    util.verifyRelPlan("SELECT COUNT(DISTINCT c) FROM MyTable")
  }

  @Test
  def testSingleMinAgg(): Unit = {
    // does not contain distinct agg
    util.verifyRelPlan("SELECT MIN(c) FROM MyTable")
  }

  @Test
  def testSingleFirstValueAgg(): Unit = {
    // does not contain distinct agg
    util.verifyRelPlan("SELECT FIRST_VALUE(c) FROM MyTable GROUP BY a")
  }

  @Test
  def testMultiDistinctAggs(): Unit = {
    util.verifyRelPlan("SELECT COUNT(DISTINCT a), SUM(DISTINCT b) FROM MyTable")
  }

  @Test
  def testSingleMaxWithDistinctAgg(): Unit = {
    val sqlQuery =
      """
        |SELECT a, COUNT(DISTINCT b), MAX(c)
        |FROM MyTable
        |GROUP BY a
      """.stripMargin
    util.verifyRelPlan(sqlQuery)
  }

  @Test
  def testSingleFirstValueWithDistinctAgg(): Unit = {
    util.verifyRelPlan("SELECT a, FIRST_VALUE(c), COUNT(DISTINCT b) FROM MyTable GROUP BY a")
  }

  @Test
  def testSingleLastValueWithDistinctAgg(): Unit = {
    util.verifyRelPlan("SELECT a, LAST_VALUE(c), COUNT(DISTINCT b) FROM MyTable GROUP BY a")
  }

  @Test
  def testSingleListAggWithDistinctAgg(): Unit = {
    util.verifyRelPlan("SELECT a, LISTAGG(c), COUNT(DISTINCT b) FROM MyTable GROUP BY a")
  }

  @Test
  def testSingleDistinctAggWithAllNonDistinctAgg(): Unit = {
    val sqlQuery =
      """
        |SELECT a, COUNT(DISTINCT c), SUM(b), AVG(b), MAX(b), MIN(b), COUNT(b), COUNT(*)
        |FROM MyTable
        |GROUP BY a
      """.stripMargin
    util.verifyRelPlan(sqlQuery)
  }

  @Test
  def testSingleDistinctAggWithGroupBy(): Unit = {
    util.verifyRelPlan("SELECT a, COUNT(DISTINCT c) FROM MyTable GROUP BY a")
  }

  @Test
  def testSingleDistinctAggWithAndNonDistinctAggOnSameColumn(): Unit = {
    util.verifyRelPlan("SELECT a, COUNT(DISTINCT b), SUM(b), AVG(b) FROM MyTable GROUP BY a")
  }

  @Test
  def testSomeColumnsBothInDistinctAggAndGroupBy(): Unit = {
    // TODO: the COUNT(DISTINCT a) can be optimized to literal 1
    util.verifyRelPlan("SELECT a, COUNT(DISTINCT a), COUNT(b) FROM MyTable GROUP BY a")
  }

  @Test
  def testAggWithFilterClause(): Unit = {
    val sqlQuery =
      s"""
         |SELECT
         |  a,
         |  COUNT(DISTINCT b) FILTER (WHERE NOT b = 2),
         |  SUM(b) FILTER (WHERE NOT b = 5),
         |  SUM(b) FILTER (WHERE NOT b = 2)
         |FROM MyTable
         |GROUP BY a
       """.stripMargin
    util.verifyRelPlan(sqlQuery)
  }

  @Test
  def testMultiGroupBys(): Unit = {
    val sqlQuery =
      s"""
         |SELECT
         |  c, MIN(b), MAX(b), SUM(b), COUNT(*), COUNT(DISTINCT a)
         |FROM(
         |  SELECT
         |    a, AVG(b) as b, MAX(c) as c
         |  FROM MyTable
         |  GROUP BY a
         |) GROUP BY c
       """.stripMargin
    util.verifyRelPlan(sqlQuery)
  }

  @Test
  def testAggWithJoin(): Unit = {
    val sqlQuery =
      s"""
         |SELECT *
         |FROM(
         |  SELECT
         |    c, SUM(b) as b, SUM(b) as d, COUNT(DISTINCT a) as a
         |  FROM(
         |    SELECT
         |      a, COUNT(DISTINCT b) as b, SUM(b) as c, SUM(b) as d
         |    FROM MyTable
         |    GROUP BY a)
         |  GROUP BY c
         |) as MyTable1 JOIN MyTable ON MyTable1.b = MyTable.a
       """.stripMargin
    util.verifyRelPlan(sqlQuery)
  }

  @Test
  def testBucketsConfiguration(): Unit = {
    util.tableEnv.getConfig.set(
      OptimizerConfigOptions.TABLE_OPTIMIZER_DISTINCT_AGG_SPLIT_BUCKET_NUM,
      Integer.valueOf(100))
    val sqlQuery = "SELECT COUNT(DISTINCT c) FROM MyTable"
    util.verifyRelPlan(sqlQuery)
  }

  @Test
  def testMultipleDistinctAggOnSameColumn(): Unit = {
    util.tableEnv.getConfig
      .set(OptimizerConfigOptions.TABLE_OPTIMIZER_DISTINCT_AGG_SPLIT_ENABLED, Boolean.box(true))
    val sqlQuery =
      s"""
         |SELECT
         |  a,
         |  COUNT(DISTINCT b),
         |  COUNT(DISTINCT b) FILTER(WHERE b <> 5),
         |  SUM(b),
         |  AVG(b)
         |FROM MyTable
         |GROUP BY a
         |""".stripMargin
    util.verifyRelPlan(sqlQuery)
  }

  @Test
  def testAggFilterClauseBothWithAvgAndCount(): Unit = {
    util.tableEnv.getConfig
      .set(OptimizerConfigOptions.TABLE_OPTIMIZER_DISTINCT_AGG_SPLIT_ENABLED, Boolean.box(true))
    val sqlQuery =
      s"""
         |SELECT
         |  a,
         |  COUNT(DISTINCT b) FILTER (WHERE NOT b = 2),
         |  SUM(b) FILTER (WHERE NOT b = 5),
         |  COUNT(b),
         |  AVG(b),
         |  SUM(b)
         |FROM MyTable
         |GROUP BY a
         |""".stripMargin
    util.verifyRelPlan(sqlQuery)
  }
}
