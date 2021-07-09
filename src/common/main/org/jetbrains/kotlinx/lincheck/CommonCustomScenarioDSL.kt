/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.*

expect class ScenarioBuilder() {
    fun buildScenario(): ExecutionScenario
}

/**
 * Creates a custom scenario using the specified DSL as in the following example:
 *
 * ```
 * scenario {
 *   initial {
 *     actor(QueueTest::offer, 1)
 *     actor(QueueTest::offer, 2)
 *   }
 *   parallel {
 *     thread {
 *       actor(QueueTest::poll)
 *     }
 *     thread {
 *       actor(QueueTest::poll)
 *     }
 *   }
 * }
 * ```
 */
fun scenario(block: ScenarioBuilder.() -> Unit): ExecutionScenario =
        ScenarioBuilder().apply(block).buildScenario()
