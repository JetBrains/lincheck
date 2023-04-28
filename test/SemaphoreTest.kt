import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation

class SemaphoreTest : AbstractLincheckTest() {
    private val semaphore = Semaphore(1)

    @Operation
    fun tryAcquire() = semaphore.tryAcquire()

    @Operation
    suspend fun acquire() = semaphore.acquire()

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    fun release() = semaphore.release()

    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(0)
    }
}
