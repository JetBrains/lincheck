package org.jetbrains.lincheck_test.project

import kotlinx.atomicfu.atomic
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * Synthetic test to show what's happening when big with 4 threads found
 */
class ManyDisplayedThreadsBugTest {

    private val sharedState = AtomicInteger(0)

    private val counter = AtomicInteger(0)

    @Operation
    fun operation(): Int {
        sharedState.incrementAndGet()
        if (someOtherOperation()) return 1
        sharedState.incrementAndGet()

        return 0
    }

    private fun someOtherOperation(): Boolean {
        counter.incrementAndGet()
        if (counter.get() == 4) {
            return true
        }
        counter.decrementAndGet()
        return false
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            initial {
                actor(ManyDisplayedThreadsBugTest::operation)
                actor(ManyDisplayedThreadsBugTest::operation)
                actor(ManyDisplayedThreadsBugTest::operation)
            }
            parallel {
                thread { actor(ManyDisplayedThreadsBugTest::operation) }
                thread { actor(ManyDisplayedThreadsBugTest::operation) }
                thread { actor(ManyDisplayedThreadsBugTest::operation) }
                thread { actor(ManyDisplayedThreadsBugTest::operation) }
            }
        }
        .threads(4).check(this::class.java)

}