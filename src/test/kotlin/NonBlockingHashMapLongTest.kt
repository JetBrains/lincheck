import org.jctools.maps.NonBlockingHashMapLong
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.LongGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test


@Param(name = "key", gen = LongGen::class, conf = "1:5")
@Param(name = "value", gen = IntGen::class, conf = "1:8")
class NonBlockingHashMapLongTest {
    private val map = NonBlockingHashMapLong<Int>()

    @Operation
    operator fun get(@Param(name = "key") key: Long): Int? = map.get(key)

    @Operation
    fun put(@Param(name = "key") key: Long, @Param(name = "value") value: Int): Int? = map.put(key, value)

    @Operation
    fun replace(
        @Param(name = "key") key: Long,
        @Param(name = "value") expected: Int,
        @Param(name = "value") newValue: Int
    ): Boolean = map.replace(key, expected, newValue)

    @Operation
    fun remove(@Param(name = "key") key: Long): Int? = map.remove(key)

    @Operation
    fun containsKey(@Param(name = "key") key: Long): Boolean = map.containsKey(key)

    @Operation
    fun containsValue(@Param(name = "value") value: Int): Boolean = map.containsValue(value)

    @Operation
    fun putIfAbsent(@Param(name = "key") key: Long, @Param(name = "value") value: Int): Int? = map.putIfAbsent(key, value)

    @Operation
    fun getOrPut(@Param(name = "key") key: Long, @Param(name = "value") value: Int): Int? = map.getOrPut(key, { value })

    @Test
    fun test() {
        ModelCheckingOptions().check(this::class)
    }
}