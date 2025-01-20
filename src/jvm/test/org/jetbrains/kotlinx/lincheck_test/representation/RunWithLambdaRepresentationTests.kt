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

import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.jetbrains.kotlinx.lincheck.util.UnsafeHolder
import org.jetbrains.kotlinx.lincheck_test.gpmc.SpinLock
import org.jetbrains.kotlinx.lincheck_test.gpmc.withLock
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.jetbrains.kotlinx.lincheck_test.util.isJdk8
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.concurrent.thread
import kotlin.random.Random


abstract class BaseRunWithLambdaRepresentationTest<R>(private val outputFileName: String) {
    /**
     * Implement me and place the logic to check its trace.
     */
    abstract fun block(): R

    @Test
    fun testRunWithModelChecker() {
        val result = runCatching {
            runConcurrentTest {
                block()
            }
        }
        check(result.isFailure) {
            "The test should fail, but it completed successfully"
        }
        val error = result.exceptionOrNull()!!
        check(error is LincheckAssertionError) {
            "The test should throw LincheckAssertionError"
        }
        error.failure.checkLincheckOutput(outputFileName)
    }
}

class ArrayReadWriteRunWithLambdaTest : BaseRunWithLambdaRepresentationTest<Unit>(
    "array_rw_run_with_lambda.txt"
) {
    companion object {
        private val array = IntArray(3) // variable is static in order to trigger snapshot tracker to restore it between iterations (`block` actually will be run twice)
    }

    @Suppress("UNUSED_VARIABLE")
    override fun block() {
        val index = Random.nextInt(array.size)
        array[index]++
        val y = array[index]
        check(false)
    }
}

class AtomicReferencesNamesRunWithLambdaTests : BaseRunWithLambdaRepresentationTest<Unit>(
    "atomic_refs_trace_run_with_lambda.txt"
) {

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
        check(false)
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
        private val atomicReference = AtomicReference(Node(1))
        private val atomicInteger = AtomicInteger(0)
        private val atomicLong = AtomicLong(0L)
        private val atomicBoolean = AtomicBoolean(true)

        private val atomicReferenceArray = AtomicReferenceArray(arrayOf(Node(1)))
        private val atomicIntegerArray = AtomicIntegerArray(intArrayOf(0))
        private val atomicLongArray = AtomicLongArray(longArrayOf(0L))

        private val wrapper = AtomicReferenceWrapper()

        @JvmStatic
        private val staticValue = AtomicInteger(0)
        @JvmStatic
        val staticArray = AtomicIntegerArray(3)
    }
}

class AtomicReferencesFromMultipleFieldsRunWithLambdaTest : BaseRunWithLambdaRepresentationTest<Unit>(
    "atomic_refs_two_fields_trace_run_with_lambda.txt"
) {
    companion object {
        private var atomicReference1: AtomicReference<Node>
        private var atomicReference2: AtomicReference<Node>

        init {
            val ref = AtomicReference(Node(1))
            atomicReference1 = ref
            atomicReference2 = ref
        }
    }

    override fun block() {
        atomicReference1.compareAndSet(atomicReference2.get(), Node(2))
        check(false)
    }

    private data class Node(val name: Int)

}

class VariableReadWriteRunWithLambdaTest : BaseRunWithLambdaRepresentationTest<Unit>(
    "var_rw_run_with_lambda.txt"
) {
    companion object {
        private var x = 0
    }

    @Suppress("UNUSED_VARIABLE")
    override fun block() {
        x++
        val y = --x
        check(false)
    }
}

class BasicCustomThreadsRunWithLambdaTest : BaseRunWithLambdaRepresentationTest<Unit>(
    "basic_custom_threads_run_with_lambda.txt"
) {
    override fun block() {
        val block = Runnable {
            wrapper.value += 1
            valueUpdater.getAndIncrement(wrapper)
            unsafe.getAndAddInt(wrapper, valueFieldOffset, 1)
            synchronized(this) {
                wrapper.value += 1
            }
        }
        val threads = List(3) { Thread(block) }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        check(false) // to trigger failure and trace collection
    }

    @Suppress("DEPRECATION") // Unsafe
    companion object {
        var wrapper = Wrapper(0)

        val unsafe =
            UnsafeHolder.UNSAFE

        private val valueField =
            Wrapper::class.java.getDeclaredField("value")

        private val valueUpdater =
            AtomicIntegerFieldUpdater.newUpdater(Wrapper::class.java, "value")

        private val valueFieldOffset =
            unsafe.objectFieldOffset(valueField)
    }

    data class Wrapper(@Volatile @JvmField var value: Int)
}

class KotlinThreadRunWithLambdaTest : BaseRunWithLambdaRepresentationTest<Unit>(
    if (isJdk8) "kotlin_thread_run_with_lambda_jdk8.txt" else "kotlin_thread_run_with_lambda.txt"
) {
    companion object {
        @Volatile
        @JvmField
        var value = 0
    }

    override fun block() {
        val t = thread {
            repeat(3) { value += 1 }
        }
        t.join()
        check(false) // to trigger failure and trace collection
    }
}

class LivelockRunWithLambdaTest : BaseRunWithLambdaRepresentationTest<Unit>(
    if (isJdk8) "livelock_run_with_lambda_jdk8.txt" else "livelock_run_with_lambda.txt"
) {
    override fun block() {
        var counter = 0
        val lock1 = SpinLock()
        val lock2 = SpinLock()
        val t1 = Thread {
            lock1.withLock {
                lock2.withLock {
                    counter++
                }
            }
        }
        val t2 = Thread {
            lock2.withLock {
                lock1.withLock {
                    counter++
                }
            }
        }
        val threads = listOf(t1, t2)
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        check(counter == 2)
    }
}

class IncorrectConcurrentLinkedDequeRunWithLambdaTest : BaseRunWithLambdaRepresentationTest<Unit>(
    if (isJdk8) "deque_run_with_lambda_jdk8.txt" else "deque_run_with_lambda.txt"
) {
    override fun block() {
        val deque = ConcurrentLinkedDeque<Int>()
        var r1: Int = -1
        var r2: Int = -1
        deque.addLast(1)
        val t1 = Thread {
            r1 = deque.pollFirst()
        }
        val t2 = Thread {
            deque.addFirst(0)
            r2 = deque.peekLast()
        }
        val threads = listOf(t1, t2)
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        check(!(r1 == 1 && r2 == 1))
    }
}

class IncorrectHashmapRunWithLambdaTest : BaseRunWithLambdaRepresentationTest<Unit>(
    if (isJdk8) "hashmap_run_with_lambda_jdk8.txt" else "hashmap_run_with_lambda.txt"
) {
    override fun block() {
        val hashMap = HashMap<Int, Int>()
        var r1: Int? = null
        var r2: Int? = null
        val t1 = thread {
            r1 = hashMap.put(0, 1)
        }
        val t2 = thread {
            r2 = hashMap.put(0, 1)
        }
        t1.join()
        t2.join()
        check(!(r1 == null && r2 == null))
    }
}