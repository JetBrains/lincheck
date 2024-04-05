package fuzzing.utils

import fuzzing.SnapTreeTest
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.LongGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test
import java.util.concurrent.ConcurrentMap
import kotlin.reflect.jvm.jvmName

@Param(name = "key", gen = LongGen::class, conf = "1:4")
@Param(name = "value", gen = IntGen::class, conf = "1:8")
abstract class AbstractConcurrentMapTest<MAP : ConcurrentMap<Long, Int>>(val map: MAP) : AbstractFuzzerBenchmarkTest() {
//    override fun <O: Options<O, *>> O.customize() {
//        iterations(100)
//        threads(3)
//        actorsPerThread(4)
//        when (this) {
//            // Smart cast as invocations are not a general property
//            is StressOptions -> invocationsPerIteration(10000)
//            is ModelCheckingOptions -> invocationsPerIteration(10000)
//        }
//    }

//    @Test
//    fun modelCheckingTestLong() {
//        ModelCheckingOptions().apply {
//            longConfiguration()
//            customize() // for test-specific features
//            check(this@AbstractConcurrentMapTest::class)
//        }
//    }

//    private fun <O: Options<O, *>> O.longConfiguration() {
//        iterations(100)
//        threads(3)
//        actorsPerThread(4)
//        when (this) {
//            // Smart cast as invocations are not a general property
//            is StressOptions -> invocationsPerIteration(10000)
//            is ModelCheckingOptions -> invocationsPerIteration(10000)
//        }
//    }


    @Operation
    operator fun get(@Param(name = "key") key: Long): Int? = map.get(key)

    @Operation
    fun put(@Param(name = "key") key: Long, @Param(name = "value") value: Int): Int? = map.put(key, value)

    @Operation
    fun replace(
        @Param(name = "key") key: Long,
        @Param(name = "value") expected: Int,
    ) = map.replace(key, expected)

    @Operation
    fun replace(
        @Param(name = "key") key: Long,
        @Param(name = "value") expected: Int,
        @Param(name = "value") newValue: Int
    ): Boolean = map.replace(key, expected, newValue)

    @Operation
    fun remove(@Param(name = "key") key: Long): Int? = map.remove(key)

    @Operation
    open fun removeIf(@Param(name = "key") key: Long, @Param(name = "value") value: Int): Boolean = map.remove(key, value)

    @Operation
    fun containsKey(@Param(name = "key") key: Long): Boolean = map.containsKey(key)

    @Operation
    fun containsValue(@Param(name = "value") value: Int): Boolean = map.containsValue(value)

    @Operation
    fun putIfAbsent(@Param(name = "key") key: Long, @Param(name = "value") value: Int): Int? = map.putIfAbsent(key, value)

    @Operation
    fun getOrPut(@Param(name = "key") key: Long, @Param(name = "value") value: Int): Int? = map.getOrPut(key, { value })
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
@Param(name = "value", gen = IntGen::class, conf = "1:8")
abstract class IntIntAbstractConcurrentMapTest<MAP : ConcurrentMap<Int, Int>>(val map: MAP) : AbstractFuzzerBenchmarkTest() {
//    @Test
//    fun modelCheckingTestLong() {
//        ModelCheckingOptions().apply {
//            invocationsPerIteration(10000)
//            iterations(100)
//            threads(3)
//            actorsPerThread(4)
//            customize()
//            check(this@IntIntAbstractConcurrentMapTest::class)
//        }
//    }

    @Operation
    operator fun get(@Param(name = "key") key: Int): Int? = map.get(key)

    @Operation
    fun put(@Param(name = "key") key: Int, @Param(name = "value") value: Int): Int? = map.put(key, value)

    @Operation
    fun replace(
        @Param(name = "key") key: Int,
        @Param(name = "value") expected: Int,
    ) = map.replace(key, expected)

    @Operation
    fun replace(
        @Param(name = "key") key: Int,
        @Param(name = "value") expected: Int,
        @Param(name = "value") newValue: Int
    ): Boolean = map.replace(key, expected, newValue)

    @Operation
    fun remove(@Param(name = "key") key: Int): Int? = map.remove(key)

    @Operation
    open fun removeIf(@Param(name = "key") key: Int, @Param(name = "value") value: Int): Boolean = map.remove(key, value)

    @Operation
    fun containsKey(@Param(name = "key") key: Int): Boolean = map.containsKey(key)

    @Operation
    fun putIfAbsent(@Param(name = "key") key: Int, @Param(name = "value") value: Int): Int? = map.putIfAbsent(key, value)

    @Operation
    fun getOrPut(@Param(name = "key") key: Int, @Param(name = "value") value: Int): Int? = map.getOrPut(key, { value })
}