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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.dsl.scenario
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.test.util.assertScenariosEquals
import org.jetbrains.kotlinx.lincheck.test.util.getMethod
import org.jetbrains.kotlinx.lincheck.test.util.getSuspendMethod
import org.junit.*
import org.junit.Assert.*
import java.lang.reflect.Method

@Suppress("unused", "RedundantSuspendModifier")
class CustomScenarioKotlinDSLTest {

    private val suspendableOperation: Method = getSuspendMethod("suspendableOperation", 0)
    private val suspendableOperationWithOneArg: Method = getSuspendMethod("suspendableOperation", 1)
    private val suspendableOperationWithTwoArgs: Method = getSuspendMethod("suspendableOperation", 2)

    private val regularOperation: Method = getMethod("regularOperation", 0)
    private val regularOperationOneArg: Method = getMethod("regularOperation", 1)
    private val regularOperationTwoArgs: Method = getMethod("regularOperation", 2)

    @Test
    fun testMinimalScenario() {
        val scenario = scenario {}
        assertEquals(0, scenario.initExecution.size)
        assertEquals(0, scenario.parallelExecution.size)
        assertEquals(0, scenario.postExecution.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInitialPartRedeclaration() {
        scenario {
            initial {}
            initial {
                actor(::hashCode)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testParallelPartRedeclaration() {
        scenario {
            parallel {}
            parallel {}
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testPostPartRedeclaration() {
        scenario {
            post {
                actor(::hashCode)
            }
            post {}
        }
    }

    @Test
    fun testAverageScenario() {
        val scenario = scenario {
            initial {
                repeat(2) { actor(::hashCode) }
            }
            parallel {
                repeat(2) {
                    thread {
                        repeat(5 + it) {
                            actor(::equals, this)
                        }
                    }
                }
            }
            post {
                repeat(3) { actor(::toString) }
            }
        }
        assertEquals(2, scenario.initExecution.size)
        assertEquals(3, scenario.postExecution.size)
        assertEquals(2, scenario.parallelExecution.size)
        assertEquals(5, scenario.parallelExecution[0].size)
        assertEquals(6, scenario.parallelExecution[1].size)
    }

    @Test
    fun `should build scenario with suspend and regular actors`() {
        val scenario = scenario {
            post {
                actor(::regularOperation, 2, "123")
                actor(::suspendableOperation, 6, 7)
            }
        }

        val expectedScenario = ExecutionScenario(
            emptyList(), emptyList(), listOf(
                Actor(regularOperationTwoArgs, listOf(2, "123")),
                Actor(suspendableOperationWithTwoArgs, listOf(6, 7), cancelOnSuspension = true)
            )
        )

        assertScenariosEquals(expectedScenario, scenario)
    }

    @Test
    fun `should build scenario with suspend and regular operations with same params count and name`() {
        val scenario = scenario {
            initial {
                actor(::regularOperation, 1, "2")
                actor(::suspendableOperation, 3, "4")
            }
        }
        val expectedScenario = ExecutionScenario(
            listOf(
                Actor(regularOperationTwoArgs, listOf(1, "2")),
                Actor(suspendableOperationWithTwoArgs, listOf(3, "4"), cancelOnSuspension = true)
            ), emptyList(), emptyList()
        )

        assertScenariosEquals(expectedScenario, scenario)
    }

    @Test
    fun `should build scenario with suspend operations overloads`() {
        val scenario = scenario {
            initial {
                actor(::suspendableOperation, 1)
                actor(::suspendableOperation)
            }
            parallel {
                thread {
                    actor(::suspendableOperation)
                    actor(::suspendableOperation, 1, 2)
                }
            }
            post {
                actor(::suspendableOperation)
            }
        }

        val expectedScenario = ExecutionScenario(
            listOf(
                Actor(suspendableOperationWithOneArg, listOf(1), cancelOnSuspension = true),
                Actor(suspendableOperation, emptyList(), cancelOnSuspension = true)
            ), listOf(
                listOf(
                    Actor(suspendableOperation, emptyList(), cancelOnSuspension = true),
                    Actor(suspendableOperationWithTwoArgs, listOf(1, 2), cancelOnSuspension = true)
                )
            ), listOf(
                Actor(suspendableOperation, emptyList(), cancelOnSuspension = true)
            )
        )

        assertScenariosEquals(expectedScenario, scenario)
    }

    @Test
    fun `should build scenario with regular operations overloads`() {
        val scenario = scenario {
            initial {
                actor(::regularOperation, 1)
                actor(::regularOperation)
            }
            parallel {
                thread {
                    actor(::regularOperation)
                    actor(::regularOperation, 1, 2)
                }
            }
            post {
                actor(::regularOperation)
            }
        }

        val expectedScenario = ExecutionScenario(
            listOf(
                Actor(regularOperationOneArg, listOf(1)),
                Actor(regularOperation, emptyList())
            ), listOf(
                listOf(
                    Actor(regularOperation, emptyList()), Actor(regularOperationTwoArgs, listOf(1, 2))
                )
            ), listOf(
                Actor(regularOperation, emptyList())
            )
        )

        assertScenariosEquals(expectedScenario, scenario)
    }

    @Test
    fun `should inherit actor parameters from annotation`() {
        val scenario = scenario {
            initial {
                actor(::operationWithManySettings, 1, 2)
            }
        }

        val actor = scenario.initExecution.first()
        val expectedActor = Actor(
            getSuspendMethod("operationWithManySettings", 2),
            listOf(1, 2),
            cancelOnSuspension = true,
            blocking = true,
            allowExtraSuspension = true,
            causesBlocking = true,
            promptCancellation = true
        )

        assertEquals(expectedActor, actor)
    }

    @Operation(
        cancellableOnSuspension = false
    )
    @Suppress("unused")
    fun regularOperation() = Unit

    @Operation(
        cancellableOnSuspension = false
    )
    @Suppress("unused")
    fun regularOperation(arg1: Int) = Unit

    @Operation(
        cancellableOnSuspension = false
    )
    @Suppress("unused")
    fun regularOperation(arg1: Int, arg2: String) = Unit

    @Operation
    suspend fun suspendableOperation() = Unit

    @Operation
    suspend fun suspendableOperation(argument: Int) = Unit

    @Operation
    suspend fun suspendableOperation(argument: Int, anotherArgument: String) = Unit

    @Operation(
        cancellableOnSuspension = true,
        blocking = true,
        allowExtraSuspension = true,
        causesBlocking = true,
        promptCancellation = true
    )
    suspend fun operationWithManySettings(argument: Int, anotherArgument: String) = Unit

}