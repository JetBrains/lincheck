import com.googlecode.concurrenttrees.radix.ConcurrentRadixTree
import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.StringGen


@Param(name = "key", gen = StringGen::class, conf = "4:ab")
class ConcurrentRadixTreeTest : AbstractLincheckTest() {
    private val radixTree = ConcurrentRadixTree<Int>(DefaultCharArrayNodeFactory())

    @Operation
    fun getKeyValuePairsForKeysStartingWith(@Param(name = "key") key: String) =
        // ignore the order of output strings as it not important
        radixTree.getKeyValuePairsForKeysStartingWith(key).map { it.toString() }.sortedWith(String.CASE_INSENSITIVE_ORDER).toString()

    @Operation
    fun getValueForExactKey(@Param(name = "key") key: String) = radixTree.getValueForExactKey(key)

    @Operation
    fun put(@Param(name = "key") key: String, value: Int) = if (key.length != 0) radixTree.put(key, value).toString() else 0
}