import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Ignore
import org.junit.Test

//    This test does not work in the artifact as it requires custom Lincheck branch to detect this liveliness error.
//    Note that the report for the test is already present at reports.md

class AbstractQueueSynchronizerTest {
    private val semaphore = java.util.concurrent.Semaphore(1, true)

    @Operation(cancellableOnSuspension = true)
    fun acquire() {
        semaphore.acquire()
    }

    @Operation
    fun release() {
        semaphore.release()
    }

    @Ignore @Test
    fun test() {
        ModelCheckingOptions()
            .actorsBefore(0)
            .actorsAfter(0)
            .sequentialSpecification(SemaphoreSequential::class.java)
            .check(this::class.java)
    }


}