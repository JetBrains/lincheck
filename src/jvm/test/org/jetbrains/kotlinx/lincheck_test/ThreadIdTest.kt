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
import org.jetbrains.lincheck.datastructures.*
import org.jetbrains.kotlinx.lincheck.paramgen.*

class ThreadIdTest : AbstractLincheckTest() {
    private val balances = IntArray(5)
    private val counter = atomic(0)

    @Operation
    fun inc(@Param(gen = ThreadIdGen::class) threadId: Int): Int = counter.incrementAndGet()
        .also { balances[threadId]++ }

    @Operation
    fun decIfNotNegative(@Param(gen = ThreadIdGen::class) threadId: Int) {
        if (balances[threadId] == 0) return
        balances[threadId]--
        val c = counter.decrementAndGet()
        if (c < 0) error("The counter cannot be negative")
    }
}
