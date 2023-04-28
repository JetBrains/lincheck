package tests

import ConcurrencyOptimalTreeMap.ConcurrencyOptimalTreeMap
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.LongGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test

@Param(name = "key", gen = IntGen::class, conf = "1:5")
@Param(name = "value", gen = IntGen::class, conf = "1:8")
class ConcurrencyOptimalMapTest : AbstractLincheckTest() {
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