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
import org.jetbrains.kotlinx.lincheck.util.UnsafeHolder
import org.jetbrains.kotlinx.lincheck.util.getArrayElementOffsetViaUnsafe
import org.jetbrains.kotlinx.lincheck.util.getFieldOffsetViaUnsafe
import kotlin.random.Random
import kotlin.reflect.jvm.javaField


@Suppress("DEPRECATION") // Unsafe
class UnsafeModificationsSnapshotTest : AbstractSnapshotTest() {
    private class Wrapper(var x: Int)
    companion object {
        private var value = 1
        private var ref = Wrapper(1)
        private var intArray = intArrayOf(1, 2, 3)

        // remember values for restoring
        private val initRef = ref
        private val initIntArray = intArray
    }

    private val U = UnsafeHolder.UNSAFE
    private val valueBase = U.staticFieldBase(UnsafeModificationsSnapshotTest.Companion::value.javaField!!)
    private val valueOffset = getFieldOffsetViaUnsafe(UnsafeModificationsSnapshotTest.Companion::value.javaField!!)

    private val refBase = U.staticFieldBase(UnsafeModificationsSnapshotTest.Companion::ref.javaField!!)
    private val refOffset = getFieldOffsetViaUnsafe(UnsafeModificationsSnapshotTest.Companion::ref.javaField!!)

    class UnsafeModificationsVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(value == 1)
            check(ref === initRef && ref.x == 1)
            check(intArray === initIntArray && intArray.contentEquals(intArrayOf(1, 2, 3)))
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(UnsafeModificationsVerifier::class.java)
        iterations(100)
        invocationsPerIteration(1)
        threads(1)
        actorsPerThread(10)
    }

    @Operation
    fun putValue() {
        U.putInt(valueBase, valueOffset, Random.nextInt())
    }

    @Operation
    fun putRef() {
        U.putObject(refBase, refOffset, Wrapper(Random.nextInt()))
    }

    @Operation
    fun putIntArray() {
        val index = Random.nextInt(0, intArray.size)
        U.putInt(intArray, getArrayElementOffsetViaUnsafe(intArray, index), Random.nextInt())
    }

    @Operation
    fun assignIntArray() {
        intArray = intArrayOf(Random.nextInt(), Random.nextInt(), Random.nextInt())
    }
}
