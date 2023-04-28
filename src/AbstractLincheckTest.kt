import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test

/**
 * Base test class with fast and long configurations.
 */
abstract class AbstractLincheckTest {
    @Test
    fun stressTestFast() {
        StressOptions().apply {
            fastConfiguration()
            customize() // for test-specific features
            check(this@AbstractLincheckTest::class)
        }
    }

    @Test
    fun stressTestLong() {
        StressOptions().apply {
            longConfiguration()
            customize() // for test-specific features
            check(this@AbstractLincheckTest::class)
        }
    }

    @Test
    fun modelCheckingTestFast() {
        ModelCheckingOptions().apply {
            fastConfiguration()
            customize() // for test-specific features
            check(this@AbstractLincheckTest::class)
        }
    }

    @Test
    fun modelCheckingTestLong() {
        ModelCheckingOptions().apply {
            longConfiguration()
            customize() // for test-specific features
            check(this@AbstractLincheckTest::class)
        }
    }

    open fun <O: Options<O, *>> O.customize() {}

    private fun <O: Options<O, *>> O.fastConfiguration() {
        iterations(30)
        threads(2)
        actorsPerThread(3)
        when (this) {
            // Smart cast as invocations are not a general property
            is StressOptions -> invocationsPerIteration(1000)
            is ModelCheckingOptions -> invocationsPerIteration(1000)
        }
        requireStateEquivalenceImplCheck(false) // removes a warning about possible optimization
    }

    private fun <O: Options<O, *>> O.longConfiguration() {
        iterations(100)
        threads(3)
        actorsPerThread(4)
        when (this) {
            // Smart cast as invocations are not a general property
            is StressOptions -> invocationsPerIteration(10000)
            is ModelCheckingOptions -> invocationsPerIteration(10000)
        }
        requireStateEquivalenceImplCheck(false) // removes a warning about possible optimization
    }
}