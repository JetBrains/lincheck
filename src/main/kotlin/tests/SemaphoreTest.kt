package tests

import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.annotations.Operation

class SemaphoreTest : AbstractLincheckTest() {
    private val semaphore = Semaphore(1)

    @Operation(cancellableOnSuspension = true)
    suspend fun acquire() {
        semaphore.acquire()
    }

    @Operation
    fun release() {
        semaphore.release()
    }
}