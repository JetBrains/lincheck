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

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

class BlockingOperationTest {
    @Operation(blocking = true)
    fun blocking(): Unit = synchronized(this) {}

    @Test
    fun test() = ModelCheckingOptions()
        .checkObstructionFreedom()
        .verifier(EpsilonVerifier::class.java)
        .actorsBefore(0)
        .actorsAfter(0)
        .check(this::class)
}

class CausesBlockingOperationTest {
    private val counter = atomic(0)

    @Operation(blocking = true)
    fun operation() {
        while (counter.value % 2 != 0) {}
    }

    @Operation
    fun causesBlocking() {
        counter.incrementAndGet()
        counter.incrementAndGet()
    }

    @Test
    fun test() = ModelCheckingOptions()
        .checkObstructionFreedom()
        .verifier(EpsilonVerifier::class.java)
        .iterations(20)
        .actorsBefore(0)
        .actorsAfter(0)
        .check(this::class)
}