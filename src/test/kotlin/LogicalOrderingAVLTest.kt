import LogicalOrderingAVL.LogicalOrderingAVL
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test

class LogicalOrderingAVLTest : IntIntAbstractConcurrentMapTest<LogicalOrderingAVL<Int, Int>>(LogicalOrderingAVL()) {
    override fun ModelCheckingOptions.customize(): ModelCheckingOptions =
        threads(3).actorsPerThread(3)
}

// This test find a different error than the previous one. Note that the output is large
class LogicalOrderingAVLTest2 : IntIntAbstractConcurrentMapTest<LogicalOrderingAVL<Int, Int>>(LogicalOrderingAVL())