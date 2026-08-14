package org.lincheck.docs

import org.jetbrains.lincheck.Lincheck
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class CounterTestWithInvokations {
    @Test
    fun test() = Lincheck.runConcurrentTest(100_000) {
        var counter = 0

        // Increments the counter concurrently
        val t1 = thread { counter++ }
        val t2 = thread { counter++ }

        // Waits for the threads to finish
        t1.join()
        t2.join()

        // Checks that both increments have been applied
        assertEquals(2, counter)
    }
}