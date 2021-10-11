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

private class InvalidThreadIdStateError(s: String) : Throwable(s)

private const val INIT = (0).toByte()
private const val PARALLEL = (1).toByte()
private const val POST = (2).toByte()

class NVMState(scenario: ExecutionScenario, recoverModel: RecoverabilityModel) : ExecutionCallback {
    private val threads = scenario.threads

    internal val crash = Crash(this, recoverModel)
    internal val cache = NVMCache(threads)
    internal val probability = Probability(scenario, recoverModel, this)

    @Volatile
    private var state = INIT

    @Volatile
    internal var crashesEnabled = false

    private val _crashes = Array(threads) { mutableListOf<CrashError>() }
    private val crashResults = Collections.synchronizedList(mutableListOf<CrashResult>())
    private val _crashesCount = atomic(0)
    private val maxCrashesPerThread = atomic(0)
    private val executedActors = IntArray(threads)

    val crashesCount get() = _crashesCount.value
    val maxCrashesCountPerThread get() = maxCrashesPerThread.value

    internal fun currentThreadId(): Int = when (state) {
        INIT -> 0
        PARALLEL -> Thread.currentThread().let {
            if (it is FixedActiveThreadsExecutor.TestThread) return@let it.iThread + 1
            throw InvalidThreadIdStateError("State is PARALLEL but thread is not test thread.")
        }
        POST -> threads + 1
        else -> throw AssertionError("Invalid state $state")
    }

    internal fun registerCrash(threadId: Int, crash: CrashError) {
        crash.actorIndex = executedActors[threadId - 1]
        _crashes[threadId - 1].add(crash)
        _crashesCount.incrementAndGet()
        val myThreadCrashes = _crashes[threadId - 1].size
        while (true) {
            val curMax = maxCrashesPerThread.value
            if (curMax >= myThreadCrashes) break
            if (maxCrashesPerThread.compareAndSet(curMax, myThreadCrashes)) break
        }
    }

    internal fun registerCrashResult(crashResult: CrashResult) {
        crashResults.add(crashResult)
    }

    internal fun setCrashedActors() {
        for (result in crashResults) {
            result.crashedActors = executedActors.copyOf(threads)
        }
        crashResults.clear()
    }

    override fun getCrashes() = _crashes.toList()

    override fun onFinish(threadId: Int) {
        // mark thread as finished
        executedActors[threadId]++
        crash.exitThread()
    }

    override fun onStart(threadId: Int) {
        crash.registerThread()
    }

    override fun beforeInit(recoverModel: RecoverabilityModel) {
        _crashesCount.value = 0
        maxCrashesPerThread.value = 0
        _crashes.indices.forEach { _crashes[it] = mutableListOf() }
        cache.clear()
        probability.setNewInvocation(recoverModel)
        crash.reset()
        crashResults.clear()
        executedActors.fill(-1)
        state = INIT
        crash.registerThread()
        crashesEnabled = false
    }

    override fun beforeParallel() {
        crash.exitThread()
        state = PARALLEL
        crashesEnabled = true
    }

    override fun beforePost() {
        crashesEnabled = false
        state = POST
        crash.registerThread()
    }

    override fun afterPost() {
        crash.exitThread()
    }

    override fun onActorStart(threadId: Int) {
        executedActors[threadId]++
    }

    override fun onEnterActorBody(threadId: Int, actorId: Int) = probability.onEnterActorBody(threadId + 1, actorId)
    override fun onExitActorBody(threadId: Int, actorId: Int) = probability.onExitActorBody(threadId + 1, actorId)
}
