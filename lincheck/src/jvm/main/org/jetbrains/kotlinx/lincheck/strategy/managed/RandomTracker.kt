/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.util.mutableThreadMapOf
import sun.nio.ch.lincheck.InjectedRandom

/**
 * Tracks per-thread deterministic random number generators.
 *
 * Each registered thread is associated with an [InjectedRandom] instance seeded
 * deterministically by the thread id, so that random calls produce reproducible
 * results across invocations. This tracker is the single source of randomness for
 * the deterministic-random method handling (see
 * [org.jetbrains.kotlinx.lincheck.strategy.nativecalls.getDeterministicRandomMethodDescriptorOrNull]).
 */
interface RandomTracker {

    /**
     * Registers a thread with the given id, creating its deterministic random generator.
     *
     * @param threadId the id of the thread to register.
     */
    fun registerThread(threadId: Int)

    /**
     * Returns the deterministic random generator associated with the given thread.
     *
     * @param threadId the id of the thread.
     */
    fun getThreadLocalRandom(threadId: Int): InjectedRandom

    /**
     * Returns the next pseudorandom integer for the given thread.
     *
     * @param threadId the id of the thread.
     */
    fun nextInt(threadId: Int): Int

    /**
     * Resets the state of the random tracker.
     */
    fun reset()

}

fun RandomTracker(): RandomTracker =
    RandomTrackerImpl()

internal class RandomTrackerImpl : RandomTracker {

    // Random instances with fixed seeds to replace random calls in instrumented code.
    private val randoms = mutableThreadMapOf<InjectedRandom>()

    override fun registerThread(threadId: Int) {
        randoms[threadId] = InjectedRandom(threadId + 239L)
    }

    override fun getThreadLocalRandom(threadId: Int): InjectedRandom =
        randoms[threadId]!!

    override fun nextInt(threadId: Int): Int =
        getThreadLocalRandom(threadId).nextInt()

    override fun reset() {
        randoms.clear()
    }

}
