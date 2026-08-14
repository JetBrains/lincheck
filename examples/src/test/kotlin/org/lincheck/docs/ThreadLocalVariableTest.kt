package org.lincheck.docs

import org.jetbrains.lincheck.Lincheck
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test

class ThreadLocalVariableTest {
    @Test
    fun modelCheckingTest() = Lincheck.runConcurrentTest {
        var counter = getLocalCounter()
        var t = thread { counter.getAndIncrement() }
        t.join()
        check(counter.get() == 1)
    }

    private fun getLocalCounter() = localCounter.get()
}

// Using ThreadLocal to create a variable leads to a failed test
private val localCounter: ThreadLocal<AtomicInteger> = ThreadLocal.withInitial {
    AtomicInteger(0)
}

class ThreadLocalVariableWorkaroundTest {
    var threadLocalCounters = ConcurrentHashMap<Long, AtomicInteger>()

    @Test
    fun modelCheckingTest() = Lincheck.runConcurrentTest {
        var counter = getLocalCounter()
        var t = thread { counter.getAndIncrement() }
        t.join()
        check(counter.get() == 1)
    }

    private fun getLocalCounter() = threadLocalCounters.computeIfAbsent(Thread.currentThread().id) {
        AtomicInteger(0)
    }
}
