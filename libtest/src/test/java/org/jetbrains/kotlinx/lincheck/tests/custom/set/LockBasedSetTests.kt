/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.tests.custom.set

import org.jetbrains.kotlinx.lincheck.ErrorType
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.tests.AbstractLinCheckTest

@Param(name = "key", gen = IntGen::class, conf = "1:5")
class SpinLockSetCorrectTest : AbstractLinCheckTest(expectedError = ErrorType.NO_ERROR) {
    private val set = SpinLockSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean  = set.add(key)

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean = set.remove(key)

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean = set.contains(key)

    override fun extractState(): Any = (1..5).map { set.contains(it) }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
class ReentrantLockSetCorrectTest : AbstractLinCheckTest(expectedError = ErrorType.NO_ERROR) {
    private val set = ReentrantLockSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean  = set.add(key)

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean = set.remove(key)

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean = set.contains(key)

    override fun extractState(): Any = (1..5).map { set.contains(it) }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
class SynchronizedLockSetCorrectTest : AbstractLinCheckTest(expectedError = ErrorType.NO_ERROR) {
    private val set = SynchronizedLockSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean  = set.add(key)

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean = set.remove(key)

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean = set.contains(key)

    override fun extractState(): Any = (1..5).map { set.contains(it) }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
class SynchronizedMethodSetCorrectTest : AbstractLinCheckTest(expectedError = ErrorType.NO_ERROR) {
    private val set = SynchronizedMethodSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean  = set.add(key)

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean = set.remove(key)

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean = set.contains(key)

    override fun extractState(): Any = (1..5).map { set.contains(it) }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
class SpinLockSetWrongGuaranteeTest : AbstractLinCheckTest(expectedError = ErrorType.OBSTRUCTION_FREEDOM_VIOLATED, checkObstructionFreedom = true) {
    private val set = SpinLockSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean  = set.add(key)

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean = set.remove(key)

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean = set.contains(key)

    override fun extractState(): Any = (1..5).map { set.contains(it) }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
class ReentrantLockSetWrongGuaranteeTest : AbstractLinCheckTest(expectedError = ErrorType.OBSTRUCTION_FREEDOM_VIOLATED, checkObstructionFreedom = true) {
    private val set = ReentrantLockSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean  = set.add(key)

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean = set.remove(key)

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean = set.contains(key)

    override fun extractState(): Any = (1..5).map { set.contains(it) }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
class SynchronizedLockSetWrongGuaranteeTest : AbstractLinCheckTest(expectedError = ErrorType.OBSTRUCTION_FREEDOM_VIOLATED, checkObstructionFreedom = true) {
    private val set = SynchronizedLockSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean  = set.add(key)

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean = set.remove(key)

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean = set.contains(key)

    override fun extractState(): Any = (1..5).map { set.contains(it) }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
class SynchronizedMethodSetWrongGuaranteeTest : AbstractLinCheckTest(expectedError = ErrorType.OBSTRUCTION_FREEDOM_VIOLATED, checkObstructionFreedom = true) {
    private val set = SynchronizedMethodSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean  = set.add(key)

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean = set.remove(key)

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean = set.contains(key)

    override fun extractState(): Any = (1..5).map { set.contains(it) }
}