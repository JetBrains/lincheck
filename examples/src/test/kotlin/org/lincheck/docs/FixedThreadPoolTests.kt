package org.lincheck.docs

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck.Lincheck
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

private const val nThreads = 2

class FixedThreadPoolDispatcherTest {
    @Test
    fun test() = Lincheck.runConcurrentTest {
        val dispatcher = Executors.newFixedThreadPool(nThreads).asCoroutineDispatcher()
        dispatcher.use {
            runBlocking(dispatcher) {
                val counter = AtomicInteger(0)
                val coro = launch {
                    while (isActive) { counter.getAndIncrement() }
                }
                coro.cancel()
                coro.join()
            }
        }
    }
}

class FixedThreadPoolExecutorServiceTest {
    @Test
    fun test() = Lincheck.runConcurrentTest {
        val executorService = Executors.newFixedThreadPool(nThreads)
        try {
            val counter = AtomicInteger(0)
            val task = Runnable { counter.getAndIncrement() }
            val future1 = executorService.submit(task)
            val future2 = executorService.submit(task)
            future1.get()
            future2.get()
        } finally {
            executorService.shutdown()
        }
    }
}
