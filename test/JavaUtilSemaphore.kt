import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation

/*
 * Note that this test requires custom Lincheck branch, which is included in the CAV submission.
 */

class JavaUtilSemaphore : AbstractLincheckTest() {
    private val semaphore = java.util.concurrent.Semaphore(1, true)

    @Operation(cancellableOnSuspension = true)
    fun acquire() {
        semaphore.acquire()
    }

    @Operation
    fun release() {
        semaphore.release()
    }

    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(0)
        actorsAfter(0)
        sequentialSpecification(SemaphoreSequential::class.java)
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