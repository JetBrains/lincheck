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

package org.jetbrains.kotlinx.lincheck.test.transformation.atomic

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import java.util.*
import java.util.concurrent.atomic.*

class AtomicBooleanTest : AbstractLincheckTest() {
    val value = AtomicBoolean()

    @Operation
    fun compareAndSet(expectedValue: Boolean, newValue: Boolean) = value.compareAndSet(expectedValue, newValue)

    @Operation
    fun get() = value.get()

    @Operation
    fun getAndSet(newValue: Boolean) = value.getAndSet(newValue)

    @Operation
    fun set(newValue: Boolean) = value.set(newValue)

    override fun extractState(): Any = value.get()
}

class AtomicIntegerTest : AbstractLincheckTest() {
    val value = AtomicInteger()

    @Operation
    fun compareAndSet(expectedValue: Int, newValue: Int) = value.compareAndSet(expectedValue, newValue)

    @Operation
    fun get() = value.get()

    @Operation
    fun getAndSet(newValue: Int) = value.getAndSet(newValue)

    @Operation
    fun set(newValue: Int) = value.set(newValue)

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
    fun compareAndSet(expectedValue: Long, newValue: Long) = value.compareAndSet(expectedValue, newValue)

    @Operation
    fun get() = value.get()

    @Operation
    fun getAndSet(newValue: Long) = value.getAndSet(newValue)

    @Operation
    fun set(newValue: Long) = value.set(newValue)

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

    override fun extractState(): Any = value.get()
}

@Param(name = "idx", gen = IntGen::class, conf = "0:4")
class AtomicIntegerArrayTest : AbstractLincheckTest() {
    val value = AtomicIntegerArray(5)

    @Operation
    fun compareAndSet(@Param(name = "idx") idx: Int, expectedValue: Int, newValue: Int) = value.compareAndSet(idx, expectedValue, newValue)

    @Operation
    fun get(@Param(name = "idx") idx: Int) = value.get(idx)

    @Operation
    fun getAndSet(@Param(name = "idx") idx: Int, newValue: Int) = value.getAndSet(idx, newValue)

    @Operation
    fun set(@Param(name = "idx") idx: Int, newValue: Int) = value.set(idx, newValue)

    @Operation
    fun addAndGet(@Param(name = "idx") idx: Int, delta: Int) = value.addAndGet(idx, delta)

    @Operation
    fun getAndAdd(@Param(name = "idx") idx: Int, delta: Int) = value.getAndAdd(idx, delta)

    @Operation
    fun incrementAndGet(@Param(name = "idx") idx: Int) = value.incrementAndGet(idx)

    @Operation
    fun getAndIncrement(@Param(name = "idx") idx: Int) = value.getAndIncrement(idx)

    @Operation
    fun decrementAndGet(@Param(name = "idx") idx: Int) = value.decrementAndGet(idx)

    @Operation
    fun getAndDecrement(@Param(name = "idx") idx: Int) = value.getAndDecrement(idx)

    override fun extractState(): Any = (0 until 5).map { value.get(it) }
}

// We use here a generator choosing from a predefined array of strings,
// because the default string generator is not "referentially-stable".
// In other words it can generate two strings with identical content,
// but having different references.
// Even empty string "" can be represented by several objects.
// Besides that, because we are also testing compare-and-swap method here,
// executing it on randomly generated strings will result in failures most of the time.
// On contrary, by choosing from fixed predefined list of strings
// we increase the chance of CAS to succeed.
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

    override fun extractState(): Any = ref.get()
}

// TODO: this generator can be generalized to a generator choosing random element
//   from an arbitrary user-defined list
class TestStringGenerator(configuration: String): ParameterGenerator<String> {
    private val random = Random(0)

    private val strings = arrayOf("", "abc", "xyz")

    override fun generate(): String =
        strings[random.nextInt(strings.size)]
}