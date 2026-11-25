/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.guide

import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

class ConcurrentHashMapTest {
    private val map = ConcurrentHashMap<Int, Int>()

    @Operation
    public fun put(key: Int, value: Int) = map.put(key, value)

    //@Test // TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun modelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings {
        modelChecking { checkObstructionFreedom = true }
        scenarioGeneration {
            actorsBefore = 1
            actorsPerThread = 1
            actorsAfter = 0
            minimizeFailedScenario = false
        }
    }, this::class)
}

class ConcurrentSkipListMapTest {
    private val map = ConcurrentSkipListMap<Int, Int>()

    @Operation
    public fun put(key: Int, value: Int) = map.put(key, value)

    @Test
    fun modelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings { commonModelChecking() }, this::class)

    @Ignore("Times out")
    @Test
    fun eventStrcutureModelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings {
        commonModelChecking()
        modelChecking { enablePartialOrderReduction() }
    }, this::class)

    private fun LincheckSettings.commonModelChecking() = modelChecking {
        analyzeStdLib = true
        checkObstructionFreedom = true
        loopDetection { loopIterationsBeforeThreadSwitch = 20 }
    }
}
