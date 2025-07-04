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
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.transformation.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import org.jetbrains.kotlinx.lincheck.util.JdkVersion
import org.jetbrains.kotlinx.lincheck.util.jdkVersion
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.ParameterGenerator
import org.jetbrains.lincheck.datastructures.RandomProvider
import org.junit.Assume.assumeFalse
import org.junit.Before
import java.io.*
import java.util.concurrent.atomic.*

class SerializableResultTest : AbstractLincheckTest() {
    private val counter = AtomicReference(ValueHolder(0))

    @Operation
    fun getAndSet(key: Int) = counter.getAndSet(ValueHolder(key))

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

class SerializableJavaUtilResultTest : AbstractLincheckTest() {
    private val value = listOf(1, 2)

    @Operation
    fun operation() = value

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

class SerializableJavaUtilResultIncorrectTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private val value = mutableListOf(1, 2)

    @Operation
    fun operation(): List<Int> {
        value[0]++
        return value
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

@Param(name = "key", gen = ValueHolderGen::class)
class SerializableParameterTest : AbstractLincheckTest() {
    private val counter = AtomicInteger(0)

    @Operation
    fun operation(@Param(name = "key") key: ValueHolder): Int = counter.addAndGet(key.value)

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

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

@Suppress("UNUSED_PARAMETER")
class ValueHolderGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<ValueHolder> {
    override fun generate() = listOf(ValueHolder(1), ValueHolder(2)).random()
}

@Param(name = "key", gen = JavaUtilGen::class)
class SerializableJavaUtilParameterTest : AbstractLincheckTest() {
    @Before
    fun setUp() {
        // https://github.com/JetBrains/lincheck/issues/499
        assumeFalse(jdkVersion == JdkVersion.JDK_21 && isInTraceDebuggerMode)
    }
    
    @Operation
    fun operation(@Param(name = "key") key: List<Int>): Int = key[0] + key.sum()

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

@Suppress("UNUSED_PARAMETER")
class JavaUtilGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<List<Int>> {
    override fun generate() = listOf(1, 2)
}

data class ValueHolder(val value: Int) : Serializable

@Param(name = "key", gen = NullGen::class)
class SerializableNullParameterTest : AbstractLincheckTest() {
    @Operation
    fun operation(@Param(name = "key") key: List<Int>?): Int = key?.sum() ?: 0

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

@Suppress("UNUSED_PARAMETER")
class NullGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<List<Int>?> {
    override fun generate() = null
}
