/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.Lincheck
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.random.Random


abstract class BaseRunWithLambdaRepresentationTest(private val outputFileName: String) {
    /**
     * Implement me and place the logic to check its trace.
     */
    abstract fun block()

    @Test
    fun testRunWithModelChecker() {
        val failure = Lincheck.verifyWithModelChecker(
            verifierClass = FailingVerifier::class.java
        ) {
            block()
        }
        failure.checkLincheckOutput(outputFileName)
    }
}

class ArrayReadWriteRunWithLambdaTest : BaseRunWithLambdaRepresentationTest("array_rw_run_with_lambda.txt") {
    private val array = IntArray(3)

    @Suppress("UNUSED_VARIABLE")
    override fun block() {
        val index = Random.nextInt(array.size)
        array[index]++
        val y = array[index]
    }
}


class AtomicReferencesNamesRunWithLambdaTests : BaseRunWithLambdaRepresentationTest("atomic_refs_trace_run_with_lambda.txt") {

    private val atomicReference = AtomicReference(Node(1))
    private val atomicInteger = AtomicInteger(0)
    private val atomicLong = AtomicLong(0L)
    private val atomicBoolean = AtomicBoolean(true)

    private val atomicReferenceArray = AtomicReferenceArray(arrayOf(Node(1)))
    private val atomicIntegerArray = AtomicIntegerArray(intArrayOf(0))
    private val atomicLongArray = AtomicLongArray(longArrayOf(0L))

    private val wrapper = AtomicReferenceWrapper()

    override fun block() {
        atomicReference.compareAndSet(atomicReference.get(), Node(2))
        atomicReference.set(Node(3))

        atomicInteger.compareAndSet(atomicInteger.get(), 2)
        atomicInteger.set(3)

        atomicLong.compareAndSet(atomicLong.get(), 2)
        atomicLong.set(3)

        atomicBoolean.compareAndSet(atomicBoolean.get(), true)
        atomicBoolean.set(false)

        atomicReferenceArray.compareAndSet(0, atomicReferenceArray.get(0), Node(2))
        atomicReferenceArray.set(0, Node(3))

        atomicIntegerArray.compareAndSet(0, atomicIntegerArray.get(0), 1)
        atomicIntegerArray.set(0, 2)

        atomicLongArray.compareAndSet(0, atomicLongArray.get(0), 1)
        atomicLongArray.set(0, 2)

        wrapper.reference.set(Node(5))
        wrapper.array.compareAndSet(0, 1 ,2)

        staticValue.compareAndSet(0, 2)
        staticValue.set(0)

        AtomicReferenceWrapper.staticValue.compareAndSet(1, 2)
        AtomicReferenceWrapper.staticValue.set(3)

        staticArray.compareAndSet(1, 0, 1)
        AtomicReferenceWrapper.staticArray.compareAndSet(1, 0, 1)
    }

    private data class Node(val name: Int)

    private class AtomicReferenceWrapper {
        val reference = AtomicReference(Node(0))
        val array = AtomicIntegerArray(10)

        companion object {
            @JvmStatic
            val staticValue = AtomicInteger(1)
            @JvmStatic
            val staticArray = AtomicIntegerArray(3)
        }
    }

    companion object {
        @JvmStatic
        private val staticValue = AtomicInteger(0)
        @JvmStatic
        val staticArray = AtomicIntegerArray(3)
    }
}

class AtomicReferencesFromMultipleFieldsRunWithLambdaTest : BaseRunWithLambdaRepresentationTest("atomic_refs_two_fields_trace_run_with_lambda.txt") {

    private var atomicReference1: AtomicReference<Node>
    private var atomicReference2: AtomicReference<Node>

    init {
        val ref = AtomicReference(Node(1))
        atomicReference1 = ref
        atomicReference2 = ref
    }

    override fun block() {
        atomicReference1.compareAndSet(atomicReference2.get(), Node(2))
    }

    private data class Node(val name: Int)

}

class VariableReadWriteRunWithLambdaTest : BaseRunWithLambdaRepresentationTest("var_rw_run_with_lambda.txt") {
    private var x = 0

    @Suppress("UNUSED_VARIABLE")
    override fun block() {
        x++
        val y = --x
    }
}