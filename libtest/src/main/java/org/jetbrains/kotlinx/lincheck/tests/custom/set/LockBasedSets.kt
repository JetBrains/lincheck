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

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface Set {
    fun add(key: Int): Boolean
    fun remove(key: Int): Boolean
    fun contains(key: Int): Boolean
}

internal class SpinLockSet : Set {
    private val set = mutableSetOf<Int>()
    private val lock = AtomicBoolean()

    override fun add(key: Int): Boolean = activeSynchronized {
        set.add(key)
    }

    override fun remove(key: Int): Boolean = activeSynchronized {
        set.remove(key)
    }

    override fun contains(key: Int): Boolean = activeSynchronized {
        set.contains(key)
    }

    private fun activeSynchronized(code: () -> Boolean): Boolean {
        while (!lock.compareAndSet(false, true));

        val result = code()

        lock.set(false)

        return result
    }
}

internal class ReentrantLockSet : Set {
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

internal class SynchronizedLockSet : Set {
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

internal class SynchronizedMethodSet : Set {
    private val set = mutableSetOf<Int>()

    @Synchronized
    override fun add(key: Int): Boolean = set.add(key)

    @Synchronized
    override fun remove(key: Int): Boolean = set.remove(key)


    @Synchronized
    override fun contains(key: Int): Boolean = set.contains(key)
}