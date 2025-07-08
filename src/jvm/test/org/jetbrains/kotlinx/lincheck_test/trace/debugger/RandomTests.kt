/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.trace.debugger

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.util.JdkVersion
import org.jetbrains.kotlinx.lincheck.util.jdkVersion
import org.junit.Assume.assumeFalse
import org.junit.Before
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.DoubleAccumulator
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAccumulator
import java.util.concurrent.atomic.LongAdder
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.random.nextLong
import kotlin.random.nextUBytes
import kotlin.random.nextUInt
import kotlin.random.nextULong
import java.util.Random as JRandom

abstract class RandomTests : AbstractDeterministicTest() {
    override val alsoRunInLincheckMode: Boolean get() = true
}

class RandomInt1Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextInt()
}

class RandomInt2Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextInt(1000)
}

class RandomInt3Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextInt(1000, 10000)
}

class RandomInt4Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextInt(1000..10000)
}

class RandomInt5Test : RandomTests() {
    @Operation
    fun operation() = runCatching { Random.Default.nextInt(10000, 1000) }.exceptionOrNull()!!.message
}

class FakeRandomTest : RandomTests() {
    @Operation
    fun operation() = NotRandom.nextBits(10)
}

class RandomLong1Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextLong()
}

class RandomLong2Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextLong(1000)
}

class RandomLong3Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextLong(1000, 10000)
}

class RandomLong4Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextLong(1000L..10000L)
}

class RandomLong5Test : RandomTests() {
    @Operation
    fun operation() = runCatching { Random.Default.nextLong(10000L, 1000L) }.exceptionOrNull()!!.message
}

class RandomUInt1Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextUInt()
}

class RandomUInt2Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextUInt(1000U)
}

class RandomUInt3Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextUInt(1000U, 10000U)
}

class RandomUInt4Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextUInt(1000U..10000U)
}

class RandomUInt5Test : RandomTests() {
    @Operation
    fun operation() = runCatching { Random.Default.nextUInt(10000U, 1000U) }.exceptionOrNull()!!.message
}

class RandomULong1Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextULong()
}

class RandomULong2Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextULong(1000U)
}

class RandomULong3Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextULong(1000U, 10000U)
}

class RandomULong4Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextULong(1000UL..10000UL)
}

class RandomULong5Test : RandomTests() {
    @Operation
    fun operation() = runCatching { Random.Default.nextULong(10000UL, 1000UL) }.exceptionOrNull()!!.message
}

class RandomBooleanTest : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextBoolean()
}

class RandomFloatTest : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextFloat()
}

class RandomDouble1Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextDouble()
}

class RandomDouble2Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextDouble(1000.0)
}

class RandomDouble3Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextDouble(1000.0, 10000.0)
}

class RandomDouble4Test : RandomTests() {
    @Operation
    fun operation() = runCatching { Random.Default.nextDouble(-1000.0) }.exceptionOrNull()!!.message
}

class RandomDouble5Test : RandomTests() {
    @Operation
    fun operation() = runCatching { Random.Default.nextDouble(10000.0, 1000.0) }.exceptionOrNull()!!.message
}

class RandomBitsTest : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextBits(24)
}

class RandomBytes1Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextBytes(24).asList()
}

class RandomBytes2Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextBytes(ByteArray(24)).asList()
}

class RandomBytes3Test : RandomTests() {
    @Operation
    fun operation() = Random.Default.nextBytes(ByteArray(24), 10, 20).asList()
}

class RandomBytes4Test : RandomTests() {
    @Operation
    fun operation(): List<Byte> {
        val array = ByteArray(24)
        val randomArray = Random.Default.nextBytes(array)
        require(array === randomArray)
        return randomArray.asList()
    }
}

class RandomBytes5Test : RandomTests() {
    @Operation
    fun operation(): List<Byte> {
        val array = ByteArray(24)
        val randomArray = Random.Default.nextBytes(array, 10, 20)
        require(array === randomArray)
        return randomArray.asList()
    }
}

class RandomUBytes1Test : RandomTests() {
    @OptIn(ExperimentalUnsignedTypes::class)
    @Operation
    fun operation() = Random.Default.nextUBytes(24).asList()
}

class RandomUBytes2Test : RandomTests() {
    @OptIn(ExperimentalUnsignedTypes::class)
    @Operation
    fun operation() = Random.Default.nextUBytes(UByteArray(24)).asList()
}

class RandomUBytes3Test : RandomTests() {
    @OptIn(ExperimentalUnsignedTypes::class)
    @Operation
    fun operation() = Random.Default.nextUBytes(UByteArray(24), 10, 20).asList()
}

@OptIn(ExperimentalUnsignedTypes::class)
class RandomUBytes4Test : RandomTests() {
    @Operation
    fun operation(): List<UByte> {
        val array = UByteArray(24)
        val randomArray = Random.Default.nextUBytes(array)
        require(array as Any == randomArray as Any)
        return randomArray.asList()
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
class RandomUBytes5Test : RandomTests() {
    @Operation
    fun operation(): List<UByte> {
        val array = UByteArray(24)
        val randomArray = Random.Default.nextUBytes(array, 10, 20)
        require(array as Any == randomArray as Any)
        return randomArray.asList()
    }
}

class ThreadLocalRandomTest : RandomTests() {
    @Operation
    fun operation() = ThreadLocalRandom.current().nextInt()
}

class LongAdderRandomTest : RandomTests() {
    @Operation
    fun operation() = LongAdder().apply { repeat(100) { increment() } }.sum()
}

class DoubleAdderRandomTest : RandomTests() {
    @Operation
    fun operation() = DoubleAdder().apply { repeat(100) { add(1.0) } }.sum()
}

class LongAccumulatorRandomTest : RandomTests() {
    @Operation
    fun operation() = LongAccumulator({ x, y -> x + y }, 0)
        .apply { repeat(100) { accumulate(1) } }
        .get()
}

class DoubleAccumulatorRandomTest : RandomTests() {
    @Operation
    fun operation() = DoubleAccumulator({ x, y -> x + y }, 0.0)
        .apply { repeat(100) { accumulate(1.0) } }
        .get()
}

class JRandomInt1Test : RandomTests() {
    @Operation
    fun operation() = JRandom().nextInt()
}

class JRandomInt2Test : RandomTests() {
    @Operation
    fun operation() = JRandom().nextInt(1000)
}

class JRandomLongTest : RandomTests() {
    @Operation
    fun operation() = JRandom().nextLong()
}

class JRandomDoubleTest : RandomTests() {
    @Operation
    fun operation() = JRandom().nextDouble()
}

class JRandomFloatTest : RandomTests() {
    @Operation
    fun operation() = JRandom().nextFloat()
}

class JRandomBooleanTest : RandomTests() {
    @Operation
    fun operation() = JRandom().nextBoolean()
}

class JRandomBytes1Test : RandomTests() {
    @Operation
    fun operation(): List<Byte> {
        val bytes = ByteArray(100)
        JRandom().nextBytes(bytes)
        return bytes.toList()
    }
}

class JRandomGaussianTest : RandomTests() {
    @Operation
    fun operation() = JRandom().nextGaussian()
}

object NotRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}

class StubRandomCheckTest : RandomTests() {
    override val alsoRunInLincheckMode: Boolean get() = false
    
    @Operation
    fun operation() {
        val randomList = List(10) { NotRandom.nextInt() }
        val expectedResult = List(10) { 0 }
        require(randomList == expectedResult) { "Wrong randomizer: $randomList != $expectedResult" }
    }
}

class FailingRecoveringRandomTest : RandomTests() {
    @Operation
    fun operation(): List<String> =
        List(10) { runCatching { if (it % 2 == 0) Random.nextInt(10, 100) else Random.nextInt(100, 10) }.toString() }
}

class FailingRandomBytesTest : RandomTests() {
    @Before
    fun setUp() {
        // https://github.com/JetBrains/lincheck/issues/564
        assumeFalse(jdkVersion == JdkVersion.JDK_21 || jdkVersion == JdkVersion.JDK_20)
    }
    
    class FailingRandom : JRandom() {
        override fun nextBytes(bytes: ByteArray) {
            super.nextBytes(bytes)
            throw IllegalStateException()
        }
    }
    
    @OptIn(ExperimentalStdlibApi::class)
    @Operation
    fun operation(): String {
        val bytes = ByteArray(10)
        val before = bytes.toList()
        runCatching {
            FailingRandom().nextBytes(bytes)
        }
        val after = bytes.toList()
        require(after.any { it != 0.toByte() }) { "Has not performed: $after" }
        return "${before.joinToString(" ") { it.toHexString() }}\n${after.joinToString(" ") { it.toHexString() }}"
    }
}

class RandomMethodWithSideEffectTest : RandomTests() {
    class RandomWithSideEffect : JRandom() {
        var counter = 0
            private set
        fun changeArray(x: ByteArray) {
            x[0] = counter.toByte()
            counter++
        }
        override fun nextInt(): Int {
            counter++
            return super.nextInt()
        }
    }
    
    @Operation
    fun operation(): String {
        val random = RandomWithSideEffect()
        val counter1 = random.counter
        val randomValue = random.nextInt()
        val counter2 = random.counter
        require(counter1 + 1 == counter2) { "Wrong counter: $counter1 + 1 = $counter2" }
        val wrapper = ByteArray(1)
        require(wrapper[0] == 0.toByte()) { "Wrong wrapper value: ${wrapper[0]}"}
        random.changeArray(wrapper)
        val counter3 = random.counter
        require(counter2 + 1 == counter3) { "Wrong wrapper value: $counter2 + 1 = $counter3" }
        return "$counter1 $randomValue $counter2 $counter3".also(::println)
    }
}
