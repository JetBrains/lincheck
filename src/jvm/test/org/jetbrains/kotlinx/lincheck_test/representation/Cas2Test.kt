/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.atomicArrayOfNulls
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.representation.AtomicArrayWithCAS2.Status.*
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test

@Param(name = "index", gen = IntGen::class, conf = "0:${ARRAY_SIZE - 1}")
@Param(name = "value", gen = IntGen::class, conf = "0:2")
class AtomicArrayWithCAS2Test {
    private val array = AtomicArrayWithCAS2(ARRAY_SIZE, 0)

//    @Operation(params = ["index"])
//    fun get(index: Int) = array.get(index)
//
//    @Operation(params = ["index", "value", "value"])
//    fun cas(index: Int, expected: Int, update: Int) = array.cas(index, expected, update)

    @Operation(params = ["index", "value", "value", "index", "value", "value"])
    fun cas2_0(
        index1: Int, expected1: Int, update1: Int,
        index2: Int, expected2: Int, update2: Int,
    ) = array.cas2(index1, expected1, update1, index2, expected2, update2, 0)

    @Operation(params = ["index", "value", "value", "index", "value", "value"])
    fun cas2_1(
        index1: Int, expected1: Int, update1: Int,
        index2: Int, expected2: Int, update2: Int,
    ) = array.cas2(index1, expected1, update1, index2, expected2, update2, 1)


    @Test
    fun modelCheckingTest() =
        ModelCheckingOptions()
            .addCustomScenario {
                parallel {
                    thread { actor(AtomicArrayWithCAS2Test::cas2_0, 0, 0, 2, 1, 0, 3) }
                    thread { actor(AtomicArrayWithCAS2Test::cas2_1, 0, 0, 4, 1, 0, 5) }
                }
            }
            .iterations(500)
            .invocationsPerIteration(1000)
            .actorsBefore(2)
            .threads(3)
            .actorsPerThread(2)
            .actorsAfter(2)
            .checkObstructionFreedom(false)
            .sequentialSpecification(IntAtomicArraySequential::class.java)
            .checkImpl(this::class.java)
            .checkLincheckOutput("cas2.txt")
}

// This implementation never stores `null` values.
class AtomicArrayWithCAS2<E : Any>(size: Int, initialValue: E) {
    private val array = atomicArrayOfNulls<Descriptor>(size)
    private val gate0 = atomic(false)
    private val gate1 = atomic(false)

    init {
        // Fill array with the initial value.
        for (i in 0 until size) {
            array[i].value = Descriptor(i, initialValue, initialValue, i, initialValue, initialValue).apply {
                status.value = SUCCESS
            }
        }
    }

    fun get(index: Int): E {
        val desc = array[index].value!!
        return desc.read(index) as E
    }

    fun cas(index: Int, expected: E, update: E): Boolean {
        val descriptor = Descriptor(-1, null, null, index, expected, update)
        descriptor.apply()
        return descriptor.status.value == SUCCESS
    }

    fun cas2(
        index1: Int, expected1: E, update1: E,
        index2: Int, expected2: E, update2: E,
        turn: Int
    ): Boolean {
        require(index1 != index2) { "The indices should be different" }

        val descriptor = if (index1 < index2) Descriptor(index1, expected1, update1, index2, expected2, update2)
        else Descriptor(index2, expected2, update2, index1, expected1, update1)

        descriptor.apply(turn = turn)
        return descriptor.status.value == SUCCESS
    }

    private inner class Descriptor(
        private val index1: Int,
        private val expected1: Any?,
        private val update1: Any?,
        private val index2: Int,
        private val expected2: Any,
        private val update2: Any
    ) {
        val status = atomic(UNDECIDED)

        fun read(index: Int): Any {
            check(index == index1 || index == index2)
            return if (status.value == SUCCESS) {
                if (index == index1) update1 else update2
            } else {
                if (index == index1) expected1 else expected2
            }!!
        }

        fun apply(initial: Boolean = true, turn: Int = -1, broken: Boolean = false) {
            if (broken) {
                status.value
                status.compareAndSet(SUCCESS, SUCCESS)
                installOrHelp(true, turn, true)
            }
            if (status.value == UNDECIDED) {
                val install1 = if (!initial || index1 == -1) true else installOrHelp(true, turn)
                val install2 = installOrHelp(false, turn)

                if (install1 && install2) {
                    status.compareAndSet(UNDECIDED, SUCCESS)
                }
            }
        }

        private fun installOrHelp(first: Boolean, turn: Int = -1, broken: Boolean = false): Boolean {
            val index = if (first) index1 else index2
            val expected = if (first) expected1 else expected2
            check(index != -1)
            while (true) {
                val current = array[index].value!!
                if (broken) {
                    current.apply(false, turn, true)
                }
                if (current === this) return true

                when (turn) {
                    1 -> {
                        gate0.value = true
                        if (gate1.value) {
                            current.apply(false, turn = turn, true)
                        }
                        gate0.value = false
                    }

                    0 -> {
                        gate1.value = true
                        if (gate0.value) {
                            current.apply(false, turn = turn, true)
                        }
                        gate1.value = false
                    }

                    else -> error("Not excepted: $turn")
                }

                current.apply(false, turn = turn)
                if (current.read(index) !== expected) {
                    status.compareAndSet(UNDECIDED, FAILED)
                    return false
                }
                if (status.value != UNDECIDED) return false
                if (array[index].compareAndSet(current, this)) {
                    return true
                }
            }
        }
    }

    enum class Status {
        UNDECIDED, SUCCESS, FAILED
    }

}

private const val ARRAY_SIZE = 3

class IntAtomicArraySequential {
    private val array = IntArray(ARRAY_SIZE)

    fun get(index: Int): Int = array[index]

    fun set(index: Int, value: Int) {
        array[index] = value
    }

    fun cas(index: Int, expected: Int, update: Int): Boolean {
        if (array[index] != expected) return false
        array[index] = update
        return true
    }

    fun dcss(
        index1: Int, expected1: Int, update1: Int,
        index2: Int, expected2: Int
    ): Boolean {
        require(index1 != index2) { "The indices should be different" }
        if (array[index1] != expected1 || array[index2] != expected2) return false
        array[index1] = update1
        return true
    }

    fun cas2(
        index1: Int, expected1: Int, update1: Int, index2: Int, expected2: Int, update2: Int
    ): Boolean {
        require(index1 != index2) { "The indices should be different" }
        if (array[index1] != expected1 || array[index2] != expected2) return false
        array[index1] = update1
        array[index2] = update2
        return true
    }

    fun cas2_0(
        index1: Int, expected1: Int, update1: Int, index2: Int, expected2: Int, update2: Int
    ) = cas2(index1, expected1, update1, index2, expected2, update2)

    fun cas2_1(
        index1: Int, expected1: Int, update1: Int, index2: Int, expected2: Int, update2: Int
    ) = cas2(index1, expected1, update1, index2, expected2, update2)
}

