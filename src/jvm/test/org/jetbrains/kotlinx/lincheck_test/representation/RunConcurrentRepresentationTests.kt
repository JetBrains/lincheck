/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:OptIn(ExperimentalModelCheckingAPI::class)

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.util.UnsafeHolder
import org.jetbrains.kotlinx.lincheck_test.gpmc.*
import org.jetbrains.kotlinx.lincheck_test.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.*
import kotlin.concurrent.thread
import kotlin.random.Random
import org.junit.Test


abstract class BaseRunConcurrentRepresentationTest<R>(private val outputFileName: String) {
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

class ArrayReadWriteRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "run_concurrent_test/array_rw.txt"
) {
    companion object {
        // the variable is static to trigger the snapshot tracker to restore it between iterations
        // (`block` actually will be run twice)
        private val array = IntArray(3)
    }

    @Suppress("UNUSED_VARIABLE")
    override fun block() {
        val index = Random.nextInt(array.size)
        array[index]++
        val y = array[index]
        check(false)
    }
}

class AtomicReferencesNamesRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "run_concurrent_test/atomic_refs.txt"
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

class AtomicReferencesFromMultipleFieldsRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "run_concurrent_test/atomic_refs_two_fields.txt"
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

class VariableReadWriteRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "run_concurrent_test/var_rw.txt"
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

// TODO investigate difference for trace debugger (Evgeniy Moiseenko)
class CustomThreadsRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    if (isInTraceDebuggerMode) {
        when (testJdkVersion) {
            TestJdkVersion.JDK_8  -> "run_concurrent_test/custom_threads_trace_debugger_jdk8.txt"
            TestJdkVersion.JDK_11 -> "run_concurrent_test/custom_threads_trace_debugger_jdk11.txt"
            TestJdkVersion.JDK_17 -> "run_concurrent_test/custom_threads_trace_debugger_jdk17.txt"
            TestJdkVersion.JDK_21 -> "run_concurrent_test/custom_threads_trace_debugger_jdk21.txt"
            else ->
                throw IllegalStateException("Unsupported JDK version for trace debugger mode: $testJdkVersion")
        }
    } else {
        "run_concurrent_test/custom_threads.txt"
    }
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

class KotlinThreadRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    if (isJdk8) "run_concurrent_test/kotlin_thread_jdk8.txt" else "run_concurrent_test/kotlin_thread.txt"
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

// TODO investigate difference for trace debugger (Evgeniy Moiseenko)
class LivelockRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    when {
        isInTraceDebuggerMode && isJdk8 -> "run_concurrent_test/livelock_trace_debugger_jdk8.txt"
        isInTraceDebuggerMode -> "run_concurrent_test/livelock_trace_debugger.txt"
        isJdk8 -> "run_concurrent_test/livelock_jdk8.txt"
        else -> "run_concurrent_test/livelock.txt"
    }
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

// TODO investigate difference for trace debugger (Evgeniy Moiseenko)
class IncorrectConcurrentLinkedDequeRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    when {
        isInTraceDebuggerMode && isJdk8 -> "run_concurrent_test/deque_trace_debugger_jdk8.txt"
        isInTraceDebuggerMode -> "run_concurrent_test/deque_trace_debugger.txt"
        isJdk8 -> "run_concurrent_test/deque_jdk8.txt"
        else -> "run_concurrent_test/deque.txt"
    }
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

class IncorrectHashmapRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    if (isJdk8) "run_concurrent_test/hashmap_jdk8.txt" else "run_concurrent_test/hashmap.txt"
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

class ThreadPoolRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    if (isInTraceDebuggerMode) {
        when (testJdkVersion) {
            TestJdkVersion.JDK_8  -> "run_concurrent_test/thread_pool/thread_pool_trace_debugger_jdk8.txt"
            TestJdkVersion.JDK_11 -> "run_concurrent_test/thread_pool/thread_pool_trace_debugger_jdk11.txt"
            TestJdkVersion.JDK_17 -> "run_concurrent_test/thread_pool/thread_pool_trace_debugger_jdk17.txt"
            TestJdkVersion.JDK_21 -> "run_concurrent_test/thread_pool/thread_pool_trace_debugger_jdk21.txt"
            else ->
                throw IllegalStateException("Unsupported JDK version for trace debugger mode: $testJdkVersion")
        }
    } else {
        when (testJdkVersion) {
            TestJdkVersion.JDK_8 -> "run_concurrent_test/thread_pool/thread_pool_jdk8.txt"
            TestJdkVersion.JDK_11 -> "run_concurrent_test/thread_pool/thread_pool_jdk11.txt"
            TestJdkVersion.JDK_13 -> "run_concurrent_test/thread_pool/thread_pool_jdk13.txt"
            TestJdkVersion.JDK_15 -> "run_concurrent_test/thread_pool/thread_pool_jdk15.txt"
            TestJdkVersion.JDK_17 -> "run_concurrent_test/thread_pool/thread_pool_jdk17.txt"
            TestJdkVersion.JDK_19 -> "run_concurrent_test/thread_pool/thread_pool_jdk19.txt"
            TestJdkVersion.JDK_20 -> "run_concurrent_test/thread_pool/thread_pool_jdk20.txt"
            TestJdkVersion.JDK_21 -> "run_concurrent_test/thread_pool/thread_pool_jdk21.txt"
        }
    }
) {
    override fun block() {
        // TODO: currently there is a problem --- if we declare counter as a local variable the test does not pass;
        //   after inspecting the generated traces, the hypothesis is that it is most likely because
        //   the counter is incorrectly classified as a local object,
        //   and thus accesses to this object are not tracked,
        //   and the race on counter increment is not detected;
        // var counter = 0
        val executorService = Executors.newFixedThreadPool(2)
        try {
            // We use an object here instead of lambda to avoid hustle with Java's lambda.
            // These lambdas are represented in the trace as `$$Lambda$XX/0x00007766d02076a0`,
            // and both lambda id and hashcode can differ between test runs,
            // leading to spurious failures of the test.
            val task = object : Runnable {
                override fun run() {
                    counter++
                }
            }
            val future1 = executorService.submit(task)
            val future2 = executorService.submit(task)
            future1.get()
            future2.get()
            check(counter == 2)
        } finally {
            executorService.shutdown()
        }
    }

    companion object {
        @JvmStatic
        private var counter = 0
    }
}