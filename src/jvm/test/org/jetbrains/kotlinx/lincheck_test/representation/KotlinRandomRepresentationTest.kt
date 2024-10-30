package org.jetbrains.kotlinx.lincheck_test.representation

import org.junit.Ignore
import kotlin.random.*

@Ignore // We use non-deterministic random in the trace debugger, so the result will differ from the one saved in file.
class KotlinRandomRepresentationTest : BaseTraceRepresentationTest("kotlin_random_representation.txt") {
    private var a = 0

    override fun operation() {
        a = Random.nextInt()
    }
    
    
}