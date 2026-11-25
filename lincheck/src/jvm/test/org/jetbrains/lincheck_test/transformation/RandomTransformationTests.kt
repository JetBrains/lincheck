/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.transformation

import org.jetbrains.lincheck.Lincheck.runConcurrentTest
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.*
import java.util.*
import java.util.concurrent.*

class JavaRandomTest : AbstractRandomTest() {
    override fun nextInt() = Random().nextInt()
}

class JavaThreadLocalRandomTest : AbstractRandomTest() {
    override fun nextInt() = ThreadLocalRandom.current().nextInt()
}

class JavaThreadLocalRandomTest2 : AbstractRandomTest() {
    override fun nextInt() = ThreadLocalRandom.current().nextInt(10, 100)
}

class KotlinRandomTest : AbstractRandomTest() {
    override fun nextInt() = kotlin.random.Random.nextInt()
}

abstract class AbstractRandomTest {
    var a: Int = 0

    abstract fun nextInt(): Int

    @Operation
    fun operation() {
        a++
        if (nextInt() % 2 == 0) {
            a++
        }
    }

    @Test
    fun test() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 1
            operationsBefore = 0
            operationsAfter = 0
            threads = 3
            operationsPerThread = 1
        }
    }, this::class)
}
