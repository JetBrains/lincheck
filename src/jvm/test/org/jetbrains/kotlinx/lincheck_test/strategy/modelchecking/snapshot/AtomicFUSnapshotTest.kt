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

import kotlinx.atomicfu.AtomicIntArray
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.atomicArrayOfNulls
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedOptions
import kotlin.random.Random

class AtomicFUSnapshotTest : AbstractSnapshotTest() {
    companion object {
        private class Wrapper(var x: Int)

        // TODO: atomicfu classes like AtomicInt, AtomicRef are compiled to pure field + java atomic field updater.
        //  Because of this, methods, such as `AtomicInt::getAndIncrement()`, will not be tracked as modification of `value` field.
        //  Tracking of atomicfu classes should be implemented eagerly, the same way as for java atomics
        //  in order to handle modification that do not reference `value` field directly.
        //  If eager traversal does not help/not possible to reach `value` field, then such indirect methods should be handled separately,
        //  the same way as reflexivity, var-handles, and unsafe by tracking the creation of java afu and retrieving the name of the associated field.
        private val atomicFUInt = atomic(1)
        private val atomicFURef = atomic<Wrapper>(Wrapper(1))

        private val atomicFUIntArray = AtomicIntArray(3)
        private val atomicFURefArray = atomicArrayOfNulls<Wrapper>(3)

        init {
            for (i in 0..atomicFUIntArray.size - 1) {
                atomicFUIntArray[i].value = i + 1
            }

            for (i in 0..atomicFURefArray.size - 1) {
                atomicFURefArray[i].value = Wrapper(i + 1)
            }
        }

        // remember values to restore
        private val atomicFURefValue = atomicFURef.value
        private val atomicFUIntValues: List<Int> = mutableListOf<Int>().apply {
            for (i in 0..atomicFUIntArray.size - 1) {
                add(atomicFUIntArray[i].value)
            }
        }
        private val atomicFURefArrayValues: List<Wrapper> = mutableListOf<Wrapper>().apply {
            for (i in 0..atomicFURefArray.size - 1) {
                add(atomicFURefArray[i].value!!)
            }
        }
    }

    class AtomicFUSnapshotVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)

            check(atomicFUInt.value == 1)

            check(atomicFURef.value == atomicFURefValue)
            check(atomicFURef.value.x == 1)

            for (i in 0..atomicFUIntArray.size - 1) {
                check(atomicFUIntArray[i].value == atomicFUIntValues[i])
            }

            for (i in 0..atomicFURefArray.size - 1) {
                check(atomicFURefArray[i].value == atomicFURefArrayValues[i])
                check(atomicFURefArray[i].value!!.x == i + 1)
            }

            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(AtomicFUSnapshotVerifier::class.java)
        threads(1)
        iterations(100)
        invocationsPerIteration(1)
        actorsPerThread(10)
    }

    @Operation
    fun modifyAtomicFUInt() {
        // TODO: `atomicFUInt.incrementAndGet()` this is not recognized as `value` field modification right now
        atomicFUInt.value = Random.nextInt()
    }

    @Operation
    fun modifyAtomicFURef() {
        atomicFURef.value = Wrapper(Random.nextInt())
    }

    @Operation
    fun modifyAtomicFURefValue() {
        atomicFURef.value.x = Random.nextInt()
    }

    @Operation
    fun modifyAtomicFUIntArray() {
        atomicFUIntArray[Random.nextInt(0, atomicFUIntArray.size)].value = Random.nextInt()
    }

    @Operation
    fun modifyAtomicFURefArray() {
        atomicFURefArray[Random.nextInt(0, atomicFURefArray.size)].value = Wrapper(Random.nextInt())
    }

    @Operation
    fun modifyAtomicFURefArrayValues() {
        atomicFURefArray[Random.nextInt(0, atomicFURefArray.size)].value!!.x = Random.nextInt()
    }
}