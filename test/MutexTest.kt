import kotlinx.coroutines.sync.Mutex
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions

class MutexTest : AbstractLincheckTest() {
    private val mutex = Mutex()

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    fun unlock() = mutex.unlock()

    @Operation
    fun tryLock() = mutex.tryLock()

    @Operation(promptCancellation = true)
    suspend fun lock() = mutex.lock()

    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(1)
        actorsAfter(0)
        minimizeFailedScenario(false)
        if (this is ModelCheckingOptions)
            checkObstructionFreedom()
    }
}