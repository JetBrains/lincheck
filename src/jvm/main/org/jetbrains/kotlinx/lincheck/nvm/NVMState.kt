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

package org.jetbrains.kotlinx.lincheck.nvm

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.CrashResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.FixedActiveThreadsExecutor
import java.util.*

// for debug usage
private class InvalidThreadIdStateError(s: String) : Throwable(s)

private const val INIT = (0).toByte()
private const val PARALLEL = (1).toByte()
private const val POST = (2).toByte()

/** Handles the state of NVM execution and reacts to [ExecutionCallback] events. */
class NVMState(scenario: ExecutionScenario, recoverModel: RecoverabilityModel) : ExecutionCallback {
    private val threads = scenario.threads

    internal val crash = Crash(this, recoverModel)
    internal val cache = NVMCache(this, threads)
    internal val probability = Probability(scenario, recoverModel, this)

    @Volatile
    private var currentState = INIT

    /** Crashes are enabled only in the parallel part. */
    @Volatile
    internal var crashesEnabled = false

    private val occurredCrashes = Array(threads) { mutableListOf<CrashError>() }
    private val crashResults = Collections.synchronizedList(mutableListOf<CrashResult>())
    private val totalCrashes = atomic(0)
    private val maxCrashesPerThread = atomic(0)

    /** The number of actors already executed in every parallel thread. */
    private val executedActors = IntArray(threads)

    val crashesCount get() = totalCrashes.value
    val maxCrashesCountPerThread get() = maxCrashesPerThread.value

    internal fun currentThreadId(): Int = when (currentState) {
        INIT -> 0
        PARALLEL -> Thread.currentThread().let {
            if (it is FixedActiveThreadsExecutor.TestThread) return@let it.iThread + 1
            throw InvalidThreadIdStateError("State is PARALLEL but thread is not test thread.")
        }
        POST -> threads + 1
        else -> throw AssertionError("Invalid state $currentState")
    }

    internal fun registerCrash(threadId: Int, crash: CrashError) {
        crash.actorIndex = executedActors[threadId - 1]
        occurredCrashes[threadId - 1].add(crash)
        totalCrashes.incrementAndGet()
        // update maximum value
        val myThreadCrashes = occurredCrashes[threadId - 1].size
        do {
            val curMax = maxCrashesPerThread.value
            if (curMax >= myThreadCrashes) break
        } while (!maxCrashesPerThread.compareAndSet(curMax, myThreadCrashes))
    }

    internal fun registerCrashResult(crashResult: CrashResult) {
        crashResults.add(crashResult)
    }

    /** Set actors' state to the crash results that were previously added by [registerCrashResult]. */
    internal fun setCrashedActors() {
        for (result in crashResults) {
            result.crashedActors = executedActors.copyOf(threads)
        }
        crashResults.clear()
    }

    override fun getCrashes() = occurredCrashes.toList()

    override fun beforeInit(recoverModel: RecoverabilityModel) {
        // reset all services
        cache.clear()
        crash.reset()
        probability.setNewInvocation(recoverModel)

        // reset internal state
        totalCrashes.value = 0
        maxCrashesPerThread.value = 0
        occurredCrashes.indices.forEach { occurredCrashes[it] = mutableListOf() }
        crashResults.clear()
        executedActors.fill(-1)

        // start new invocation
        currentState = INIT
        crash.registerThread() // init thread started
        crashesEnabled = false
    }

    override fun beforeParallel() {
        crash.exitThread() // init thread finished
        currentState = PARALLEL
        crashesEnabled = true
    }

    override fun onStart(threadId: Int) {
        crash.registerThread() // parallel thread started
    }

    override fun onActorStart(threadId: Int) {
        executedActors[threadId]++
    }

    override fun onFinish(threadId: Int) {
        executedActors[threadId]++ // the last actor finished
        crash.exitThread() // parallel thread finished
    }

    override fun beforePost() {
        currentState = POST
        crash.registerThread() // post thread started
        crashesEnabled = false
    }

    override fun afterPost() {
        crash.exitThread() // post thread finished
    }

    override fun onEnterActorBody(threadId: Int, actorId: Int) = probability.onEnterActorBody(threadId + 1, actorId)
    override fun onExitActorBody(threadId: Int, actorId: Int) = probability.onExitActorBody(threadId + 1, actorId)
}
