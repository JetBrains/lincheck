package org.jetbrains.lincheck_test.project

import org.jetbrains.lincheck.*
import kotlin.concurrent.thread
import kotlin.test.Test

class GPMCCounterTest {

    @Test
    fun test() {
        Lincheck.runConcurrentTest {
            var c = 0
            val t1 = thread {
                c++
            }
            val t2 = thread {
                c++
            }
            t1.join()
            t2.join()
            check(c == 2)
        }
    }

}