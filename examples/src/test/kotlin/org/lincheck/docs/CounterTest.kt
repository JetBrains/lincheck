package org.lincheck.docs

import org.jetbrains.lincheck.Lincheck
import kotlin.test.*
import kotlin.concurrent.thread

class CounterTest {
    @Test // Test function declaration
    fun test() = Lincheck.runConcurrentTest {
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