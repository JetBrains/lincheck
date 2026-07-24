package org.lincheck.docs

import org.jetbrains.lincheck.*
import kotlin.test.Test
import java.util.concurrent.*
import kotlin.concurrent.*

// This test demonstrates a deadlock caused by two threads
// performing nested `computeIfAbsent` calls in opposite order.
class ConcurrentHashMapDeadlockTest {
    @Test
    fun test() = Lincheck.runConcurrentTest {
        val map = ConcurrentHashMap<String, String>()

        // Updates `key2` while locking `key1`.
        val thread1 = thread {
            map.computeIfAbsent("key1") {
                map.computeIfAbsent("key2") { "value2" }
                "value1"
            }
        }
        
        // Updates `key1` while locking `key2`.
        val thread2 = thread {
            map.computeIfAbsent("key2") {
                map.computeIfAbsent("key1") { "value1" }
                "value2"
            }
        }

        // Wait until both threads complete.
        thread1.join()
        thread2.join()
    }
}