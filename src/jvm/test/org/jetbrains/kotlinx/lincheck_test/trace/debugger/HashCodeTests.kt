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
import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.*

abstract class HashCodeTest : AbstractDeterministicTest()

class SimpleHashCodeTest : HashCodeTest() {
    @Operation
    fun operation() = List(100) { Any().hashCode() }
}

class IdentityHashCodeTest : HashCodeTest() {
    @Operation
    fun operation(): List<Int> = List(100) { System.identityHashCode(Any()) }
}


class ObjectsHashCodeTest : HashCodeTest() {
    @Operation
    fun operation() = List(100) { Objects.hashCode(Any()) }
}


class ObjectsHashCodeVarargTest : HashCodeTest() {
    @Operation
    fun operation() = List(100) { Objects.hash(Any(), Any()) }
}

class SimpleToStringTest : HashCodeTest() {
    @Operation
    fun operation() = List(100) { Any().toString() }
}

class HashCodeToStringTest : HashCodeTest() {
    @Operation
    fun operation() = List(100) { Any().hashCode().toString() }
}

class StringBuilderToStringTest : HashCodeTest() {
    @Operation
    fun operation() = List(100) { buildString { appendLine(Any()) } }
}

class StringBuilderHashCodeToStringTest : HashCodeTest() {
    @Operation
    fun operation() = List(100) { buildString { appendLine(Any().hashCode()) } }
}

// TODO Investigate the loop detector bug: why it fails with 100 (Alexander Potapov)
// https://github.com/JetBrains/lincheck/issues/498
class InvokeDynamicToStringTest : HashCodeTest() {
    @Operation
    fun operation() = List(42) { "${Any()} ${Any()}" }
}

// TODO Investigate the loop detector bug: why it fails with 100 (Alexander Potapov)
// https://github.com/JetBrains/lincheck/issues/498
class InvokeDynamicInnerClassCreationTest : HashCodeTest() {
    class A {
        override fun toString(): String = Any().hashCode().toString()
    }
    @Operation
    fun operation(): List<String> = List(31) { "${A()} ${A()}" }
}

class InvokeDynamicHashCodeToStringTest : HashCodeTest() {
    @Operation
    fun operation(): List<String> = List(100) { "${Any().hashCode()} ${Any().hashCode()}" }
}

class StringFormatToStringTest : HashCodeTest() {
    @Operation
    fun operation() = List(100) { String.format("%s %s", Any(), Any()) }
}

class StringFormatHashCodeToStringTest : HashCodeTest() {
    @Operation
    fun operation() = List(100) { String.format("%s %s", Any().hashCode(), Any().hashCode()) }
}

data class Wrapper(val value: Any)

class WrapperHashCodeTest : HashCodeTest() {
    @Operation
    fun operation() = List(100) { Wrapper(Any()).hashCode() }
}

// TODO Investigate the loop detector bug: why it fails with 100 (Alexander Potapov)
// https://github.com/JetBrains/lincheck/issues/498
class WrapperToStringTest : HashCodeTest() {
    @Operation
    fun operation() = List(42) { Wrapper(Any()).toString() }
}

// TODO Investigate the loop detector bug: why it fails with 100 (Alexander Potapov)
// https://github.com/JetBrains/lincheck/issues/498
class InitInternalToStringTest : HashCodeTest() {
    class A {
        internal val value = "${Any()} ${Any()}"
        override fun toString(): String = value
    }
    @Operation
    fun operation() = List(21) { "${A()} ${A()}" }
}

class ClassInitInternalToStringTest : HashCodeTest() {
    object A {
        @JvmStatic
        internal val value = "${Any()} ${Any()}"
    }
    fun f(): String = A.value
    
    @Operation
    fun operation() = List(100) { "${f()} ${f()}" }
}

class FailingInvokeDynamicTest : HashCodeTest() {
    private class A {
        override fun toString(): String {
            throw IllegalStateException()
        }
    }
    @Operation
    fun operation(): List<String> {
        try {
            List(100) { "${A()} ${A()}" }
        } catch (_: Throwable) {
            // ignore
        }
        return List(100) { Any().toString() }
    }
}

class FailingInvokeDynamicWithStateTest : HashCodeTest() {
    private class A {
        var x = 0
        override fun toString(): String {
            try {
                x = Any().hashCode()
                throw IllegalStateException()
            } finally {
                x = x xor Any().hashCode()
            }
        }
    }
    
    @Operation
    fun operation(): List<String> {
        try {
            List(100) { "${A()} ${A()} ${A().x}" }
        } catch (_: Throwable) {
            // ignore
        }
        // TODO Investigate the loop detector bug: why it fails with 100 (Alexander Potapov)
        // https://github.com/JetBrains/lincheck/issues/498
        return List(99) { Any().toString() + " " + A().x }
    }
}

class IdentityHashCodeDiffersTest() {
    @Before
    fun setUp() {
        assumeTrue(isInTraceDebuggerMode)
    }

    @Operation
    fun operation() {
        val objects = Array(10) { Any() }
        // identityHashCode should differ
        check(objects.map { it.hashCode() }.distinct().size > 1)
    }

    @Test
    fun test() = ModelCheckingOptions()
        .iterations(1)
        .threads(1)
        .check(this::class)
}

class ArraysClassTest: AbstractDeterministicTest() {
    @Operation
    fun operation(): String {
        val result = ByteArray(10)
        val hashCode1 = result.hashCode()
        val hashCode = Arrays.hashCode(result)
        return """
            |$hashCode1 $hashCode
        """.trimMargin()
    }
}
