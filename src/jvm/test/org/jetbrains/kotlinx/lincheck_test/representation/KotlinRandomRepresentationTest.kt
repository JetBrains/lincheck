package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.junit.Assume.assumeFalse
import org.junit.Before
import kotlin.random.*

class KotlinRandomRepresentationTest : BaseTraceRepresentationTest("kotlin_random_representation") {
    @Before
    fun setUp() {
        assumeFalse(isInTraceDebuggerMode)
    }
    
    private var a = 0

    override fun operation() {
        a = Random.nextInt()
    }
}