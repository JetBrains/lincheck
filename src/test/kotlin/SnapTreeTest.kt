import SnapTree.SnapTreeMap
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param

class SnapTreeTest : AbstractConcurrentMapTest<SnapTreeMap<Long, Int>>(SnapTreeMap()) {
    // this operation is disabled for SnapTree, since it's bugged even in the sequential case
    override fun removeIf(key: Long, value: Int): Boolean {
        return false
    }

    @Operation(handleExceptionsAsResult = [NoSuchElementException::class])
    fun firstKey(): Long = map.firstKey()

    @Operation(handleExceptionsAsResult = [NoSuchElementException::class])
    fun lastKey(): Long = map.lastKey()

    @Operation
    fun lowerKey(@Param(name = "key") key: Long): Long? = map.lowerKey(key)

    @Operation
    fun higherKey(@Param(name = "key") key: Long): Long? = map.higherKey(key)
}