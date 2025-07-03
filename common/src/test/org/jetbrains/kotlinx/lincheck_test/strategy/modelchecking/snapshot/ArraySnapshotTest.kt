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
import org.jetbrains.kotlinx.lincheck.traceagent.isInTraceDebuggerMode
import org.jetbrains.lincheck.datastructures.ManagedOptions
import kotlin.random.Random


private var intArray = intArrayOf(1, 2, 3)

class StaticIntArraySnapshotTest : AbstractSnapshotTest() {
    companion object {
        private var ref = intArray
        private var values = intArray.copyOf()
    }

    class StaticIntArrayVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(intArray == ref)
            check(ref.contentEquals(values))
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(StaticIntArrayVerifier::class.java)
    }

    @Operation
    fun modify() {
        intArray[0]++
    }
}


private class X(var value: Int) {
    @OptIn(ExperimentalStdlibApi::class)
    override fun toString() = "X@${this.hashCode().toHexString()}($value)"
}

private var objArray = arrayOf<X>(X(1), X(2), X(3))

class StaticObjectArraySnapshotTest : AbstractSnapshotTest() {
    companion object {
        private var ref: Array<X> = objArray
        private var elements: Array<X?> = Array<X?>(3) { null }.also { objArray.forEachIndexed { index, x -> it[index] = x } }
        private var values: Array<Int> = Array<Int>(3) { 0 }.also { objArray.forEachIndexed { index, x -> it[index] = x.value } }
    }

    class StaticObjectArrayVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(objArray == ref)
            check(objArray.contentEquals(elements))
            check(objArray.map { it.value }.toTypedArray().contentEquals(values))
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(StaticObjectArrayVerifier::class.java)
        if (isInTraceDebuggerMode) invocationsPerIteration(1)
    }

    @Operation
    fun modify() {
        objArray[0].value++
        objArray[1].value--
        objArray[2] = X(Random.nextInt())
    }
}