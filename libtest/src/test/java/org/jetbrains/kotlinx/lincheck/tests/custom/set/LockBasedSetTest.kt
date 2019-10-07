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

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.randomsearch.RandomSearchCTest
import org.jetbrains.kotlinx.lincheck.tests.AbstractLincheckTest
import org.junit.Ignore
import org.junit.Test
import tests.custom.set.*

@Param(name = "key", gen = IntGen::class, conf = "1:5")
internal class ActiveLockSetTestCorrect : AbstractLincheckTest(false, false) {
    override fun extractState(): Any {
        return (1..5).map { set.contains(it) }
    }

    private val set = ActiveLockSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean {
        return set.add(key)
    }

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean {
        return set.remove(key)
    }

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean {
        return set.contains(key)
    }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
internal class ActiveLockSetTestWrongGuarrantee : AbstractLincheckTest(true, true) {
    override fun extractState(): Any {
        return (1..5).map { set.contains(it) }
    }

    private val set = ActiveLockSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean {
        return set.add(key)
    }

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean {
        return set.remove(key)
    }

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean {
        return set.contains(key)
    }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
internal class ReentrantLockSetTestCorrect : AbstractLincheckTest(false, false) {
    override fun extractState(): Any {
        return (1..5).map { set.contains(it) }
    }

    private val set = ReentrantLockSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean {
        return set.add(key)
    }

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean {
        return set.remove(key)
    }

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean {
        return set.contains(key)
    }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
internal class ReentrantLockSetTestWrongGuarrantee : AbstractLincheckTest(true, true) {
    override fun extractState(): Any {
        return (1..5).map { set.contains(it) }
    }

    private val set = ReentrantLockSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean {
        return set.add(key)
    }

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean {
        return set.remove(key)
    }

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean {
        return set.contains(key)
    }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
internal class SynchronizedLockSetTestCorrect : AbstractLincheckTest(false, false) {
    override fun extractState(): Any {
        return (1..5).map { set.contains(it) }
    }

    private val set = SynchronizedLockSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean {
        return set.add(key)
    }

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean {
        return set.remove(key)
    }

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean {
        return set.contains(key)
    }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
internal class SynchronizedLockSetTestWrongGuarrantee : AbstractLincheckTest(true, true) {
    override fun extractState(): Any {
        return (1..5).map { set.contains(it) }
    }

    private val set = SynchronizedLockSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean {
        return set.add(key)
    }

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean {
        return set.remove(key)
    }

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean {
        return set.contains(key)
    }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
internal class SynchronizedMethodSetTestCorrect : AbstractLincheckTest(false, false) {
    override fun extractState(): Any {
        return (1..5).map { set.contains(it) }
    }

    private val set = SynchronizedMethodSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean {
        return set.add(key)
    }

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean {
        return set.remove(key)
    }

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean {
        return set.contains(key)
    }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
internal class SynchronizedMethodSetTestWrongGuarrantee : AbstractLincheckTest(true, true) {
    override fun extractState(): Any {
        return (1..5).map { set.contains(it) }
    }

    private val set = SynchronizedMethodSet()

    @Operation
    fun add(@Param(name = "key") key: Int): Boolean {
        return set.add(key)
    }

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean {
        return set.remove(key)
    }

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean {
        return set.contains(key)
    }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
internal class SuspendMutexSetTestCorrect {
    private val set = SuspendMutexSet()

    @Operation
    suspend fun add(@Param(name = "key") key: Int): Boolean {
        return set.add(key)
    }

    @Operation
    suspend fun remove(@Param(name = "key") key: Int): Boolean {
        return set.remove(key)
    }

    @Operation
    suspend fun contains(@Param(name = "key") key: Int): Boolean {
        return set.contains(key)
    }


    @Ignore("disabled, because suspension currently is a cause of non-linerizability")
    @Test
    fun test() {
        LinChecker.check(SuspendMutexSetTestCorrect::class.java)
    }
}

@Param(name = "key", gen = IntGen::class, conf = "1:5")
@RandomSearchCTest(checkObstructionFreedom = true, requireStateEquivalenceImplCheck = false)
internal class SuspendMutexSetTestWrongGuarrantee {
    private val set = SuspendMutexSet()

    @Operation
    suspend fun add(@Param(name = "key") key: Int): Boolean {
        return set.add(key)
    }

    @Operation
    suspend fun remove(@Param(name = "key") key: Int): Boolean {
        return set.remove(key)
    }

    @Operation
    suspend fun contains(@Param(name = "key") key: Int): Boolean {
        return set.contains(key)
    }

    @Ignore("disabled due to the fact that currently suspension point does not violate obstruction-freedom")
    @Test(expected = AssertionError::class)
    fun test() {
        LinChecker.check(SuspendMutexSetTestWrongGuarrantee::class.java)
    }
}