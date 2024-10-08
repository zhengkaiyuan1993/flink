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
import org.apache.flink.table.planner.plan.optimize.program.{BatchOptimizeContext, FlinkChainedProgram, FlinkHepRuleSetProgramBuilder, HEP_RULES_EXECUTION_TYPE}
import org.apache.flink.table.planner.utils.TableTestBase

import org.apache.calcite.plan.hep.HepMatchOrder
import org.apache.calcite.tools.RuleSets
import org.junit.jupiter.api.{BeforeEach, Test}

/** Test for [[RewriteMinusAllRule]]. */
class RewriteMinusAllRuleTest extends TableTestBase {

  private val util = batchTestUtil()

  @BeforeEach
  def setup(): Unit = {
    val programs = new FlinkChainedProgram[BatchOptimizeContext]()
    programs.addLast(
      "rules",
      FlinkHepRuleSetProgramBuilder.newBuilder
        .setHepRulesExecutionType(HEP_RULES_EXECUTION_TYPE.RULE_SEQUENCE)
        .setHepMatchOrder(HepMatchOrder.BOTTOM_UP)
        .add(RuleSets.ofList(RewriteMinusAllRule.INSTANCE))
        .build()
    )
    util.replaceBatchProgram(programs)

    util.addTableSource[(Int, Long, String)]("T1", 'a, 'b, 'c)
    util.addTableSource[(Int, Long, String)]("T2", 'd, 'e, 'f)
  }

  @Test
  def testExceptAll(): Unit = {
    util.verifyRelPlan("SELECT c FROM T1 EXCEPT ALL SELECT f FROM T2")
  }

  @Test
  def testExceptAllWithFilter(): Unit = {
    util.verifyRelPlan("SELECT c FROM (SELECT * FROM T1 EXCEPT ALL (SELECT * FROM T2)) WHERE b < 2")
  }

  @Test
  def testExceptAllLeftIsEmpty(): Unit = {
    util.verifyRelPlan("SELECT c FROM T1 WHERE 1=0 EXCEPT ALL SELECT f FROM T2")
  }

  @Test
  def testExceptAllRightIsEmpty(): Unit = {
    util.verifyRelPlan("SELECT c FROM T1 EXCEPT ALL SELECT f FROM T2 WHERE 1=0")
  }
}
