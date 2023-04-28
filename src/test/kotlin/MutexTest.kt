import kotlinx.coroutines.sync.Mutex
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions

class MutexTest : AbstractLincheckTest() {
    private val mutex = Mutex()

    @Operation
    fun tryLock() = mutex.tryLock()

    @Operation(promptCancellation = true)
    suspend fun lock() = mutex.lock()

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    fun unlock() = mutex.unlock()


    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(0)
//        actorsPerThread(3)
        if (this is ModelCheckingOptions)
            checkObstructionFreedom()
    }
}