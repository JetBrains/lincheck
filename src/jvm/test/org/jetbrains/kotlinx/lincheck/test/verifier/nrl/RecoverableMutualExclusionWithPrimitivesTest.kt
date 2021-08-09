/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.verifier.nrl

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.DeadlockWithDumpFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.forClasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.test.verifier.linearizability.SequentialCounter

private const val THREADS = 3

internal class RecoverableMutualExclusionWithPrimitivesTest : AbstractNVMLincheckTest(Recover.NRL, THREADS, SequentialCounter::class) {
    private val counter = CounterWithLock(THREADS + 2, LockWithPrimitives(THREADS + 2))

    @Operation
    fun inc(@Param(gen = ThreadIdGen::class) threadId: Int): Int = counter.inc(threadId)

    override fun testWithStressStrategy() {
        println("${this::class.qualifiedName}:testWithStressStrategy test is ignored as no special atomic primitives available.")
    }

    override fun ModelCheckingOptions.customize() {
        actorsBefore(0)
        actorsAfter(1)
        addGuarantee(forClasses(LockWithPrimitives::class.qualifiedName!!).methods { it == "atomically" }.treatAsAtomic())
    }
}

interface DurableLock {
    fun lock(threadId: Int)
    fun unlock(threadId: Int)
}

internal class CounterWithLock(threads: Int, private val lock: DurableLock) {
    private val value = nonVolatile(0)
    private val cp = Array(threads) { nonVolatile(0) }
    private val before = Array(threads) { nonVolatile(0) }

    @Recoverable(recoverMethod = "incRecover", beforeMethod = "incBefore")
    fun inc(threadId: Int) = incInternal(threadId)

    private fun incInternal(threadId: Int): Int {
        lock.lock(threadId)
        val result = value.value + 1
        before[threadId].value = result
        before[threadId].flush()
        cp[threadId].value = 1
        cp[threadId].flush()

        value.value = result
        value.flush()

        cp[threadId].value = 2
        cp[threadId].flush()

        lock.unlock(threadId)

        cp[threadId].value = 3
        cp[threadId].flush()
        return result
    }

    fun incBefore(threadId: Int) {
        cp[threadId].value = 0
        cp[threadId].flush()
    }

    fun incRecover(threadId: Int) = when (cp[threadId].value) {
        0 -> incInternal(threadId)
        1 -> {
            cp[threadId].flush()
            lock.lock(threadId)
            // do not care about data race as I'm still the owner of the lock
            value.value = before[threadId].value
            value.flush()
            cp[threadId].value = 2
            cp[threadId].flush()
            lock.unlock(threadId)
            cp[threadId].value = 3
            cp[threadId].flush()
            before[threadId].value
        }
        2 -> {
            cp[threadId].flush()
            // required for lock release
            lock.lock(threadId)
            lock.unlock(threadId)
            cp[threadId].value = 3
            cp[threadId].flush()
            before[threadId].value
        }
        3 -> before[threadId].value
        else -> error("")
    }
}

private class QNode {
    @Volatile
    var next: QNode? = null

    @Volatile
    var prev: QNode? = this

    @Volatile
    var linked: Boolean = false
}

/**
 * @see <a href="https://sci-hub.mksa.top/10.1145/3087801.3087819">Recoverable Mutual Exclusion in Sub-logarithmic Time</a>
 */
internal class LockWithPrimitives(threads: Int) : DurableLock {
    private val q = Array(threads) { QNode() }

    @Volatile
    private var t: QNode? = null

    override fun lock(threadId: Int) {
        val qi = q[threadId]
        var previous = qi.prev
        if (previous === qi) {
            qi.next = null
            qi.linked = false
            atomically {
                qi.prev = t
                t = qi
            }
            previous = qi.prev
        }
        if (previous !== null) {
            atomically {
                if (previous.next === null && !qi.linked) {
                    previous.next = qi
                    qi.linked = true
                }
            }
            while (qi.prev !== null);
        }
    }

    override fun unlock(threadId: Int) {
        val qi = q[threadId]
        val success = atomically<Boolean> {
            (t === qi && qi.prev === null).also {
                if (it) {
                    t = null
                    qi.prev = qi
                }
            }
        }
        if (!success) {
            while (qi.next === null);
            atomically {
                qi.prev = qi.next!!.prev
                qi.next!!.prev = null
            }
        }
    }

    private fun atomically(action: () -> Unit) {
        action()
    }

    private fun <T> atomically(action: () -> T): T {
        return action()
    }
}

internal class MutualExclusionFailingTest : AbstractNVMLincheckFailingTest(Recover.NRL, THREADS, SequentialCounter::class, false, DeadlockWithDumpFailure::class) {
    private val counter = CounterWithLock(THREADS + 2, SimplestLockEver())

    @Operation
    fun inc(@Param(gen = ThreadIdGen::class) threadId: Int): Int = counter.inc(threadId)

    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(0)
        actorsAfter(1)
    }

    override fun ModelCheckingOptions.customize() {
        threads(2)
        actorsPerThread(2)
        invocationsPerIteration(1e6.toInt())
    }
}

/*
With adding `owner.flush()` at the end of lock/unlock methods this lock works OK.
 */
private class SimplestLockEver : DurableLock {
    private val owner = nonVolatile(-1)

    override fun lock(threadId: Int) {
        while (!(owner.compareAndSet(-1, threadId) || owner.compareAndSet(threadId, threadId)));
    }

    override fun unlock(threadId: Int) {
        owner.compareAndSet(threadId, -1)
    }
}
