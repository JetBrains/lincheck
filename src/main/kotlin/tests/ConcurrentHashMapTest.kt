package tests

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import java.util.concurrent.ConcurrentHashMap

@Param(name = "key", gen = IntGen::class, conf = "1:5")
class ConcurrentHashMapTest : AbstractLincheckTest() {
    private val map = ConcurrentHashMap<Int, Int>()

    @Operation
    fun put(@Param(name = "key") key: Int, value: Int) = map.put(key, value)

    @Operation
    operator fun get(@Param(name = "key") key: Int) = map[key]

    @Operation
    fun remove(@Param(name = "key") key: Int) = map.remove(key)
}