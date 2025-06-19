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

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import org.jetbrains.kotlinx.lincheck_test.util.StringPoolGenerator
import java.util.concurrent.atomic.*

class AtomicIntegerFieldUpdaterTest : AbstractLincheckTest() {
    @Volatile
    var value: Int = 0

    private val updater = AtomicIntegerFieldUpdater.newUpdater(AtomicIntegerFieldUpdaterTest::class.java, "value")

    @Operation
    fun get() = updater.get(this)

    @Operation
    fun set(newValue: Int) = updater.set(this, newValue)

    @Operation
    fun getAndSet(newValue: Int) = updater.getAndSet(this, newValue)

    @Operation
    fun compareAndSet(expectedValue: Int, newValue: Int) = updater.compareAndSet(this, expectedValue, newValue)

    @Operation
    fun addAndGet(delta: Int) = updater.addAndGet(this, delta)

    @Operation
    fun getAndAdd(delta: Int) = updater.getAndAdd(this, delta)

    @Operation
    fun incrementAndGet() = updater.incrementAndGet(this)

    @Operation
    fun getAndIncrement() = updater.getAndIncrement(this)

    @Operation
    fun decrementAndGet() = updater.decrementAndGet(this)

    @Operation
    fun getAndDecrement() = updater.getAndDecrement(this)
}

class AtomicLongFieldUpdaterTest : AbstractLincheckTest() {
    @Volatile
    var value: Long = 0

    private val updater = AtomicLongFieldUpdater.newUpdater(AtomicLongFieldUpdaterTest::class.java, "value")

    @Operation
    fun get() = updater.get(this)

    @Operation
    fun set(newValue: Long) = updater.set(this, newValue)

    @Operation
    fun getAndSet(newValue: Long) = updater.getAndSet(this, newValue)

    @Operation
    fun compareAndSet(expectedValue: Long, newValue: Long) = updater.compareAndSet(this, expectedValue, newValue)

    @Operation
    fun addAndGet(delta: Long) = updater.addAndGet(this, delta)

    @Operation
    fun getAndAdd(delta: Long) = updater.getAndAdd(this, delta)

    @Operation
    fun incrementAndGet() = updater.incrementAndGet(this)

    @Operation
    fun getAndIncrement() = updater.getAndIncrement(this)

    @Operation
    fun decrementAndGet() = updater.decrementAndGet(this)

    @Operation
    fun getAndDecrement() = updater.getAndDecrement(this)
}

// see comment on `AtomicReferenceTest` explaining usage of custom parameter generator here
@Param(name = "string", gen = StringPoolGenerator::class)
class AtomicReferenceFieldUpdaterTest : AbstractLincheckTest() {
    @Volatile
    var value: String = ""

    private val updater = AtomicReferenceFieldUpdater.newUpdater(
        AtomicReferenceFieldUpdaterTest::class.java,
        String::class.java,
        "value"
    )

    @Operation
    fun get() = updater.get(this)

    @Operation
    fun set(@Param(name = "string") newValue: String) =
        updater.set(this, newValue)

    @Operation
    fun getAndSet(@Param(name = "string") newValue: String) =
        updater.getAndSet(this, newValue)

    @Operation
    fun compareAndSet(@Param(name = "string") expectedValue: String, @Param(name = "string") newValue: String) =
        updater.compareAndSet(this, expectedValue, newValue)
}