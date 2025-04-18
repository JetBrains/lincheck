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
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedOptions
import org.junit.Ignore
import java.util.*
import kotlin.random.Random

private val arrayValue = intArrayOf(2, 1, 4, 3, 6, 5, 8, 7, 10, 9)

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
        iterations(1000)
        invocationsPerIteration(1)
        threads(1)
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        setup()
    }

    protected fun asListImpl() {
        Arrays.asList<Wrapper?>(*refArray).random().x = Random.nextInt()
    }

    protected fun sortImpl() {
        intArray.sort()
        refArray.sortBy { it.x }
    }

    protected fun arraysSortImpl() {
        Arrays.sort(intArray)
        Arrays.sort(refArray) { a, b -> a.x - b.x }
    }

    protected fun arraysParallelSortImpl() {
        Arrays.parallelSort(intArray)
    }

    protected fun arraysParallelPrefixImpl() {
        Arrays.parallelPrefix(intArray) { a, b -> a + b }
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

    protected fun arraysParallelSetAllImpl() {
        Arrays.parallelSetAll(intArray) { Random.nextInt() }
        Arrays.parallelSetAll(refArray) { Wrapper(Random.nextInt()) }
    }

    protected fun copyOfImpl() {
        val otherRefArray = refArray.copyOf()
        otherRefArray[Random.nextInt(0, otherRefArray.size)] = Wrapper(Random.nextInt())
        otherRefArray[Random.nextInt(0, otherRefArray.size)].x = Random.nextInt()
    }

    protected fun arraysCopyOfImpl() {
        val otherRefArray = Arrays.copyOf(refArray, refArray.size)
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

class ArraysAPISnapshotTest : BaseArraysAPISnapshotTest() {

    @Operation
    fun asList() = asListImpl()

    @Operation
    fun sort() = sortImpl()

    @Operation
    fun arraysSort() = arraysSortImpl()

    @Operation
    fun arraysParallelSort() = arraysParallelSortImpl()

    @Operation
    fun arraysParallelPrefix() = arraysParallelPrefixImpl()

    @Operation
    fun reverse() = reverseImpl()

    @Operation
    fun fill() = fillImpl()

    @Operation
    fun arraysFill() = arraysFillImpl()

    @Operation
    fun arraysSetAll() = arraysSetAllImpl()

    fun arraysParallelSetAll() = arraysParallelSetAllImpl()

    @Operation
    fun copyOf() = copyOfImpl()

    @Operation
    fun arraysCopyOf() = arraysCopyOfImpl()

    @Operation
    fun copyOfRange() = copyOfRangeImpl()

    @Operation
    fun arraysCopyOfRange() = arraysCopyOfRangeImpl()
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
        iterations(1000)
        actorsPerThread(1)
    }
}

class IsolatedAsListTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun asList() = asListImpl()
}

class IsolatedSortTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun sort() = sortImpl()
}

class IsolatedArraysSortTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysSort() = arraysSortImpl()
}

class IsolatedArraysParallelSortTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysParallelSort() = arraysParallelSortImpl()
}

class IsolatedArraysParallelPrefixTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysParallelPrefix() = arraysParallelPrefixImpl()
}

class IsolatedReverseTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun reverse() = reverseImpl()
}

class IsolatedFillTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun fill() = fillImpl()
}

class IsolatedArraysFillTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysFill() = arraysFillImpl()
}

class IsolatedArraysSetAllTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysSetAll() = arraysSetAllImpl()
}

@Ignore("Execution has hung error")
class IsolatedArraysParallelSetAllTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysParallelSetAll() = arraysParallelSetAllImpl()
}

class IsolatedCopyOfTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun copyOf() = copyOfImpl()
}

class IsolatedArraysCopyOfTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysCopyOf() = arraysCopyOfImpl()
}

class IsolatedCopyOfRangeTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun copyOfRange() = copyOfRangeImpl()
}

class IsolatedArraysCopyOfRangeTest : BaseIsolatedArraysAPISnapshotTest() {
    @Operation
    fun arraysCopyOfRange() = arraysCopyOfRangeImpl()
}