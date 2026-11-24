/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.datastructures.linearizable

import org.jetbrains.lincheck_test.AbstractLincheckIntegrationTest
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Param
import java.util.concurrent.ConcurrentHashMap

@Param(name = "key", gen = IntGen::class, conf = "1:5")
class ConcurrentHashMapTest : AbstractLincheckIntegrationTest() {
    private val map = ConcurrentHashMap<Int, Int>()

    @Operation
    fun put(@Param(name = "key") key: Int, value: Int) = map.put(key, value)

    @Operation
    operator fun get(@Param(name = "key") key: Int) = map[key]

    @Operation
    fun remove(@Param(name = "key") key: Int) = map.remove(key)

    override fun LincheckSettings.customize() {
        // TODO: This times out, we need to fix performance
        ignoreExperimentalModelChecking()
        // To obtain rare interleaving with `fullAddCount` method
        if (strategy is TestingStrategy.ModelChecking) {
            scenarioGeneration.invocationsPerIteration(10000)
            (strategy as TestingStrategy.ModelChecking).analyzeStdLib(true)
        }
    }
}
