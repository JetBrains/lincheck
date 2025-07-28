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

import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import org.jetbrains.lincheck.util.UnsafeHolder
import org.jetbrains.kotlinx.lincheck_test.gpmc.*
import org.jetbrains.kotlinx.lincheck_test.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.lincheck.Lincheck.runConcurrentTestInternal
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.util.JdkVersion
import org.jetbrains.lincheck.util.jdkVersion
import kotlin.concurrent.thread
import org.junit.*
import org.junit.Assume.assumeFalse


abstract class BaseRunConcurrentRepresentationTest<R>(
    private val outputFileName: String,
    private val handleOnlyDefaultJdkOutput: Boolean = true
) {
    /**
     * If this flag is marked as `true`, then this test will not check its
     * representation output with the expected one saved on disk.
     */
    protected open val isFlakyTest: Boolean = false

    /**
     * Implement me and place the logic to check its trace.
     */
    abstract fun block(): R

    @Test
    fun testRunWithModelChecker() {
        val result = runCatching {
            val settings = LincheckSettings(analyzeStdLib = analyzeStdLib)
            runConcurrentTestInternal(settings = settings) {
                block()
            }
        }
        checkResult(result, outputFileName, handleOnlyDefaultJdkOutput, isFlakyTest)
    }

    open val analyzeStdLib = true
    
    companion object {
        fun checkResult(result: Result<*>, outputFileName: String, handleOnlyDefaultJdkOutput: Boolean, isFlakyTest: Boolean = false) {
            check(result.isFailure) {
                "The test should fail, but it completed successfully"
            }
            val error = result.exceptionOrNull()!!
            check(error is LincheckAssertionError) {
            """
            |The test should throw LincheckAssertionError, but instead it failed with:
            |${error.stackTraceToString()}
            """
            .trimMargin()
            }
            if (!isFlakyTest) {
                error.failure.checkLincheckOutput(outputFileName, handleOnlyDefaultJdkOutput)
            }
        }
    }
}

class NoEventsRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "run_concurrent_test/no_events"
) {
    override fun block() {
        check(false)
    }
}

class IncrementAndFailConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "run_concurrent_test/increment_and_fail"
) {
    var x = 0

    override fun block() {
        x++
        check(false)
    }
}


@Ignore // TODO: does not provide a thread dump
class InfiniteLoopRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "run_concurrent_test/infinite_loop"
) {
    override fun block() {
        while (true) {}
    }
}

class MainThreadBlockedRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "run_concurrent_test/main_thread_blocked"
) {
    override fun block() {
       val q = ArrayBlockingQueue<Int>(1)
       q.take() // should block
    }
}

class ArrayReadWriteRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "run_concurrent_test/array_rw"
) {
    companion object {
        // the variable is static to trigger the snapshot tracker to restore it between iterations
        // (`block` actually will be run twice)
        private val array = IntArray(3)
    }

    @Suppress("UNUSED_VARIABLE")
    override fun block() {
        for (index in array.indices) {
            array[index]++
            val y = array[index]
            check(false)
        }
    }
}

class AtomicReferencesNamesRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "run_concurrent_test/atomic_refs"
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
    "run_concurrent_test/atomic_refs_two_fields"
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
    "run_concurrent_test/var_rw"
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

class AnonymousObjectRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>("run_concurrent_test/anonymous_object") {
    // use static fields to avoid local object optimizations
    companion object {
        @JvmField var runnable: Runnable? = null
        @JvmField var x = 0
    }

    // use the interface to additionally check that KT-16727 bug is handled:
    // https://youtrack.jetbrains.com/issue/KT-16727/
    interface I {
        fun test() = object : Runnable {
            override fun run() {
                x++
            }
        }
    }

    @Suppress("UNUSED_VARIABLE")
    override fun block() {
        runnable = (object : I {}).test()
        runnable!!.run()
        check(false)
    }
}

// TODO investigate difference for trace debugger (Evgeniy Moiseenko)
class CustomThreadsRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>("run_concurrent_test/custom_threads") {
    override fun block() {
        // We use an object here instead of lambda to avoid hustle
        // with different representations of lambdas on different JDKs.
        val task = object : Runnable {
            override fun run() {
                wrapper.value += 1
                valueUpdater.getAndIncrement(wrapper)
                unsafe.getAndAddInt(wrapper, valueFieldOffset, 1)
                synchronized(this) {
                    wrapper.value += 1
                }
            }
        }
        val threads = List(3) { Thread(task) }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        check(false) // to trigger failure and trace collection
    }

    @Suppress("DEPRECATION") // Unsafe
    companion object {
        @JvmField var wrapper = Wrapper(0)

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

class KotlinThreadRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>("run_concurrent_test/kotlin_thread") {
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
class LivelockRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>("run_concurrent_test/livelock") {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

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
class IncorrectConcurrentLinkedDequeRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>("run_concurrent_test/deque") {
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

    override val analyzeStdLib: Boolean = true
}

class IncorrectHashmapRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>("run_concurrent_test/hashmap") {
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
    "run_concurrent_test/thread_pool/thread_pool"
) {
    @Before
    fun setUp() {
        assumeFalse(isInTraceDebuggerMode) // unstable hash-code
    }

    override fun block() {
        // TODO: currently there is a problem --- if we declare counter as a local variable the test does not pass;
        //   after inspecting the generated traces, the hypothesis is that it is most likely because
        //   the counter is incorrectly classified as a local object,
        //   and thus accesses to this object are not tracked,
        //   and the race on counter increment is not detected;
        // var counter = 0
        val executorService = createFixedThreadPool(2)
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

class CoroutinesRunConcurrentRepresentationTest : BaseRunConcurrentRepresentationTest<Unit>(
    "run_concurrent_test/coroutines/coroutines"
) {
    @Before
    fun setUp() {
        assumeFalse(isInTraceDebuggerMode) // unstable hash-code
        // TODO: investigate why test is unstable on these JDKs
        assumeFalse(jdkVersion == JdkVersion.JDK_8)
        assumeFalse(jdkVersion == JdkVersion.JDK_11)
        assumeFalse(jdkVersion == JdkVersion.JDK_13)
    }

    companion object {
        @JvmStatic var sharedCounter = 0

        @JvmStatic var r1 = -1
        @JvmStatic var r2 = -1

        private val channel1 = Channel<Int>(capacity = 1)
        private val channel2 = Channel<Int>(capacity = 1)
    }

    override fun block() {
        val executorService = createFixedThreadPool(2)
        executorService.asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher + CoroutineName("Coroutine-0")) {
                // set coroutines' names explicitly to make them deterministic within the Lincheck test
                val job1 = launch(dispatcher + CoroutineName("Coroutine-1")) {
                    channel1.send(sharedCounter++)
                    r1 = channel2.receive()
                }
                val job2 = launch(dispatcher + CoroutineName("Coroutine-2")) {
                    channel2.send(sharedCounter++)
                    r2 = channel1.receive()
                }
                job1.join()
                job2.join()
                check(r1 == 1 || r2 == 1)
            }
        }
    }
}

private fun createFixedThreadPool(nThreads: Int): ExecutorService {
    val threadNumber = AtomicInteger(0)
    return Executors.newFixedThreadPool(nThreads) { runnable ->
        // set threads' names explicitly to make them deterministic within the Lincheck test
        Thread(runnable, "Thread-${threadNumber.incrementAndGet()}")
    }
}