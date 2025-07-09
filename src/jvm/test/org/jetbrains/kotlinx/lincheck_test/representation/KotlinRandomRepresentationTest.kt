package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import org.junit.Assume.assumeFalse
import org.junit.Before
import kotlin.random.*

class KotlinRandomRepresentationTest : BaseTraceRepresentationTest("kotlin_random_representation") {
    @Before
    fun setUp() {
        assumeFalse(isInTraceDebuggerMode) // The random number is different on each test run
    }
    
    private var a = 0

    override fun operation() {
        a = Random.nextInt()
    }
}