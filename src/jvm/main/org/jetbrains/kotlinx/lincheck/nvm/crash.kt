/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.nvm

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.runner.RecoverableStateContainer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * This exception is used to emulate system crash.
 * Must be ignored by user code, namely 'catch (e: Throwable)' constructions should pass this exception.
 */
class CrashError(var actorIndex: Int = -1) : Error()

object Crash {
    /**
     * Crash simulation.
     * @throws CrashError
     */
    private fun crash(threadId: Int) {
        NVMCache.crash(threadId)
        throw CRASH.also { RecoverableStateContainer.registerCrash(threadId, it) }
    }

    private val CRASH = CrashError()

    private val threads: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap())
    private val waitingCont = atomic(0)

    /**
     * Random crash simulation. Produces a single thread crash or a system crash.
     * On a system crash a thread waits for other threads to reach this method call.
     */
    @JvmStatic
    fun possiblyCrash() {
//        if (waitingCont.value > 0) {
//            val threadId = threadId()
//            awaitSystemCrash(threadId)
//            crash(threadId)
//        }
        if (Probability.shouldCrash()) {
            val threadId = RecoverableStateContainer.threadId()
//            if (Probability.shouldSystemCrash()) {
//                awaitSystemCrash(threadId)
//            }
            crash(threadId)
        }
    }

    private fun awaitSystemCrash(threadId: Int) {
        waitingCont.incrementAndGet()
        threads.add(threadId)
        var threadsCount: Int
        do {
            threadsCount = threads.size
        } while (waitingCont.value < threadsCount)
        waitingCont.compareAndSet(threadsCount, 0)
    }

    /** Should be called when thread finished. */
    @JvmStatic
    fun exit(threadId: Int) {
        threads.remove(threadId)
    }
}
