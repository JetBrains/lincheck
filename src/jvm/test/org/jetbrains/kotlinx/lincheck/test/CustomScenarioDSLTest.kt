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
package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
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
