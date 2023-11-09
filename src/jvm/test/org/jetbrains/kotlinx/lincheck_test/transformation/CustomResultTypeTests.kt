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
import org.jetbrains.kotlinx.lincheck_test.*
import java.util.concurrent.atomic.*

class CustomResultTest : AbstractLincheckTest() {
    private val counter = AtomicReference(CustomValue(0))

    @Operation
    fun getAndSet(key: Int) = counter.getAndSet(CustomValue(key))

    override fun extractState(): Any = counter.get().value

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

class ListAsResultIncorrectTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
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

@Param(name = "key", gen = ValueHolderGen::class)
class CustomParameterTest : AbstractLincheckTest() {
    private val counter = AtomicInteger(0)

    @Operation
    fun operation(@Param(name = "key") key: CustomValue): Int = counter.addAndGet(key.value)

    override fun extractState(): Any = counter.get()

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
    }
}

@Param(name = "key", gen = ValueHolderGen::class)
class CustomParameterIncorrectTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private var counter = 0

    @Operation
    fun operation(@Param(name = "key") key: CustomValue): Int {
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

class ValueHolderGen(randomProvider: RandomProvider, conf: String) : ParameterGenerator<CustomValue> {
    override fun generate(): CustomValue {
        return listOf(CustomValue(1), CustomValue(2)).random()
    }
}

@Param(name = "key", gen = JavaUtilGen::class)
class ListAsParameterTest : AbstractLincheckTest() {
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
}

data class CustomValue(val value: Int)

@Param(name = "key", gen = NullGen::class)
class CustomNullParameterTest : AbstractLincheckTest() {
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
}
