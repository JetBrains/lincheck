package org.lincheck.docs

import org.jetbrains.lincheck.Lincheck
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class VariableResetTest {
    companion object {
        private var atomicInt = AtomicInteger(0)
    }

    @Test
    @Order(1)
    fun modelCheckingTest() = Lincheck.runConcurrentTest {
        val t1 = thread { atomicInt.getAndIncrement() }
        val t2 = thread { atomicInt.getAndIncrement() }

        t1.join()
        t2.join()

        check(atomicInt.get() == 2)
    }

    @Test
    @Order(2)
    fun resetAfterModelCheckingTest() {
        // Verify `atomicInt` has been reset to 0 after `modelCheckingTest()`
        check(atomicInt.get() == 0)
    }

    @Test
    @Order(3)
    fun regularIncTest() {
        atomicInt.getAndIncrement()
        check(atomicInt.get() == 1)
    }

    @Test
    @Order(4)
    fun valuePersistsAfterRegularIncTest() {
        // Verify `atomicInt` still holds 1 after `regularIncTest()`
        check(atomicInt.get() == 1)
    }
}
