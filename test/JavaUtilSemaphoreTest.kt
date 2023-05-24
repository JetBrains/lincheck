import org.jetbrains.kotlinx.lincheck_custom.*
import org.jetbrains.kotlinx.lincheck_custom.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_custom.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck_custom.annotations.Operation
import org.junit.Test

/*
 * Note that this test requires custom Lincheck branch, which is included in the CAV submission.
 */

class JavaUtilSemaphoreTest {
    private val semaphore = java.util.concurrent.Semaphore(1, true)

    @Operation(cancellableOnSuspension = true)
    fun acquire() {
        semaphore.acquire()
    }

    @Operation
    fun release() {
        semaphore.release()
    }

    @Test
    fun stressTestFast() {
        StressOptions().apply {
            fastConfiguration()
            customize() // for test-specific features
            check(this@JavaUtilSemaphoreTest::class)
        }
    }

    @Test
    fun stressTestLong() {
        StressOptions().apply {
            longConfiguration()
            customize() // for test-specific features
            check(this@JavaUtilSemaphoreTest::class)
        }
    }

    @Test
    fun modelCheckingTestFast() {
        ModelCheckingOptions().apply {
            fastConfiguration()
            customize() // for test-specific features
            check(this@JavaUtilSemaphoreTest::class)
        }
    }

    @Test
    fun modelCheckingTestLong() {
        ModelCheckingOptions().apply {
            longConfiguration()
            customize() // for test-specific features
            check(this@JavaUtilSemaphoreTest::class)
        }
    }

    fun <O: Options<O, *>> O.customize() {
        actorsBefore(0)
        actorsAfter(0)
        sequentialSpecification(SemaphoreSequential::class.java)
    }

    private fun <O: Options<O, *>> O.fastConfiguration() {
        iterations(30)
        threads(2)
        actorsPerThread(3)
        when (this) {
            // Smart cast as invocations are not a general property
            is StressOptions -> invocationsPerIteration(1000)
            is ModelCheckingOptions -> invocationsPerIteration(1000)
        }
        requireStateEquivalenceImplCheck(false) // removes a warning about possible optimization
    }

    private fun <O: Options<O, *>> O.longConfiguration() {
        iterations(100)
        threads(3)
        actorsPerThread(4)
        when (this) {
            // Smart cast as invocations are not a general property
            is StressOptions -> invocationsPerIteration(10000)
            is ModelCheckingOptions -> invocationsPerIteration(10000)
        }
        requireStateEquivalenceImplCheck(false) // removes a warning about possible optimization
    }
}

class SemaphoreSequential {
    private val s = kotlinx.coroutines.sync.Semaphore(100, 99)

    suspend fun acquire() {
        s.acquire()
    }

    fun release() {
        s.release()
    }
}