package org.lincheck.docs

import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test

class DummyStructure {
    fun singleOp() {}
    fun regularOp() {}
}

class RunOnceTest {
    var struct = DummyStructure()

    @Operation(runOnce = true)
    fun singleOp() = struct.singleOp()

    @Operation
    fun regularOp() = struct.regularOp()

    @Test
    fun modelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings { modelChecking() }, this::class)
}