package fuzzing

import com.googlecode.concurrenttrees.radix.ConcurrentRadixTree
import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory
import fuzzing.utils.AbstractConcurrentMapTest
import fuzzing.utils.AbstractFuzzerBenchmarkTest
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.coverage.CoverageOptions
import org.jetbrains.kotlinx.lincheck.paramgen.StringGen
import org.jetbrains.kotlinx.lincheck.scenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck_test.verifier.linearizability.LockFreeSet
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.reflect.jvm.jvmName


@Param(name = "key", gen = StringGen::class, conf = "4:ab")
class ConcurrentRadixTreeTest : AbstractFuzzerBenchmarkTest() {
    private val radixTree = ConcurrentRadixTree<Int>(DefaultCharArrayNodeFactory())

    @Operation
    fun getKeyValuePairsForKeysStartingWith(@Param(name = "key") key: String) =
        // ignore the order of output strings as it not important
        radixTree.getKeyValuePairsForKeysStartingWith(key)
            .map { it.toString() }.sortedWith(String.CASE_INSENSITIVE_ORDER).toString()

    @Operation
    fun getValueForExactKey(@Param(name = "key") key: String) = radixTree.getValueForExactKey(key)

    @Operation
    fun put(@Param(name = "key") key: String, value: Int) = if (key.length != 0) radixTree.put(key, value).toString() else 0

    //@Test(expected = AssertionError::class)
    fun knownFailingTest() {
        // Fails as expected
        val scenario = scenario {
            initial {
                actor(ConcurrentRadixTreeTest::put, "aaa", 2)
            }
            parallel {
                thread {
                    actor(ConcurrentRadixTreeTest::put, "aba", -6)
                    actor(ConcurrentRadixTreeTest::getKeyValuePairsForKeysStartingWith, "")
                }
                thread {
                    actor(ConcurrentRadixTreeTest::put, "ab", 4)
                    actor(ConcurrentRadixTreeTest::put, "aa", 5)
                }
            }
        }

        ModelCheckingOptions()
            .addCustomScenario(scenario)
            .invocationsPerIteration(1_000_000)
            .iterations(0)
            .logLevel(LoggingLevel.INFO)
            .check(this::class)
    }

    override fun <O : Options<O, *>> O.customize() {
        threads(2)
        //logLevel(LoggingLevel.INFO)
    }


    override fun <O : Options<O, *>> O.customizeModelCheckingCoverage() =
        coverageConfigurationForModelChecking(
            listOf(
                ConcurrentRadixTreeTest::class.jvmName
            ),
            emptyList()
        )

    override fun <O : Options<O, *>> O.customizeFuzzingCoverage() =
        coverageConfigurationForFuzzing(
            listOf(
                ConcurrentRadixTreeTest::class.jvmName
            ),
            emptyList()
        )

//    @Test
//    fun modelCheckingTestLong() {
//        ModelCheckingOptions().apply {
//            iterations(100)
//            threads(2)
//            actorsPerThread(4)
//            invocationsPerIteration(10000)
//            // logLevel(LoggingLevel.INFO)
//            check(this@ConcurrentRadixTreeTest::class)
//        }
//    }
}