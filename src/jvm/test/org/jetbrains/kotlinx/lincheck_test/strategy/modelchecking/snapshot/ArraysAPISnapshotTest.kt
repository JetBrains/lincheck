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
import org.junit.Ignore
import java.util.Arrays
import kotlin.random.Random

private val arrayValue = intArrayOf(2, 1, 4, 3, 6, 5, 8, 7, 10, 9)

// TODO:
//  1. Instrumented method invocation inserted before `System.arraycopy` calls, get strapped out during execution (possibly jit causes that).
//  2. Parallel operations are not supported because of java.lang.ClassCastException:
//     class java.util.concurrent.ForkJoinWorkerThread cannot be casted to class sun.nio.ch.lincheck.TestThread
//     (java.util.concurrent.ForkJoinWorkerThread is in module java.base of loader 'bootstrap';
//     sun.nio.ch.lincheck.TestThread is in unnamed module of loader 'bootstrap').
@Ignore("Without support for System.arraycopy, tracking for copy methods will not work")
class ArraysAPISnapshotTest : AbstractSnapshotTest() {
    private class Wrapper(var x: Int)
    companion object {
        private var intArray = arrayValue
        private var refArray = arrayOf(Wrapper(1), Wrapper(3), Wrapper(2))

        // save values to restore
        private val refIntArray = intArray
        private val refRefArray = refArray
        private val a = refArray[0]
        private val b = refArray[1]
        private val c = refArray[2]
    }

    class ArraysAPIVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(intArray === refIntArray)
            check(intArray.contentEquals(arrayValue))

            check(refArray === refRefArray)
            check(refArray[0] == a && refArray[1] == b && refArray[2] == c)
            check(a.x == 1 && b.x == 3 && c.x == 2)
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(ArraysAPIVerifier::class.java)
        actorsBefore(0)
        actorsAfter(0)
        iterations(100)
        invocationsPerIteration(1)
        threads(1)
        actorsPerThread(1)
    }

    @Operation
    fun sort() {
        intArray.sort()
        refArray.sortBy { it.x }
    }

    @Operation
    fun arraySort() {
        Arrays.sort(intArray)
        Arrays.sort(refArray) { a, b -> a.x - b.x }
    }

    @Operation
    fun reverse() {
        intArray.reverse()
        refArray.reverse()
    }

    @Operation
    fun fill() {
        intArray.fill(Random.nextInt())
        refArray.fill(Wrapper(Random.nextInt()))
    }

    @Operation
    fun arraysFill() {
        Arrays.fill(intArray, Random.nextInt())
        Arrays.fill(refArray, Wrapper(Random.nextInt()))
    }

    @Operation
    fun arraysSetAll() {
        Arrays.setAll(intArray) { Random.nextInt() }
        Arrays.setAll(refArray) { Wrapper(Random.nextInt()) }
    }

    @Operation
    fun copyOf() {
        val otherRefArray = refArray.copyOf()
        otherRefArray[Random.nextInt(0, otherRefArray.size)] = Wrapper(Random.nextInt())
        otherRefArray[Random.nextInt(0, otherRefArray.size)].x = Random.nextInt()
    }

    @Operation
    fun arraysCopyOf() {
        val otherRefArray = Arrays.copyOf(refArray, refArray.size + 1)
        otherRefArray[Random.nextInt(0, otherRefArray.size)] = Wrapper(Random.nextInt())
        otherRefArray[Random.nextInt(0, otherRefArray.size)].x = Random.nextInt()
    }
}