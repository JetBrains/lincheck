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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.scenario
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
        val failure = StressOptions()
            .iterations(0)
            .addCustomScenario(scenario {
                parallel {
                    thread { actor(::t1) }
                    thread { actor(::t2) }
                }
            })
            .checkImpl(this::class.java)
        assert(failure is IncorrectResultsFailure)
    }

    @Test
    fun stressTest2() {
        val failure = StressOptions()
            .iterations(0)
            .addCustomScenario {
                parallel {
                    thread { actor(::t1) }
                    thread { actor(::t2) }
                }
            }
            .checkImpl(this::class.java)
        assert(failure is IncorrectResultsFailure)
    }
}