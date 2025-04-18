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

import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedOptions
import java.util.Arrays
import kotlin.random.Random

private val arrayValue = intArrayOf(2, 1, 4, 3, 6, 5, 8, 7, 10, 9)

// TODO:
//  1. Instrumented method invocation inserted before `System.arraycopy` calls, get strapped out during execution (possibly jit causes that).
//  2. Parallel operations are not supported because of java.lang.ClassCastException:
//     class java.util.concurrent.ForkJoinWorkerThread cannot be casted to class sun.nio.ch.lincheck.TestThread
//     (java.util.concurrent.ForkJoinWorkerThread is in module java.base of loader 'bootstrap';
//     sun.nio.ch.lincheck.TestThread is in unnamed module of loader 'bootstrap').
abstract class BaseArraysAPISnapshotTest : AbstractSnapshotTest() {
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

    protected fun <O : ManagedOptions<O, *>> O.setup() {
        verifier(ArraysAPIVerifier::class.java)
        actorsBefore(0)
        actorsAfter(0)
        actorsPerThread(10)
        iterations(200)
        invocationsPerIteration(1)
        threads(1)
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        setup()
    }

    protected fun sortImpl() {
        intArray.sort()
        refArray.sortBy { it.x }
    }

    protected fun arraysSortImpl() {
        Arrays.sort(intArray)
        Arrays.sort(refArray) { a, b -> a.x - b.x }
    }

    protected fun reverseImpl() {
        intArray.reverse()
        refArray.reverse()
    }

    protected fun fillImpl() {
        intArray.fill(Random.nextInt())
        refArray.fill(Wrapper(Random.nextInt()))
    }

    protected fun arraysFillImpl() {
        Arrays.fill(intArray, Random.nextInt())
        Arrays.fill(refArray, Wrapper(Random.nextInt()))
    }

    protected fun arraysSetAllImpl() {
        Arrays.setAll(intArray) { Random.nextInt() }
        Arrays.setAll(refArray) { Wrapper(Random.nextInt()) }
    }

    protected fun copyOfImpl() {
        val otherRefArray = refArray.copyOf()
        otherRefArray[Random.nextInt(0, otherRefArray.size)] = Wrapper(Random.nextInt())
        otherRefArray[Random.nextInt(0, otherRefArray.size)].x = Random.nextInt()
    }

    protected fun arraysCopyOfImpl() {
        val otherRefArray = Arrays.copyOf(refArray, refArray.size + 1 /* extra size */)
        otherRefArray[Random.nextInt(0, otherRefArray.size)] = Wrapper(Random.nextInt())
        otherRefArray[Random.nextInt(0, otherRefArray.size)].x = Random.nextInt()
    }

    protected fun copyOfRangeImpl() {
        val otherRefArray = refArray.copyOfRange(0, refArray.size)
        otherRefArray[Random.nextInt(0, otherRefArray.size)] = Wrapper(Random.nextInt())
        otherRefArray[Random.nextInt(0, otherRefArray.size)].x = Random.nextInt()
    }

    protected fun arraysCopyOfRangeImpl() {
        val otherRefArray = Arrays.copyOfRange(refArray, 0, refArray.size)
        otherRefArray[Random.nextInt(0, otherRefArray.size)] = Wrapper(Random.nextInt())
        otherRefArray[Random.nextInt(0, otherRefArray.size)].x = Random.nextInt()
    }
}

/**
 * Isolated tests are aimed to trigger jit to optimize the bytecode in the `Arrays` methods.
 * Previously we encountered a bug (https://github.com/JetBrains/lincheck/issues/470), when hooks,
 * inserted directly before `System.arraycopy`, were missed during execution after
 * a couple of hundreds iterations due to jit optimizations.
 *
 * Thus, in subclasses of [BaseIsolatedArraysAPISnapshotTest] we perform
 * each operation alone during many iterations.
 */
abstract class BaseIsolatedArraysAPISnapshotTest : BaseArraysAPISnapshotTest() {
    override fun <O : ManagedOptions<O, *>> O.customize() {
        setup()
        iterations(600)
        actorsPerThread(1)
        logLevel(LoggingLevel.INFO)
    }
}

class IsolatedSortTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun sort() = this::sortImpl
}

class IsolatedArraysSortTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysSort() = this::arraysSortImpl
}

class IsolatedReverseTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun reverse() = this::reverseImpl
}

class IsolatedFillTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun fill() = this::fillImpl
}

class IsolatedArraysFillTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysFill() = this::arraysFillImpl
}

class IsolatedArraysSetAllTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysSetAll() = this::arraysSetAllImpl
}

class IsolatedCopyOfTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun copyOf() = this::copyOfImpl
}

class IsolatedArraysCopyOfTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysCopyOf() = this::arraysCopyOfImpl
}

class IsolatedCopyOfRangeTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun copyOfRange() = this::copyOfRangeImpl
}

class IsolatedArraysCopyOfRangeTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysCopyOfRange() = this::arraysCopyOfRangeImpl
}