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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedOptions
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking.snapshot.SetSnapshotTest.Companion.Wrapper
import kotlin.random.Random


abstract class CollectionSnapshotTest : AbstractSnapshotTest() {

    override fun testModelChecking() = ModelCheckingOptions()
        .actorsBefore(0)
        .actorsAfter(0)
        .iterations(100)
        .invocationsPerIteration(1)
        .threads(1)
        .actorsPerThread(10)
        .restoreStaticMemory(true)
        .apply { customize() }
        .check(this::class)

    @Operation
    open fun addElement() {}

    @Operation
    open fun removeElement() {}

    @Operation
    open fun updateElement() {}

    @Operation
    open fun clear() {}

    @Operation
    open fun reassign() {}
}

class SetSnapshotTest : CollectionSnapshotTest() {
    companion object {
        private class Wrapper(var value: Int)
        private var staticSet = mutableSetOf<Wrapper>()

        // remember values for restoring
        private val ref = staticSet
        private val a = Wrapper(1)
        private val b = Wrapper(2)
        private val c = Wrapper(3)
        init {
            staticSet.addAll(listOf(a, b, c))
        }
    }

    class SetVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(staticSet == ref)
            check(staticSet.size == 3 && staticSet.containsAll(listOf(a, b, c)))
            check(a.value == 1 && b.value == 2 && c.value == 3)
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(SetVerifier::class.java)
    }

    @Operation
    override fun addElement() {
        staticSet.add(Wrapper(Random.nextInt()))
    }

    @Operation
    override fun removeElement() {
        staticSet.remove(staticSet.randomOrNull() ?: return)
    }

    @Operation
    override fun updateElement() {
        staticSet.randomOrNull()?.value = Random.nextInt()
    }

    @Operation
    override fun clear() {
        staticSet.clear()
    }

    @Operation
    override fun reassign() {
        staticSet = mutableSetOf<Wrapper>()
    }
}