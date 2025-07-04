/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.*

@Suppress("UNUSED_PARAMETER")
class SingletonCollectionInTraceTest {
    private var x = 0

    fun useList(set: List<Int>) {
        x = 1
    }

    fun useSet(set: Set<Int>) {
        x = 2
    }

    fun useMap(set: Map<Int, Int>) {
        x = 3
    }

    @Operation(runOnce = true)
    fun op() {
        useList(listOf(1))
        useSet(setOf(1))
        useMap(mapOf(1 to 1))
        error("fail")
    }

    @Test
    fun modelCheckingTest() {
        val failure = ModelCheckingOptions()
            .iterations(1)
            .checkImpl(this::class.java)
        val message = failure.toString()
        assert("SingletonList" !in message)
        assert("SingletonSet" !in message)
        assert("SingletonMap" !in message)
    }
}