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
package org.apache.flink.table.planner.plan.stream.table.validation

import org.apache.flink.table.api._
import org.apache.flink.table.planner.plan.utils.WindowEmitStrategy.{TABLE_EXEC_EMIT_EARLY_FIRE_DELAY, TABLE_EXEC_EMIT_EARLY_FIRE_ENABLED}
import org.apache.flink.table.planner.runtime.utils.JavaUserDefinedAggFunctions.WeightedAvgWithMerge
import org.apache.flink.table.planner.utils.{TableTestBase, Top3}

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

import java.time.Duration

class GroupWindowTableAggregateValidationTest extends TableTestBase {

  val top3 = new Top3
  val weightedAvg = new WeightedAvgWithMerge
  val util = streamTestUtil()
  val table = util
    .addTableSource[(Long, Int, String)]('long, 'int, 'string, 'rowtime.rowtime, 'proctime.proctime)

  @Test
  def testTumbleUdAggWithInvalidArgs(): Unit = {
    assertThatThrownBy(
      () =>
        table
          .window(Slide.over(2.hours).every(30.minutes).on('rowtime).as('w))
          .groupBy('string, 'w)
          .flatAggregate(call(top3, 'long)) // invalid args
          .select('string, 'f0))
      .hasMessageContaining("Invalid function call:\nTop3(BIGINT)")
      .isInstanceOf[ValidationException]
  }

  @Test
  def testInvalidStarInSelection(): Unit = {
    val util = streamTestUtil()
    val table = util.addTableSource[(Long, Int, String)]('long, 'int, 'string, 'proctime.proctime)

    assertThatThrownBy(
      () =>
        table
          .window(Tumble.over(2.rows).on('proctime).as('w))
          .groupBy('string, 'w)
          .flatAggregate(top3('int))
          .select('*))
      .hasMessageContaining("Can not use * for window aggregate!")
      .isInstanceOf[ValidationException]
  }

  @Test
  def testEmitStrategyNotSupported(): Unit = {
    val util = streamTestUtil()
    val table = util.addTableSource[(Long, Int, String)]('long, 'int, 'string, 'proctime.proctime)

    val tableConf = util.getTableEnv.getConfig
    tableConf.set(TABLE_EXEC_EMIT_EARLY_FIRE_ENABLED, Boolean.box(true))
    tableConf.set(TABLE_EXEC_EMIT_EARLY_FIRE_DELAY, Duration.ofMillis(10))

    val result = table
      .window(Tumble.over(2.hours).on('proctime).as('w))
      .groupBy('string, 'w)
      .flatAggregate(top3('int))
      .select('string, 'f0, 'w.start)

    assertThatThrownBy(() => util.verifyExecPlan(result))
      .hasMessageContaining("Emit strategy has not been supported for Table Aggregate!")
      .isInstanceOf[TableException]
  }
}
