/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

class ValueHolderGen(conf: String) : ParameterGenerator<CustomValue> {
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

class JavaUtilGen(conf: String) : ParameterGenerator<List<Int>> {
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

class NullGen(conf: String) : ParameterGenerator<List<Int>?> {
    override fun generate() = null
}
