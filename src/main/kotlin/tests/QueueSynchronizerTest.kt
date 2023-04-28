package tests

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions

class QueueSynchronizerTest {
    private val semaphore = java.util.concurrent.Semaphore(1, true)

    @Operation(cancellableOnSuspension = true)
    fun acquire() {
        semaphore.acquire()
    }

    @Operation
    fun release() {
        semaphore.release()
    }

//    TODO: does not work yet: uses custom lincheck branch.
//    Note that the report is already present at reports.md
//    @Test
    fun test() {
        ModelCheckingOptions()
            .actorsBefore(0)
            .actorsAfter(0)
            .sequentialSpecification(SemaphoreSequential::class.java)
            .check(this::class.java)
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