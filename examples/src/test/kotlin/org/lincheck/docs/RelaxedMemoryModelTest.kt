package org.lincheck.docs

import org.jetbrains.lincheck.Lincheck
import kotlin.concurrent.thread
import kotlin.test.Test

class RelaxedMemoryModelTest {
    var x = 0 // Not @Volatile
    var y = 0 // Not @Volatile

    @Test
    fun modelCheckingTest() = Lincheck.runConcurrentTest {
        thread {
            x = 1
            y = 1
        }
        thread {
            if (y == 1 && x == 0) {
                // Code in this block might be executed on real hardware because of
                // store buffer and compiler reordering.
                // Lincheck cannot model this behavior with model checking.
                error("Unreachable under sequential consistency")
            }
        }
    }
}
