/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.lincheck.datastructures.scenario
import org.junit.*
import org.junit.Assert.*

class CustomScenarioDSLTest {
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
                actor(Object::hashCode)
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
                actor(Object::hashCode)
            }
            post {}
        }
    }

    @Test
    fun testAverageScenario() {
        val scenario = scenario {
            initial {
                repeat(2) { actor(Object::hashCode) }
            }
            parallel {
                repeat(2) {
                    thread {
                        repeat(5 + it) {
                            actor(Object::equals, this)
                        }
                    }
                }
            }
            post {
                repeat(3) { actor(Object::toString) }
            }
        }
        assertEquals(2, scenario.initExecution.size)
        assertEquals(3, scenario.postExecution.size)
        assertEquals(2, scenario.parallelExecution.size)
        assertEquals(5, scenario.parallelExecution[0].size)
        assertEquals(6, scenario.parallelExecution[1].size)
    }
}
