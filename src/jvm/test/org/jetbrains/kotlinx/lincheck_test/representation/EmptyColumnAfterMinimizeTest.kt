/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.jetbrains.lincheck.datastructures.*
import org.junit.Test
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * This test triggered a bug where a thread column in the trace representation was empty.
 * This was the result of scenario minimization, but columns were not removed when empty.
 */
@Param(name = "id", gen = IntGen::class, conf = "0:2")
@Param(name = "amount", gen = LongGen::class, conf = "1:10")
class EmptyColumnAfterMinimizeTest {
    private val accounts: Array<Number> = Array(3) { Number() }
    private val lock = ReentrantLock()


    @Operation
    fun getAmount(@Param(name = "id") id: Int): Long = lock.withLock {
        return accounts[id].value
    }

    @Operation
    fun deposit(@Param(name = "id") id: Int, @Param(name = "amount") amount: Long): Long = lock.withLock {
        require(amount > 0) { "Invalid amount: $amount" }
        val account = accounts[id]
        account.value += amount
        return account.value
    }

    @Operation
    fun withdraw(@Param(name = "id") id: Int, @Param(name = "amount") amount: Long): Long {
        val account = accounts[id]
        check(account.value - amount >= 0) { "Underflow" }
        lock.withLock {
            account.value -= amount
            return account.value
        }
    }

    @Operation
    fun transfer(@Param(name = "id") fromId: Int, @Param(name = "id") toId: Int, @Param(name = "amount") amount: Long) = lock.withLock {
        val from = accounts[fromId]
        val to = accounts[toId]
        check(amount <= from.value) { "Underflow" }
        from.value -= amount
        to.value += amount
    }

    class Number { var value: Long = 0 }
    
    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .iterations(50)
        .invocationsPerIteration(50)
        .actorsBefore(1)
        .threads(3)
        .actorsPerThread(2)
        .actorsAfter(0)
        .checkObstructionFreedom(false)
        .checkImpl(this::class.java) { failure ->
            failure.checkLincheckOutput("empty_column_after_minimize")
        }
}