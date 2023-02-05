import kotlinx.coroutines.sync.Mutex
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test

class MutexLincheckTest {
    private val mutex = Mutex()

    @Operation
    fun tryLock() = mutex.tryLock()

    @Operation(promptCancellation = true)
    suspend fun lock() = mutex.lock()

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    fun unlock() = mutex.unlock()

    @Test
    fun test() {
        ModelCheckingOptions()
            .checkObstructionFreedom()
            .actorsBefore(0)
            .actorsPerThread(3)
            .check(this::class)
    }
}