/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.verifier.linearizability

import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.kotlinx.lincheck_test.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
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