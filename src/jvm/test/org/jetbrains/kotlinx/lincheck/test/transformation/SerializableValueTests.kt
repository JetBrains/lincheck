/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.Serializable
import java.util.concurrent.atomic.*

class SerializableResultTest : AbstractLincheckTest() {
    private val counter = AtomicReference(ValueHolder(0))

    @Operation
    fun getAndSet(key: Int) = counter.getAndSet(ValueHolder(key))

    override fun extractState(): Any = counter.get().value

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

class SerializableJavaUtilResultTest : AbstractLincheckTest() {
    private val value = listOf(1, 2)

    @Operation
    fun get(key: Int) = value

    override fun extractState(): Any = value

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

class SerializableJavaUtilResultIncorrectTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private val value = mutableListOf(1, 2)

    @Operation
    fun get(key: Int): List<Int> {
        value[0]++
        return value
    }

    override fun extractState(): Any = value

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

class SerializableNullResultTest {
    @Test
    fun test() {
        val a = ValueResult(null)
        val value = ValueHolder(0)
        val loader = TransformationClassLoader { CancellabilitySupportClassTransformer(it) }
        val transformedValue = value.convertForLoader(loader)
        val b = ValueResult(transformedValue)
        // check that no exception was thrown
        assertFalse(a == b)
        assertFalse(b == a)
    }
}

@Param(name = "key", gen = ValueHolderGen::class)
class SerializableParameterTest : AbstractLincheckTest() {
    private val counter = AtomicInteger(0)

    @Operation
    fun operation(@Param(name = "key") key: ValueHolder): Int = counter.addAndGet(key.value)

    override fun extractState(): Any = counter.get()

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

@Param(name = "key", gen = ValueHolderGen::class)
class SerializableParameterIncorrectTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private var counter = 0

    @Operation
    fun operation(@Param(name = "key") key: ValueHolder): Int {
        counter += key.value
        return counter
    }

    override fun extractState(): Any = counter

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

class ValueHolderGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<ValueHolder> {
    override fun generate(): ValueHolder {
        return listOf(ValueHolder(1), ValueHolder(2)).random()
    }

    override fun resetRange() {
    }
}

@Param(name = "key", gen = JavaUtilGen::class)
class SerializableJavaUtilParameterTest : AbstractLincheckTest() {
    @Operation
    fun operation(@Param(name = "key") key: List<Int>): Int = key[0] + key.sum()

    override fun extractState(): Any = 0 // constant state

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

class JavaUtilGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<List<Int>> {
    override fun generate() = listOf(1, 2)
    override fun resetRange() {
    }
}

data class ValueHolder(val value: Int) : Serializable

@Param(name = "key", gen = NullGen::class)
class SerializableNullParameterTest : AbstractLincheckTest() {
    @Operation
    fun operation(@Param(name = "key") key: List<Int>?): Int = key?.sum() ?: 0

    override fun extractState(): Any = 0 // constant state

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

class NullGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<List<Int>?> {
    override fun generate() = null

    override fun resetRange() {
    }
}
