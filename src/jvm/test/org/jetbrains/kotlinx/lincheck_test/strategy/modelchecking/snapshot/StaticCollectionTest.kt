/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking.snapshot

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import kotlin.random.Random

private class Wrapper(var value: Int)
private var staticSet = mutableSetOf<Wrapper>()

class StaticCollectionTest : SnapshotAbstractTest() {
    companion object {
        private val ref = staticSet
        private val a = Wrapper(1)
        private val b = Wrapper(2)
        private val c = Wrapper(3)
        init {
            staticSet.addAll(listOf(a, b, c))
        }
    }

    class StaticCollectionVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(staticSet == ref)
            check(staticSet.size == 3 && staticSet.containsAll(listOf(a, b, c)))
            check(a.value == 1 && b.value == 2 && c.value == 3)
            return true
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(100)
        if (this is ModelCheckingOptions) invocationsPerIteration(1)
        threads(1)
        actorsPerThread(10)
        verifier(StaticCollectionVerifier::class.java)
    }

    @Operation
    fun addElement() {
        staticSet.add(Wrapper(Random.nextInt()))
    }

    @Operation
    fun removeElement() {
        staticSet.remove(staticSet.randomOrNull() ?: return)
    }

    @Operation
    fun updateElement() {
        staticSet.randomOrNull()?.value = Random.nextInt()
    }

    @Operation
    fun clear() {
        staticSet.clear()
    }
}