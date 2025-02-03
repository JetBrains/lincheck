/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.guide

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.junit.jupiter.api.*
import java.util.concurrent.*
import kotlin.test.assertFailsWith

class ConcurrentHashMapTest {
    private val map = ConcurrentHashMap<Int, Int>()

    @Operation
    public fun put(key: Int, value: Int) = map.put(key, value)

    @Test
    fun modelCheckingTest() {
        @Suppress("UNUSED_VARIABLE")
        val error = assertFailsWith<AssertionError> {
            ModelCheckingOptions()
                .actorsBefore(1)
                .actorsPerThread(1)
                .actorsAfter(0)
                .minimizeFailedScenario(false)
                .checkObstructionFreedom(true)
                .check(this::class)
        }
        // throw error // TODO: Please, uncomment me to run the test and get the output
    }
}

class ConcurrentSkipListMapTest {
    private val map = ConcurrentSkipListMap<Int, Int>()

    @Operation
    public fun put(key: Int, value: Int) = map.put(key, value)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .check(this::class)
}
