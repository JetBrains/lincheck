package tests

import CATreeMapAVL.CATreeMapAVL
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.LongGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test

// CATreeMapAVL does not implement ConcurrentMap interface,
// so we cannot re-use AbstractConcurrentMapTest here
@Param(name = "key", gen = LongGen::class, conf = "1:5")
@Param(name = "value", gen = IntGen::class, conf = "1:8")
class CATreeTest : AbstractLincheckTest() {
    private val map = CATreeMapAVL<Long, Int>()

    @Operation
    operator fun get(@Param(name = "key") key: Long): Int? = map.get(key)

    @Operation
    fun put(@Param(name = "key") key: Long, @Param(name = "value") value: Int): Int? = map.put(key, value)

    @Operation
    fun remove(@Param(name = "key") key: Long): Int? = map.remove(key)

    @Operation
    fun containsKey(@Param(name = "key") key: Long): Boolean = map.containsKey(key)

    @Operation
    fun putIfAbsent(@Param(name = "key") key: Long, @Param(name = "value") value: Int): Int? = map.putIfAbsent(key, value)

    @Operation
    fun size(): Int = map.size

    @Operation
    fun clear() = map.clear()

    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(0)
        actorsAfter(0)
//        actorsPerThread(2)
    }
}