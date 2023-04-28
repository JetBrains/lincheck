package tests

import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory
import com.googlecode.concurrenttrees.suffix.ConcurrentSuffixTree
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.StringGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test


@Param(name = "key", gen = StringGen::class, conf = "4:ab")
class ConcurrentSuffixTreeTest : AbstractLincheckTest() {
    private val suffixTree = ConcurrentSuffixTree<Int>(DefaultCharArrayNodeFactory())

    @Operation
    fun getKeysContaining(@Param(name = "key") key: String) =
        // ignore the order of output strings
        suffixTree.getKeysContaining(key).map { it.toString() }.sortedWith(String.CASE_INSENSITIVE_ORDER).toString()

    @Operation
    fun getKeysEndingWith(@Param(name = "key") key: String) =
        suffixTree.getKeysEndingWith(key).map { it.toString() }.sortedWith(String.CASE_INSENSITIVE_ORDER).toString()

    @Operation
    fun getValuesForKeysEndingWith(@Param(name = "key") key: String) =
        suffixTree.getValuesForKeysEndingWith(key).sorted().toString()

    @Operation
    fun getValueForExactKey(@Param(name = "key")key: String) = suffixTree.getValueForExactKey(key)

    @Operation
    fun put(@Param(name = "key") key: String, value: Int) = if (key.length != 0) suffixTree.put(key, value).toString() else 0
}