package org.jetbrains.lincheck_test.project

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import kotlin.test.Test

class HashMapTest {
    private val map = hashMapOf<Int, Int>()

    @Operation
    fun get(key: Int) = map[key]

    @Operation
    fun put(key: Int, value: Int) = map.put(key, value)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions().check(this::class)
}
