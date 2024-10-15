/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.traceDebugger

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.DoubleAccumulator
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAccumulator
import java.util.concurrent.atomic.LongAdder
import kotlin.random.*

abstract class NativeCallTest {
    private fun testTraceDebugger() {
        val oldStdOut = System.out
        val oldErr = System.err
        val stdOutOutputCollector = ByteArrayOutputStream()
        val myStdOut = PrintStream(stdOutOutputCollector)
        val stdErrOutputCollector = ByteArrayOutputStream()
        val myStdErr = PrintStream(stdErrOutputCollector)
        System.setOut(myStdOut)
        System.setErr(myStdErr)
        try {
            ModelCheckingOptions()
                .actorsBefore(0)
                .actorsAfter(0)
                .threads(1)
                .actorsPerThread(1)
                .verifier(FailingVerifier::class.java)
                .checkImpl(this::class.java)
        } finally {
            val forbiddenString = "Non-determinism found."
            System.setOut(oldStdOut)
            System.setErr(oldErr)
            val stdOutOutput = stdOutOutputCollector.toString()
            println(stdOutOutput)
            val stdErrOutput = stdErrOutputCollector.toString()
            System.err.print(stdErrOutput)
            require(!stdOutOutput.contains(forbiddenString) && !stdErrOutput.contains(forbiddenString))
        }
    }

    @Test
    fun test() = testTraceDebugger()
}

abstract class CurrentTimeTest : NativeCallTest()

class CurrentTimeNanoTest : CurrentTimeTest() {
    @Operation
    fun operation() = System.nanoTime()
}

class CurrentTimeMillisTest : CurrentTimeTest() {
    @Operation
    fun operation() = System.currentTimeMillis()
}

abstract class HashCodeTest : NativeCallTest()

class OverriddenHashCodeTest : HashCodeTest() {
    @Operation
    fun operation() = Any().hashCode()
}

class IdentityHashCodeTest : HashCodeTest() {
    @Operation
    fun operation() = System.identityHashCode(Any())
}

abstract class RandomTest : NativeCallTest()


class RandomInt1Test : RandomTest() {
    @Operation
    fun operation() = Random.nextInt()
}

class RandomInt2Test : RandomTest() {
    @Operation
    fun operation() = Random.nextInt(1000)
}

class RandomInt3Test : RandomTest() {
    @Operation
    fun operation() = Random.nextInt(1000, 10000)
}

class RandomInt4Test : RandomTest() {
    @Operation
    fun operation() = Random.nextInt(1000..10000)
}

class RandomLong1Test : RandomTest() {
    @Operation
    fun operation() = Random.nextLong()
}

class RandomLong2Test : RandomTest() {
    @Operation
    fun operation() = Random.nextLong(1000)
}

class RandomLong3Test : RandomTest() {
    @Operation
    fun operation() = Random.nextLong(1000, 10000)
}

class RandomLong4Test : RandomTest() {
    @Operation
    fun operation() = Random.nextLong(1000L..10000L)
}

class RandomUInt1Test : RandomTest() {
    @Operation
    fun operation() = Random.nextUInt()
}

class RandomUInt2Test : RandomTest() {
    @Operation
    fun operation() = Random.nextUInt(1000U)
}

class RandomUInt3Test : RandomTest() {
    @Operation
    fun operation() = Random.nextUInt(1000U, 10000U)
}

class RandomUInt4Test : RandomTest() {
    @Operation
    fun operation() = Random.nextUInt(1000U..10000U)
}

class RandomULong1Test : RandomTest() {
    @Operation
    fun operation() = Random.nextULong()
}

class RandomULong2Test : RandomTest() {
    @Operation
    fun operation() = Random.nextULong(1000U)
}

class RandomULong3Test : RandomTest() {
    @Operation
    fun operation() = Random.nextULong(1000U, 10000U)
}

class RandomULong4Test : RandomTest() {
    @Operation
    fun operation() = Random.nextULong(1000UL..10000UL)
}

class RandomBooleanTest : RandomTest() {
    @Operation
    fun operation() = Random.nextBoolean()
}

class RandomFloatTest : RandomTest() {
    @Operation
    fun operation() = Random.nextFloat()
}

class RandomDouble1Test : RandomTest() {
    @Operation
    fun operation() = Random.nextDouble()
}

class RandomDouble2Test : RandomTest() {
    @Operation
    fun operation() = Random.nextDouble(1000.0)
}

class RandomDouble3Test : RandomTest() {
    @Operation
    fun operation() = Random.nextDouble(1000.0, 10000.0)
}

class RandomBitsTest : RandomTest() {
    @Operation
    fun operation() = Random.nextBits(24)
}

class RandomBytes1Test : RandomTest() {
    @Operation
    fun operation() = Random.nextBytes(24).asList()
}

class RandomBytes2Test : RandomTest() {
    @Operation
    fun operation() = Random.nextBytes(ByteArray(24)).asList()
}

class RandomBytes3Test : RandomTest() {
    @Operation
    fun operation() = Random.nextBytes(ByteArray(24), 10, 20).asList()
}

class RandomUBytes1Test : RandomTest() {
    @OptIn(ExperimentalUnsignedTypes::class)
    @Operation
    fun operation() = Random.nextUBytes(24).asList()
}

class RandomUBytes2Test : RandomTest() {
    @OptIn(ExperimentalUnsignedTypes::class)
    @Operation
    fun operation() = Random.nextUBytes(UByteArray(24)).asList()
}

class RandomUBytes3Test : RandomTest() {
    @OptIn(ExperimentalUnsignedTypes::class)
    @Operation
    fun operation() = Random.nextUBytes(UByteArray(24), 10, 20).asList()
}

class ThreadLocalRandomTest : RandomTest() {
    @Operation
    fun operation() = ThreadLocalRandom.current().nextInt()
}

class LongAdderRandomTest : RandomTest() {
    @Operation
    fun operation() = LongAdder().apply { repeat(100) { increment() } }.sum()
}

class DoubleAdderRandomTest : RandomTest() {
    @Operation
    fun operation() = DoubleAdder().apply { repeat(100) { add(1.0) } }.sum()
}

class LongAccumulatorRandomTest : RandomTest() {
    @Operation
    fun operation() = LongAccumulator({ x, y -> x + y }, 0)
        .apply { repeat(100) { accumulate(1) } }
        .get()
}

class DoubleAccumulatorRandomTest : RandomTest() {
    @Operation
    fun operation() = DoubleAccumulator({ x, y -> x + y }, 0.0)
        .apply { repeat(100) { accumulate(1.0) } }
        .get()
}

class ExceptionTest : NativeCallTest() {
    @Operation
    fun operation() = Random.nextInt(10, 0)
}

@Suppress("UNUSED_PARAMETER")
class FailingVerifier(sequentialSpecification: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?) = false
}
