package org.lincheck.docs

import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckSettings
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
    fun modelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings { modelChecking() }, this::class)

    // This test takes less time because Lincheck doesn't need to analyze
    // the methods of `ConcurrentHashMap`

    @Test
    fun modelCheckingWithGuaranteesTest() = Lincheck.runConcurrentTest(LincheckSettings {
        modelChecking {
            addGuarantee(
                forClasses("java.util.concurrent.ConcurrentHashMap")
                    .allMethods()
                    .treatAsAtomic()
            )
        }
    }, this::class)
}
