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

import org.jetbrains.kotlinx.lincheck.ExceptionResult
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import org.junit.Ignore
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.DoubleAccumulator
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAccumulator
import java.util.concurrent.atomic.LongAdder
import kotlin.random.*
import kotlin.random.Random

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
                .iterations(30)
                .threads(2)
                .actorsPerThread(1)
                .verifier(FailingVerifier::class.java)
                .customize()
                .checkImpl(this::class.java) { lincheckFailure ->
                    val results = lincheckFailure?.results?.parallelResults?.flatten()?.takeIf { it.isNotEmpty() }
                    require(results != null) { lincheckFailure.toString() }
                    if (shouldFail()) {
                        require(results.all { it is ExceptionResult })// { lincheckFailure.toString() }
                    } else {
                        require(results.none { it is ExceptionResult })// { lincheckFailure.toString() }
                    }
                }
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
    
    open fun shouldFail() = false

    @Test
    fun test() = testTraceDebugger()
    open fun ModelCheckingOptions.customize(): ModelCheckingOptions = this
}

@Ignore("Loop detector bug")
class ChangingOutputTest : NativeCallTest() {
    @Operation
    fun operation() {
        val result = FakeNativeCalls.makeNewByteArray()
        for (i in 0 until 10) {
            require(result[i] == i.toByte()) { "Wrong value at index $i: ${result[i]} instead of $i"}
        }
        for (i in 0 until 10) {
            result[i] = (-i).toByte()
        }
        for (i in 0 until 10) {
            require(result[i] == (-i).toByte()) { "Wrong value at index $i: ${result[i]} instead of ${-i}"}
        }
    }

    override fun ModelCheckingOptions.customize(): ModelCheckingOptions {
        return hangingDetectionThreshold(10_000)
    }
}

class PartialModificationTest : NativeCallTest() {
    @Operation
    fun operation() {
        val result = ByteArray(10)
        require(result.all { it == 0.toByte() }) { result.asList() }
        runCatching {
            FakeNativeCalls.failingInit(result)
        }
        for (i in 0 until 5) {
            require(result[i] == i.toByte()) { "Wrong value at index $i: ${result[i]} instead of $i"}
        }
        for (i in 5 until 10) {
            require(result[i] == 0.toByte()) { "Wrong value at index $i: ${result[i]} instead of 0"}
        }
        FakeNativeCalls.init(result)
        for (i in 0 until 10) {
            require(result[i] == i.toByte()) { "Wrong value at index $i: ${result[i]} instead of $i"}
        }
    }
}

class IdCheckTest : NativeCallTest() {
    @Operation
    fun operation() {
        val result = ByteArray(10)
        require(FakeNativeCalls.id(result) === result) { "Wrong id: ${FakeNativeCalls.id(result)}" }
        @Suppress("ReplaceArrayEqualityOpWithArraysEquals")
        require(FakeNativeCalls.id(result) == result) { "Wrong id: ${FakeNativeCalls.id(result)}" }
        require(FakeNativeCalls.id(result).hashCode() == result.hashCode()) { "Wrong hashCode: ${FakeNativeCalls.id(result).hashCode()} != ${result.hashCode()}" }
        require(FakeNativeCalls.id(result).toString() == result.toString()) { "Wrong toString: ${FakeNativeCalls.id(result)} != $result" }
    }
}

class ByteArrayToStringTest : NativeCallTest() {
    private data class A(val x: ByteArray): I {
        override fun toString(): String = "A($x)"
    }
    private interface I {
        override fun toString(): String
    }
    
    @Operation
    fun operation(): String {
        val result = ByteArray(10)
        
        require(result.toString() == FakeNativeCalls.id(result).toString()) {
            "Wrong toString: $result != ${FakeNativeCalls.id(result)}"
        }
        require(A(result).toString() == A(FakeNativeCalls.id(result)).toString()) {
            "Wrong toString: ${A(result)} != ${A(FakeNativeCalls.id(result))}"
        }
        require((A(result) as I).toString() == (A(FakeNativeCalls.id(result)) as I).toString()) {
            "Wrong toString: ${A(result) as I} != ${A(FakeNativeCalls.id(result)) as I}"
        }
        
        require(Objects.toString(result) == Objects.toString(FakeNativeCalls.id(result))) {
            "Wrong toString: ${Objects.toString(result)} != ${Objects.toString(FakeNativeCalls.id(result))}"
        }
        require(Objects.toString(A(result)) == Objects.toString(A(FakeNativeCalls.id(result)))) {
            "Wrong toString: ${Objects.toString(A(result))} != ${Objects.toString(A(FakeNativeCalls.id(result)))}"
        }
        require(Objects.toString((A(result) as I)) == Objects.toString(A(FakeNativeCalls.id(result)) as I)) {
            "Wrong toString: ${Objects.toString(A(result) as I)} != ${Objects.toString(A(FakeNativeCalls.id(result)) as I)}"
        }
        
        require(Objects.toString(result, "null") == Objects.toString(FakeNativeCalls.id(result), "null")) {
            "Wrong toString: ${Objects.toString(result, "null")} != ${Objects.toString(FakeNativeCalls.id(result), "null")}"
        }
        require(Objects.toString(A(result), "null") == Objects.toString(A(FakeNativeCalls.id(result)), "null")) {
            "Wrong toString: ${Objects.toString(A(result), "null")} != ${Objects.toString(A(FakeNativeCalls.id(result)), "null")}"
        }
        require(Objects.toString((A(result) as I), "null") == Objects.toString(A(FakeNativeCalls.id(result)) as I, "null")) {
            "Wrong toString: ${Objects.toString(A(result) as I, "null")} != ${Objects.toString(A(FakeNativeCalls.id(result)) as I, "null")}"
        }
        
        val fromStringBuilder = buildString {
            append(result, A(result), A(result) as I)
            append(result)
            append(A(result))
            append(A(result) as I)
            appendLine(result)
            appendLine(A(result))
            appendLine(A(result) as I)
        }
        return """
            |$result.\n${A(result)}\n${(A(result) as I)}
            |${Objects.toString(result)}\n${Objects.toString(A(result))}\n${Objects.toString((A(result) as I))}
            |${Objects.toString(result, "null")}\n${Objects.toString(A(result), "null")}\n${Objects.toString((A(result) as I), "null")}
            |$fromStringBuilder
            |${java.lang.String.valueOf(result)}\n${java.lang.String.valueOf(A(result))}\n${java.lang.String.valueOf(A(result) as I)}
            |${String.format("%s", result)}\n${String.format("%s", A(result))}\n${String.format("%s", A(result) as I)}
        """.trimMargin()
    }
}

class ByteArrayEqualsTest : NativeCallTest() {
    private data class A(val x: ByteArray): I {
        override fun equals(other: Any?): Boolean = other is A && this.x == other.x
    }
    private interface I {
        override fun equals(other: Any?): Boolean
    }
    
    @Operation
    fun operation() {
        val result = ByteArray(10)
        require(result == FakeNativeCalls.id(result)) {
            "Wrong equals: $result != ${FakeNativeCalls.id(result)}"
        }
        require(A(result) == A(FakeNativeCalls.id(result))) {
            "Wrong equals: ${A(result)} != ${A(FakeNativeCalls.id(result))}"
        }
        require((A(result) as I) == (A(FakeNativeCalls.id(result)) as I)) {
            "Wrong equals: ${A(result) as I} != ${A(FakeNativeCalls.id(result)) as I}"
        }
        
        require(result.equals(FakeNativeCalls.id(result))) {
            "Wrong equals: $result != ${FakeNativeCalls.id(result)}"
        }
        require(A(result).equals(A(FakeNativeCalls.id(result)))) {
            "Wrong equals: ${A(result)} != ${A(FakeNativeCalls.id(result))}"
        }
        require((A(result) as I).equals((A(FakeNativeCalls.id(result)) as I))) {
            "Wrong equals: ${A(result) as I} != ${A(FakeNativeCalls.id(result)) as I}"
        }
        
        require(Objects.equals(result, FakeNativeCalls.id(result))) {
            "Wrong equals: $result != ${FakeNativeCalls.id(result)}"
        }
        require(Objects.equals(A(result), A(FakeNativeCalls.id(result)))) {
            "Wrong equals: ${A(result)} != ${A(FakeNativeCalls.id(result))}"
        }
        require(Objects.equals(A(result) as I, A(FakeNativeCalls.id(result)) as I)) {
            "Wrong equals: ${A(result) as I} != ${A(FakeNativeCalls.id(result)) as I}"
        }
        
        require(Objects.deepEquals(result, FakeNativeCalls.id(result))) {
            "Wrong equals: $result != ${FakeNativeCalls.id(result)}"
        }
        require(Objects.deepEquals(A(result), A(FakeNativeCalls.id(result)))) {
            "Wrong equals: ${A(result)} != ${A(FakeNativeCalls.id(result))}"
        }
        require(Objects.deepEquals(A(result) as I, A(FakeNativeCalls.id(result)) as I)) {
            "Wrong equals: ${A(result) as I} != ${A(FakeNativeCalls.id(result)) as I}"
        }
    }
}

class ByteArrayHashCodeTest : NativeCallTest() {
    private data class A(val x: ByteArray): I
    private interface I {
        override fun hashCode(): Int
    }
    
    @Operation
    fun operation(): String {
        val result = ByteArray(10)
        require(result.hashCode() == FakeNativeCalls.id(result).hashCode()) {
            "Wrong hashCode: ${result.hashCode()} != ${FakeNativeCalls.id(result).hashCode()}"
        }
        require(A(result).hashCode() == A(FakeNativeCalls.id(result)).hashCode()) {
            "Wrong hashCode: ${A(result).hashCode()} != ${A(FakeNativeCalls.id(result)).hashCode()}"
        }
        require((A(result) as I).hashCode() == (A(FakeNativeCalls.id(result)) as I).hashCode()) {
            "Wrong hashCode: ${(A(result) as I).hashCode()} != ${(A(FakeNativeCalls.id(result)) as I).hashCode()}"
        }
        
        require(Objects.hashCode(result) == Objects.hashCode(FakeNativeCalls.id(result))) {
            "Wrong hashCode: ${Objects.hashCode(result)} != ${Objects.hashCode(FakeNativeCalls.id(result))}"
        }
        require(Objects.hashCode(A(result)) == Objects.hashCode(A(FakeNativeCalls.id(result)))) {
            "Wrong hashCode: ${Objects.hashCode(A(result))} != ${Objects.hashCode(A(FakeNativeCalls.id(result)))}"
        }
        require(Objects.hashCode(A(result) as I) == Objects.hashCode(A(FakeNativeCalls.id(result)) as I)) {
            "Wrong hashCode: ${Objects.hashCode(A(result) as I)} != ${Objects.hashCode(A(FakeNativeCalls.id(result)) as I)}"
        }
        
        require(Objects.hash(result) == Objects.hash(FakeNativeCalls.id(result))) {
            "Wrong hashCode: ${Objects.hash(result)} != ${Objects.hash(FakeNativeCalls.id(result))}"
        }
        require(Objects.hash(A(result)) == Objects.hash(A(FakeNativeCalls.id(result)))) {
            "Wrong hashCode: ${Objects.hash(A(result))} != ${Objects.hash(A(FakeNativeCalls.id(result)))}"
        }
        require(Objects.hash(A(result) as I) == Objects.hash(A(FakeNativeCalls.id(result)) as I)) {
            "Wrong hashCode: ${Objects.hash(A(result) as I)} != ${Objects.hash(A(FakeNativeCalls.id(result)) as I)}"
        }
        
        return """
            |${result.hashCode()}\n${A(result).hashCode()}\n${(A(result) as I).hashCode()}
            |${Objects.hashCode(result)}\n${Objects.hashCode(A(result))}\n${(Objects.hashCode(A(result) as I))}
            |${Objects.hash(result)}\n${Objects.hash(A(result))}\n${(Objects.hash(A(result) as I))}
        """.trimMargin()
    }
}

class InternalIdCheckTest : NativeCallTest() {
    @Operation
    fun operation() {
        val result = ByteArray(10)
        repeat(3) {
            require(FakeNativeCalls.saveOrIsSaved(result))
        }
    }
    
    override fun ModelCheckingOptions.customize() = threads(1) // using global variable
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

class FakeRandomTest : RandomTest() {
    class NotRandom : Random() {
        private var x = 0
        override fun nextBits(bitCount: Int): Int = x++
    }
    @Operation
    fun operation() = NotRandom().nextBits(10)
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
    override fun shouldFail() = true
}

@Suppress("UNUSED_PARAMETER")
class FailingVerifier(sequentialSpecification: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?) = false
}
