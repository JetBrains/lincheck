package fuzzing

import fuzzing.utils.AbstractFuzzerBenchmarkTest
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.junit.Test
import kotlin.reflect.jvm.jvmName

/*
 * Note that this test requires custom Lincheck branch, which is included in the CAV submission.
 */

class AbstractQueueSynchronizerTest : AbstractFuzzerBenchmarkTest() {
    private val semaphore = java.util.concurrent.Semaphore(1, true)

    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(0)
        actorsAfter(0)
        sequentialSpecification(SemaphoreSequential::class.java)
    }

    override fun <O : Options<O, *>> O.customizeModelCheckingCoverage() =
        coverageConfigurationForModelChecking(
            listOf(
                AbstractQueueSynchronizerTest::class.jvmName
            ),
            emptyList()
        )

    override fun <O : Options<O, *>> O.customizeFuzzingCoverage() =
        coverageConfigurationForFuzzing(
            listOf(
                AbstractQueueSynchronizerTest::class.jvmName
            ),
            emptyList()
        )

    @Operation(cancellableOnSuspension = true)
    fun acquire() = semaphore.acquire()

    @Operation
    fun release() = semaphore.release()

//    @Test
//    fun modelCheckingTestLong() {
//        ModelCheckingOptions().apply {
//            longConfiguration()
//            customize() // for test-specific features
//            check(this@AbstractQueueSynchronizerTest::class)
//        }
//    }
//
//    fun <O: Options<O, *>> O.customize() {
//        actorsBefore(0)
//        actorsAfter(0)
//        sequentialSpecification(SemaphoreSequential::class.java)
//    }
//
//    private fun <O: Options<O, *>> O.longConfiguration() {
//        iterations(100)
//        threads(3)
//        actorsPerThread(4)
//        when (this) {
//            // Smart cast as invocations are not a general property
//            is StressOptions -> invocationsPerIteration(10000)
//            is ModelCheckingOptions -> invocationsPerIteration(10000)
//        }
//    }
}

class SemaphoreSequential {
    private val s = kotlinx.coroutines.sync.Semaphore(100, 99)

    suspend fun acquire() = s.acquire()

    fun release() = s.release()
}