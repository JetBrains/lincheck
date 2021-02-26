package org.jetbrains.kotlinx.lincheck.test.distributed.common

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.distributed.queue.FastQueue
import org.junit.Test
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class NodeExecutorTest {
    val expectedValues = HashSet<String>()
    val queue = FastQueue<String>()
    val finished = atomic(0)
    val numberOfThreads = 3
    val iterations = 10000

    val consumer = thread(start = false) {
        while (finished.value != numberOfThreads) {
            val s = queue.poll()
            if (s != null) {
                expectedValues.add(s)
            }
        }
        while (true) {
            val s = queue.poll() ?: return@thread
            expectedValues.add(s)
        }
    }

    val producers = Array(numberOfThreads) {
        thread(start = false) {
            repeat(iterations) { i ->
                queue.put("$it-$i")
            }
            finished.incrementAndGet()
        }
    }

    @Test
    fun test() {
        consumer.start()
        producers.forEach { it.start() }
        producers.forEach { it.join() }
        consumer.join()
        println(expectedValues.size)
        println(queue.poll())
        for (i in 0 until numberOfThreads) {
            repeat(iterations) {
                check(expectedValues.contains("$i-$it")) {
                    "$i-$it not present"
                }
            }
        }
        check(expectedValues.size == numberOfThreads * iterations)
    }

    @Test
    fun testSimple() {
        producers.forEach { it.start() }
        producers.forEach { it.join() }
        println(expectedValues.size)
        repeat(numberOfThreads * iterations) {
            println(queue.poll())
        }
    }

    @Test
    fun testSingleThread() {
        repeat(3) {
            println(queue.poll())
        }
        repeat(5) {
            queue.put(it.toString())
        }
        println()
        repeat(10) {
            println(queue.poll())
        }
        println()
        repeat(5) {
            queue.put(it.toString())
        }
        println()
        repeat(10) {
            println(queue.poll())
        }
    }
}