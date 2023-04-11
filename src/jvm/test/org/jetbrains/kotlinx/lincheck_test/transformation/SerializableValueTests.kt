/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.util.concurrent.atomic.*

class SerializableResultTest : AbstractLincheckTest() {
    private val counter = AtomicReference(ValueHolder(0))

    @Operation
    fun getAndSet(key: Int) = counter.getAndSet(ValueHolder(key))

    override fun LincheckOptionsImpl.customize() {
        testingTimeInSeconds = 1
        generateBeforeAndAfterParts = false
    }

    override val testPlanningConstraints: Boolean = false
}

class SerializableJavaUtilResultTest : AbstractLincheckTest() {
    private val value = listOf(1, 2)

    @Operation
    fun get(key: Int) = value

    override fun LincheckOptionsImpl.customize() {
        testingTimeInSeconds = 1
        generateBeforeAndAfterParts = false
    }

    override val testPlanningConstraints: Boolean = false
}

class SerializableJavaUtilResultIncorrectTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private val value = mutableListOf(1, 2)

    @Operation
    fun get(key: Int): List<Int> {
        value[0]++
        return value
    }

    override val testPlanningConstraints: Boolean = false
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

    override fun LincheckOptionsImpl.customize() {
        testingTimeInSeconds = 1
        generateBeforeAndAfterParts = false
    }

    override val testPlanningConstraints: Boolean = false
}

@Param(name = "key", gen = ValueHolderGen::class)
class SerializableParameterIncorrectTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private var counter = 0

    @Operation
    fun operation(@Param(name = "key") key: ValueHolder): Int {
        counter += key.value
        return counter
    }

    override val testPlanningConstraints: Boolean = false
}

class ValueHolderGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<ValueHolder> {
    override fun generate() = listOf(ValueHolder(1), ValueHolder(2)).random()
}

@Param(name = "key", gen = JavaUtilGen::class)
class SerializableJavaUtilParameterTest : AbstractLincheckTest() {
    @Operation
    fun operation(@Param(name = "key") key: List<Int>): Int = key[0] + key.sum()

    override fun LincheckOptionsImpl.customize() {
        testingTimeInSeconds = 1
        generateBeforeAndAfterParts = false
    }

    override val testPlanningConstraints: Boolean = false
}

class JavaUtilGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<List<Int>> {
    override fun generate() = listOf(1, 2)
}

data class ValueHolder(val value: Int) : Serializable

@Param(name = "key", gen = NullGen::class)
class SerializableNullParameterTest : AbstractLincheckTest() {
    @Operation
    fun operation(@Param(name = "key") key: List<Int>?): Int = key?.sum() ?: 0

    override fun LincheckOptionsImpl.customize() {
        testingTimeInSeconds = 1
        generateBeforeAndAfterParts = false
    }

    override val testPlanningConstraints: Boolean = false
}

class NullGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<List<Int>?> {
    override fun generate() = null
}
