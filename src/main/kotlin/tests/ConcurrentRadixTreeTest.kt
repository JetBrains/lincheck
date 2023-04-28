package tests

import com.googlecode.concurrenttrees.radix.ConcurrentRadixTree
import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.StringGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test


@Param(name = "key", gen = StringGen::class, conf = "4:ab")
class ConcurrentRadixTreeTest : AbstractLincheckTest() {
    private val radixTree = ConcurrentRadixTree<Int>(DefaultCharArrayNodeFactory())

    @Operation
    fun getKeyValuePairsForKeysStartingWith(@Param(name = "key") key: String) =
        // ignore the order of output strings
        radixTree.getKeyValuePairsForKeysStartingWith(key).map { it.toString() }.sortedWith(String.CASE_INSENSITIVE_ORDER).toString()

    @Operation
    fun getValueForExactKey(@Param(name = "key")key: String) = radixTree.getValueForExactKey(key)

    @Operation
    fun put(@Param(name = "key") key: String, value: Int) = if (key.length != 0) radixTree.put(key, value).toString() else 0
}