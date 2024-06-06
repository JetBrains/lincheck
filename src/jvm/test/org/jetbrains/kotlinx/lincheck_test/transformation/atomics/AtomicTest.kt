/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck_test.transformation.atomics

import org.jetbrains.kotlinx.lincheck.RandomProvider
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import java.util.concurrent.atomic.*
import java.util.*


class AtomicBooleanTest : AbstractLincheckTest() {
    val value = AtomicBoolean()

    @Operation
    fun get() = value.get()

    @Operation
    fun set(newValue: Boolean) = value.set(newValue)

    @Operation
    fun getAndSet(newValue: Boolean) = value.getAndSet(newValue)

    @Operation
    fun compareAndSet(expectedValue: Boolean, newValue: Boolean) = value.compareAndSet(expectedValue, newValue)

}

class AtomicIntegerTest : AbstractLincheckTest() {
    val value = AtomicInteger()

    @Operation
    fun get() = value.get()

    @Operation
    fun set(newValue: Int) = value.set(newValue)

    @Operation
    fun getAndSet(newValue: Int) = value.getAndSet(newValue)

    @Operation
    fun compareAndSet(expectedValue: Int, newValue: Int) = value.compareAndSet(expectedValue, newValue)

    @Operation
    fun addAndGet(delta: Int) = value.addAndGet(delta)

    @Operation
    fun getAndAdd(delta: Int) = value.getAndAdd(delta)

    @Operation
    fun incrementAndGet() = value.incrementAndGet()

    @Operation
    fun getAndIncrement() = value.getAndIncrement()

    @Operation
    fun decrementAndGet() = value.decrementAndGet()

    @Operation
    fun getAndDecrement() = value.getAndDecrement()

    override fun extractState(): Any = value.get()
}

class AtomicLongTest : AbstractLincheckTest() {
    val value = AtomicLong()

    @Operation
    fun get() = value.get()

    @Operation
    fun set(newValue: Long) = value.set(newValue)

    @Operation
    fun getAndSet(newValue: Long) = value.getAndSet(newValue)

    @Operation
    fun compareAndSet(expectedValue: Long, newValue: Long) = value.compareAndSet(expectedValue, newValue)

    @Operation
    fun addAndGet(delta: Long) = value.addAndGet(delta)

    @Operation
    fun getAndAdd(delta: Long) = value.getAndAdd(delta)

    @Operation
    fun incrementAndGet() = value.incrementAndGet()

    @Operation
    fun getAndIncrement() = value.getAndIncrement()

    @Operation
    fun decrementAndGet() = value.decrementAndGet()

    @Operation
    fun getAndDecrement() = value.getAndDecrement()
}

/* We use here a generator choosing from a predefined array of strings,
 * because the default string generator is not "referentially-stable".
 * In other words, it can generate two strings with identical content but have different references.
 * Even empty string "" can be represented by several objects.
 * Besides that, because we are also testing the compare-and-swap method here,
 * executing it on randomly generated strings will result in failures most of the time.
 * On the contrary, by choosing from a fixed predefined list of strings,
 * we increase the chance of CAS to succeed.
 */
@Param(name = "test", gen = TestStringGenerator::class)
class AtomicReferenceTest : AbstractLincheckTest() {

    val ref = AtomicReference("")

    @Operation
    fun get() = ref.get()

    @Operation
    fun set(@Param(name = "test") newValue: String) {
        ref.set(newValue)
    }

    @Operation
    fun compareAndSet(@Param(name = "test") expectedValue: String,
                      @Param(name = "test") newValue: String) =
        ref.compareAndSet(expectedValue, newValue)

    @Operation
    fun getAndSet(@Param(name = "test") newValue: String) =
        ref.getAndSet(newValue)

}

// TODO: this generator can be generalized to a generator choosing random element
//   from an arbitrary user-defined list
class TestStringGenerator(randomProvider: RandomProvider, configuration: String): ParameterGenerator<String> {
    private val random = randomProvider.createRandom()

    private val strings = arrayOf("", "abc", "xyz")

    override fun generate(): String =
        strings[random.nextInt(strings.size)]
}

