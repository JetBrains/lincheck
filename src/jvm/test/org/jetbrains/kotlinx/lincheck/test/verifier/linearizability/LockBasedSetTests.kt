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
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.test.*
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.*
import kotlin.concurrent.*

@Param(name = "key", gen = IntGen::class, conf = "1:5")
abstract class AbstractSetTest(private val set: Set) : AbstractLincheckTest() {
    @Operation
    fun add(@Param(name = "key") key: Int): Boolean  = set.add(key)

    @Operation
    fun remove(@Param(name = "key") key: Int): Boolean = set.remove(key)

    @Operation
    operator fun contains(@Param(name = "key") key: Int): Boolean = set.contains(key)
}

class SpinLockSetTest : AbstractSetTest(SpinLockBasedSet())
class ReentrantLockSetTest : AbstractSetTest(ReentrantLockBasedSet())
class SynchronizedLockSetTest : AbstractSetTest(SynchronizedBlockBasedSet())
class SynchronizedMethodSetTest : AbstractSetTest(SynchronizedMethodBasedSet())

interface Set {
    fun add(key: Int): Boolean
    fun remove(key: Int): Boolean
    fun contains(key: Int): Boolean
}

internal class SpinLockBasedSet : Set {
    private val set = mutableSetOf<Int>()
    private val locked = AtomicBoolean()

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
        while (!locked.compareAndSet(false, true)) { Thread.yield() }
        try {
            return block()
        } finally {
            locked.set(false)
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

internal class SynchronizedBlockBasedSet : Set {
    private val set = mutableSetOf<Int>()

    override fun add(key: Int): Boolean = synchronized(set) {
        set.add(key)
    }

    override fun remove(key: Int): Boolean = synchronized(set) {
        set.remove(key)
    }

    override fun contains(key: Int): Boolean = synchronized(set) {
        set.contains(key)
    }
}

internal class SynchronizedMethodBasedSet : Set {
    private val set = mutableSetOf<Int>()

    @Synchronized
    override fun add(key: Int): Boolean = set.add(key)

    @Synchronized
    override fun remove(key: Int): Boolean = set.remove(key)

    @Synchronized
    override fun contains(key: Int): Boolean = set.contains(key)
}