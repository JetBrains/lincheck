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
import java.util.Collections
import kotlin.random.Random


private val arrayValue = intArrayOf(2, 1, 4, 3, 6, 5, 8, 7, 10, 9)

class CollectionsAPISnapshotTest : AbstractSnapshotTest() {

    private class Wrapper(var x: Int)
    companion object {
        private var intList = arrayValue.toMutableList()
        private var refList = mutableListOf<Wrapper>(Wrapper(1), Wrapper(3), Wrapper(2))

        // save values to restore
        private val refIntList = intList
        private val refRefList = refList
        private val a = refList[0]
        private val b = refList[1]
        private val c = refList[2]
    }

    class CollectionsAPIVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(intList === refIntList)
            check(intList.toIntArray().contentEquals(arrayValue))

            check(refList === refRefList)
            check(refList[0] == a && refList[1] == b && refList[2] == c)
            check(a.x == 1 && b.x == 3 && c.x == 2)
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(CollectionsAPIVerifier::class.java)
        actorsBefore(0)
        actorsAfter(0)
        iterations(100)
        invocationsPerIteration(1)
        threads(1)
        actorsPerThread(1)
    }

    @Operation
    fun sort() {
        Collections.sort(intList)
        Collections.sort(refList) { a, b -> a.x - b.x }
    }

    @Operation
    fun reverse() {
        Collections.reverse(intList)
        Collections.reverse(refList)
    }

    @Operation
    fun fill() {
        Collections.fill(intList, Random.nextInt())
        Collections.fill(refList, Wrapper(Random.nextInt()))
    }

    @Operation
    fun copyOf() {
        val otherRefList = MutableList<Wrapper?>(refList.size) { null }
        Collections.copy<Wrapper>(otherRefList, refList)

        otherRefList[Random.nextInt(0, otherRefList.size)] = Wrapper(Random.nextInt())
        otherRefList[Random.nextInt(0, otherRefList.size)]!!.x = Random.nextInt()
    }
}