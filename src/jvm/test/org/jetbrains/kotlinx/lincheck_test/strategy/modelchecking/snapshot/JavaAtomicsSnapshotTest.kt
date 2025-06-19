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

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedOptions
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.random.Random


class JavaAtomicsSnapshotTest : AbstractSnapshotTest() {
    companion object {
        private class Wrapper(var x: Int)

        private var atomicInt = AtomicInteger(1)
        private var atomicRef = AtomicReference<Wrapper>(Wrapper(1))
        private var atomicIntArray = AtomicIntegerArray(3).apply { for (i in 0..length() - 1) set(i, i + 1); }
        private var atomicRefArray = AtomicReferenceArray<Wrapper>(3).apply { for (i in 0..length() - 1) set(i, Wrapper(i + 1)); }

        // remember values to restore
        private var intRef: AtomicInteger = atomicInt
        private var intValue: Int = atomicInt.get()
        private var refRef: AtomicReference<Wrapper> = atomicRef
        private var refValue: Wrapper = atomicRef.get()
        private var intArrayRef: AtomicIntegerArray = atomicIntArray
        private var refArrayRef: AtomicReferenceArray<Wrapper> = atomicRefArray
        private var refArrayValues: List<Wrapper> = mutableListOf<Wrapper>().apply { for (i in 0..atomicRefArray.length() - 1) add(atomicRefArray.get(i)) }
    }

    class StaticObjectVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(atomicInt == intRef)
            check(atomicInt.get() == intValue)

            check(atomicRef == refRef)
            check(atomicRef.get() == refValue)
            check(atomicRef.get().x == 1)

            check(atomicIntArray == intArrayRef)
            for (i in 0..atomicIntArray.length() - 1) {
                check(atomicIntArray.get(i) == i + 1)
            }

            check(atomicRefArray == refArrayRef)
            for (i in 0..atomicRefArray.length() - 1) {
                val wrapper = atomicRefArray.get(i)
                check(wrapper == refArrayValues[i])
                check(wrapper.x == i + 1)
            }
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(StaticObjectVerifier::class.java)
        threads(1)
        iterations(100)
        invocationsPerIteration(1)
        actorsPerThread(10)
    }

    @Operation
    fun modifyInt() {
        atomicInt.getAndIncrement()
    }

    @Operation
    fun modifyRef() {
        atomicRef.set(Wrapper(atomicInt.get() + 1))
    }

    @Operation
    fun modifyIntArray() {
        atomicIntArray.getAndIncrement(Random.nextInt(0, atomicIntArray.length()))
    }

    @Operation
    fun modifyRefArray() {
        atomicRefArray.set(Random.nextInt(0, atomicRefArray.length()), Wrapper(Random.nextInt()))
    }

    @Operation
    fun modifyRefArrayValues() {
        atomicRefArray.get(Random.nextInt(0, atomicRefArray.length())).x = Random.nextInt()
    }
}