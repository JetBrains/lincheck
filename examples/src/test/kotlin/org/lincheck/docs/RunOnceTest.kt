package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.util.LoggingLevel
import kotlin.test.Test

class DummyStructure {
    fun foo() {}
    fun buzz() {}
}

class RunOnceTest {
    var struct = DummyStructure()

    @Operation(runOnce = true)
    fun foo() = struct.foo()

    @Operation
    fun buzz() = struct.buzz()

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        // Report the scenarios even if the test has not failed
        .logLevel(LoggingLevel.INFO)
        .check(this::class)
}