package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.forClasses
import kotlin.test.Test
import java.util.concurrent.ConcurrentHashMap

class CustomGuaranteeTest {
    private val map = ConcurrentHashMap<Int, Int>()

    @Operation
    fun put(key: Int, value: Int) = map.put(key, value)

    @Operation
    fun get(key: Int) = map.get(key)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .check(this::class)

    // This test takes less time because Lincheck doesn't need to analyze
    // the methods of `ConcurrentHashMap`
    @Test
    fun modelCheckingWithGuaranteesTest() = ModelCheckingOptions()
        .addGuarantee(
            forClasses("java.util.concurrent.ConcurrentHashMap")
                .allMethods()
                .treatAsAtomic()
        )
        .check(this::class)
}