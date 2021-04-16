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
package verifier.linearizability

import AbstractLincheckStressTest
import kotlinx.atomicfu.locks.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import kotlin.native.concurrent.*

abstract class AbstractSetTest(private val set: Set) : AbstractLincheckStressTest<AbstractSetTest>() {
    fun add(key: Int): Boolean = set.add(key)

    fun remove(key: Int): Boolean = set.remove(key)

    operator fun contains(key: Int): Boolean = set.contains(key)

    fun <T : LincheckStressConfiguration<AbstractSetTest>> T.customizeOperations() {
        val keyGen = IntGen("1:5")
        operation(keyGen, AbstractSetTest::add)
        operation(keyGen, AbstractSetTest::remove)
        operation(keyGen, AbstractSetTest::contains)
    }

    override fun extractState(): Any = (1..5).map { set.contains(it) }
}

class SpinLockSetTest : AbstractSetTest(SpinLockBasedSet()) {
    override fun <T : LincheckStressConfiguration<AbstractSetTest>> T.customize() {
        initialState { SpinLockSetTest() }

        customizeOperations()
    }
}

class ReentrantLockSetTest : AbstractSetTest(ReentrantLockBasedSet()) {
    override fun <T : LincheckStressConfiguration<AbstractSetTest>> T.customize() {
        initialState { ReentrantLockSetTest() }

        customizeOperations()
    }
}

class SynchronizedLockSetTest : AbstractSetTest(SynchronizedBlockBasedSet()) {
    override fun <T : LincheckStressConfiguration<AbstractSetTest>> T.customize() {
        initialState { SynchronizedLockSetTest() }

        customizeOperations()
    }
}

interface Set {
    fun add(key: Int): Boolean
    fun remove(key: Int): Boolean
    fun contains(key: Int): Boolean
}

internal class SpinLockBasedSet : Set {
    private val set = mutableSetOf<Int>()
    private val locked = AtomicInt(0)

    override fun add(key: Int): Boolean = withSpinLock {
        set.add(key)
    }

    override fun remove(key: Int): Boolean = withSpinLock {
        set.remove(key)
    }

    override fun contains(key: Int): Boolean = withSpinLock {
        set.contains(key)
    }

    private fun withSpinLock(block: () -> Boolean): Boolean {
        while (!locked.compareAndSet(0, 1)) { /* Thread.yield() in java */ }
        try {
            return block()
        } finally {
            locked.value = 0
        }
    }
}

internal class ReentrantLockBasedSet : Set {
    private val set = mutableSetOf<Int>()
    private val lock = ReentrantLock()

    override fun add(key: Int): Boolean = lock.withLock {
        set.add(key)
    }

    override fun remove(key: Int): Boolean = lock.withLock {
        set.remove(key)
    }

    override fun contains(key: Int): Boolean = lock.withLock {
        set.contains(key)
    }
}

internal class SynchronizedBlockBasedSet : SynchronizedObject(), Set {
    private val set = mutableSetOf<Int>()

    override fun add(key: Int): Boolean = synchronized(this) {
        set.add(key)
    }

    override fun remove(key: Int): Boolean = synchronized(this) {
        set.remove(key)
    }

    override fun contains(key: Int): Boolean = synchronized(this) {
        set.contains(key)
    }
}