package fuzzing

import fuzzing.ConcurrencyOptimalTreeMap.ConcurrencyOptimalTreeMap
import fuzzing.utils.AbstractFuzzerBenchmarkTest
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.coverage.CoverageOptions
import org.jetbrains.kotlinx.lincheck.fuzzing.coverage.toCoverage
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test
import kotlin.reflect.jvm.jvmName

@Param(name = "key", gen = IntGen::class, conf = "1:5")
@Param(name = "value", gen = IntGen::class, conf = "1:8")
class ConcurrencyOptimalMapTest : AbstractFuzzerBenchmarkTest() {
    override fun <O : Options<O, *>> O.customize() {
        actorsAfter(0)
        actorsBefore(0)
    }

    override fun <O : Options<O, *>> O.customizeModelCheckingCoverage() =
        coverageConfigurationForModelChecking(
            listOf(
                ConcurrencyOptimalMapTest::class.jvmName
            ),
            emptyList()
        )

    override fun <O : Options<O, *>> O.customizeFuzzingCoverage() =
        coverageConfigurationForFuzzing(
            listOf(
                ConcurrencyOptimalMapTest::class.jvmName
            ),
            emptyList()
        )

//    @Test
//    fun modelCheckingTestLong() {
//        ModelCheckingOptions().apply {
//            invocationsPerIteration(10000)
//            iterations(100)
//            threads(3)
//            actorsPerThread(4)
//            actorsAfter(0)
//            actorsBefore(0)
//            withCoverage(CoverageOptions(
//                excludePatterns = listOf(this@ConcurrencyOptimalMapTest::class.jvmName),
//                fuzz = true
//            ) { pr, res ->
//                println("Coverage: edges=${pr.toCoverage().coveredBranchesCount()}, " +
//                        "branch=${res.branchCoverage}/${res.totalBranches}, " +
//                        "line=${res.lineCoverage}/${res.totalLines}")
//            })
//            check(this@ConcurrencyOptimalMapTest::class)
//        }
//    }

    private val map: ConcurrencyOptimalTreeMap<Int, Int> = ConcurrencyOptimalTreeMap()

    @Operation(handleExceptionsAsResult = [NullPointerException::class])
    fun putIfAbsent(@Param(name = "key") key: Int, @Param(name = "value") value: Int): Int? =
        map.putIfAbsent(key, value)

    @Operation(handleExceptionsAsResult = [NullPointerException::class])
    fun remove(@Param(name = "key") key: Int): Int? = map.remove(key)

    @Operation
    fun get(@Param(name = "key") key: Int): Int? = map.get(key)

    @Operation
    fun containsKey(@Param(name = "key") key: Int): Boolean = map.containsKey(key)
}