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
import kotlin.ranges.until

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
        private val atomicFURef = atomic(Wrapper(1))

        private const val ARRAY_SIZE = 3

        private val atomicFUIntArray = AtomicIntArray(ARRAY_SIZE)
        private val atomicFURefArray = atomicArrayOfNulls<Wrapper>(ARRAY_SIZE)

        init {
            for (i in 0 until ARRAY_SIZE) {
                atomicFUIntArray[i].value = i + 1
            }

            for (i in 0 until ARRAY_SIZE) {
                atomicFURefArray[i].value = Wrapper(i + 1)
            }
        }

        // remember values to restore
        private val atomicFURefValue = atomicFURef.value
        private val atomicFUIntValues: List<Int> = mutableListOf<Int>().apply {
            for (i in 0 until ARRAY_SIZE) {
                add(atomicFUIntArray[i].value)
            }
        }
        private val atomicFURefArrayValues: List<Wrapper> = mutableListOf<Wrapper>().apply {
            for (i in 0 until ARRAY_SIZE) {
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

            for (i in 0 until ARRAY_SIZE) {
                check(atomicFUIntArray[i].value == atomicFUIntValues[i])
            }

            for (i in 0 until ARRAY_SIZE) {
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
        atomicFUIntArray[Random.nextInt(0, ARRAY_SIZE)].value = Random.nextInt()
    }

    @Operation
    fun modifyAtomicFURefArray() {
        atomicFURefArray[Random.nextInt(0, ARRAY_SIZE)].value = Wrapper(Random.nextInt())
    }

    @Operation
    fun modifyAtomicFURefArrayValues() {
        atomicFURefArray[Random.nextInt(0, ARRAY_SIZE)].value!!.x = Random.nextInt()
    }
}

class ImplicitAtomicFUSnapshotTest : AbstractSnapshotTest() {
    companion object {
        private class Wrapper(var x: Int)

        private val atomicFUInt = kotlinx.atomicfu.atomic(1)
        private val atomicFURef = kotlinx.atomicfu.atomic<Wrapper>(Wrapper(1))

        private const val ARRAY_SIZE = 3

        private val atomicFUIntArray = kotlinx.atomicfu.AtomicIntArray(ARRAY_SIZE)
        private val atomicFURefArray = kotlinx.atomicfu.atomicArrayOfNulls<Wrapper>(ARRAY_SIZE)

        init {
            for (i in 0 until ARRAY_SIZE) {
                atomicFUIntArray[i].value = i + 1
            }

            for (i in 0 until ARRAY_SIZE) {
                atomicFURefArray[i].value = Wrapper(i + 1)
            }
        }

        // remember values to restore
        private val atomicFURefValue = atomicFURef.value
        private val atomicFUIntValues: List<Int> = mutableListOf<Int>().apply {
            for (i in 0 until ARRAY_SIZE) {
                add(atomicFUIntArray[i].value)
            }
        }
        private val atomicFURefArrayValues: List<Wrapper> = mutableListOf<Wrapper>().apply {
            for (i in 0 until ARRAY_SIZE) {
                add(atomicFURefArray[i].value!!)
            }
        }
    }

    class ImplicitAtomicFUSnapshotVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)

            check(atomicFUInt.value == 1)

            check(atomicFURef.value == atomicFURefValue)
            check(atomicFURef.value.x == 1)

            for (i in 0 until ARRAY_SIZE) {
                check(atomicFUIntArray[i].value == atomicFUIntValues[i])
            }

            for (i in 0 until ARRAY_SIZE) {
                check(atomicFURefArray[i].value == atomicFURefArrayValues[i])
                check(atomicFURefArray[i].value!!.x == i + 1)
            }

            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(ImplicitAtomicFUSnapshotVerifier::class.java)
        threads(1)
        iterations(100)
        invocationsPerIteration(1)
        actorsPerThread(10)
    }

    @Operation
    fun getAndSetAtomicFUInt() {
        atomicFUInt.getAndSet(Random.nextInt())
    }

    @Operation
    fun compareAndSetAtomicFUInt() {
        atomicFUInt.compareAndSet(atomicFUInt.value, Random.nextInt())
    }

    @Operation
    fun getAndIncrementAtomicFUInt() {
        atomicFUInt.getAndIncrement()
    }

    @Operation
    fun getAndSetAtomicFURef() {
        atomicFURef.getAndSet(Wrapper(Random.nextInt()))
    }

    @Operation
    fun compareAndSetAtomicFURef() {
        atomicFURef.compareAndSet(atomicFURef.value, Wrapper(Random.nextInt()))
    }

    @Operation
    fun incrementAndGetAtomicFUIntArray() {
        atomicFUIntArray[Random.nextInt(0, ARRAY_SIZE)].incrementAndGet()
    }

    @Operation
    fun decrementAndGetAtomicFUIntArray() {
        atomicFUIntArray[Random.nextInt(0, ARRAY_SIZE)].decrementAndGet()
    }

    @Operation
    fun compareAndSetAtomicFUIntArray() {
        val idx = Random.nextInt(0, ARRAY_SIZE)
        atomicFUIntArray[idx].compareAndSet(atomicFUIntArray[idx].value, Random.nextInt())
    }

    @Operation
    fun getAndSetAtomicFURefArray() {
        atomicFURefArray[Random.nextInt(0, ARRAY_SIZE)].getAndSet(Wrapper(Random.nextInt()))
    }

    @Operation
    fun compareAndSetAtomicFURefArray() {
        val idx = Random.nextInt(0, ARRAY_SIZE)
        atomicFURefArray[idx].compareAndSet(atomicFURefArray[idx].value, Wrapper(Random.nextInt()))
    }
}