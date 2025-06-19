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
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import kotlin.random.Random


class VarHandleModificationsSnapshotTest : AbstractSnapshotTest() {
    private class Wrapper(var x: Int)
    companion object {
        private var value = 1
        private var ref = Wrapper(1)
        private var intArray = intArrayOf(1, 2, 3)

        // remember values for restoring
        private val initRef = ref
        private val initIntArray = intArray
    }

    val valueHandle: VarHandle = MethodHandles.lookup()
        .`in`(VarHandleModificationsSnapshotTest::class.java)
        .findStaticVarHandle(VarHandleModificationsSnapshotTest::class.java, "value", Int::class.java)

    val refHandle: VarHandle = MethodHandles.lookup()
        .`in`(VarHandleModificationsSnapshotTest::class.java)
        .findStaticVarHandle(VarHandleModificationsSnapshotTest::class.java, "ref", Wrapper::class.java)

    val intArrayHandle: VarHandle = MethodHandles.lookup()
        .`in`(VarHandleModificationsSnapshotTest::class.java)
        .findStaticVarHandle(VarHandleModificationsSnapshotTest::class.java, "intArray", IntArray::class.java)

    val intArrayElementsHandle: VarHandle = MethodHandles.arrayElementVarHandle(IntArray::class.java)

    class VarHandleModificationsVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(value == 1)
            check(ref === initRef && ref.x == 1)
            check(intArray === initIntArray && intArray.contentEquals(intArrayOf(1, 2, 3)))
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(VarHandleModificationsVerifier::class.java)
        iterations(100)
        invocationsPerIteration(1)
        threads(1)
        actorsPerThread(10)
    }

    @Operation
    fun putValue() {
        valueHandle.set(Random.nextInt())
    }

    @Operation
    fun compareAndSetValue() {
        val current = valueHandle.get() as Int
        valueHandle.compareAndSet(current, current + 1)
    }

    @Operation
    fun weakCompareAndSetValue() {
        val current = valueHandle.get() as Int
        valueHandle.weakCompareAndSet(current, current + 1)
    }

    @Operation
    fun getAndAddValue() {
        valueHandle.getAndAdd(1)
    }

    @Operation
    fun getAndSetValue() {
        valueHandle.getAndSet(Random.nextInt())
    }

    @Operation
    fun putRef() {
        refHandle.set(Wrapper(Random.nextInt()))
    }

    @Operation
    fun putIntArrayElement() {
        val idx = Random.nextInt(0, intArray.size)
        intArrayElementsHandle.set(intArray, idx, Random.nextInt())
    }

    @Operation
    fun compareAndSetArrayElement() {
        val idx = Random.nextInt(0, intArray.size)
        val current = intArrayElementsHandle.get(intArray, idx) as Int
        intArrayElementsHandle.compareAndSet(intArray, idx, current, current + 1)
    }

    @Operation
    fun weakCompareAndSetArrayElement() {
        val idx = Random.nextInt(0, intArray.size)
        val current = intArrayElementsHandle.get(intArray, idx) as Int
        intArrayElementsHandle.weakCompareAndSet(intArray, idx, current, current + 1)
    }

    @Operation
    fun getAndAddArrayElement() {
        val idx = Random.nextInt(0, intArray.size)
        intArrayElementsHandle.getAndAdd(intArray, idx, 1)
    }

    @Operation
    fun getAndSetArrayElement() {
        val idx = Random.nextInt(0, intArray.size)
        intArrayElementsHandle.getAndSet(intArray, idx, Random.nextInt())
    }

    @Operation
    fun assignIntArray() {
        intArrayHandle.set(intArrayOf(Random.nextInt(), Random.nextInt(), Random.nextInt()))
    }
}
