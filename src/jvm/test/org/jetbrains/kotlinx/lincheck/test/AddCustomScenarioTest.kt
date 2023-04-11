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
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*

class AddCustomScenarioTest {
    var x = 0
    var y = 0

    fun t1(): Int {
        x = 1
        return y
    }

    fun t2(): Int {
        y = 1
        return x
    }

    @Test
    fun stressTest1() {
        val failure = LincheckOptions {
            this as LincheckOptionsImpl
            generateScenarios = false
            addCustomScenario {
                parallel {
                    thread { actor(::t1) }
                    thread { actor(::t2) }
                }
            }
        }.checkImpl(this::class.java)
        assert(failure is IncorrectResultsFailure)
    }

    @Test
    fun stressTest2() {
        val failure = LincheckOptions {
            this as LincheckOptionsImpl
            generateScenarios = false
            addCustomScenario {
                parallel {
                    thread { actor(::t1) }
                    thread { actor(::t2) }
                }
            }
        }.checkImpl(this::class.java)
        assert(failure is IncorrectResultsFailure)
    }
}