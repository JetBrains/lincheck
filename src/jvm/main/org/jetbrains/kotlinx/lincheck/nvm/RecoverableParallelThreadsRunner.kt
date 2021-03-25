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
import org.jetbrains.kotlinx.lincheck.runner.FixedActiveThreadsExecutor
import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner
import org.jetbrains.kotlinx.lincheck.runner.UseClocks
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.objectweb.asm.ClassVisitor
import java.lang.reflect.Method

internal enum class ExecutionState {
    INIT, PARALLEL, POST
}

object RecoverableStateContainer {
    @Volatile
    internal var state = ExecutionState.INIT
    internal var threads = 0

    @Volatile
    internal var crashesEnabled = false

    private var crashes = initCrashes()
    private val crashesCount = atomic(0)
    private val maxCrashesPerThread = atomic(0)
    private var executedActors = IntArray(NVMCache.MAX_THREADS_NUMBER) { -1 }

    private fun initCrashes() = Array(NVMCache.MAX_THREADS_NUMBER) { mutableListOf<CrashError>() }

    fun threadId(): Int = when (state) {
        ExecutionState.INIT -> 0
        ExecutionState.PARALLEL -> Thread.currentThread().let {
            if (it is FixedActiveThreadsExecutor.TestThread) it.iThread + 1 else -1
        }
        ExecutionState.POST -> threads + 1
    }

    internal fun registerCrash(threadId: Int, crash: CrashError) {
        crash.actorIndex = executedActors[threadId]
        crashes[threadId].add(crash)
        crashesCount.incrementAndGet()
        val myThreadCrashes = crashes[threadId].size
        while (true) {
            val curMax = maxCrashesPerThread.value
            if (curMax >= myThreadCrashes) break
            if (maxCrashesPerThread.compareAndSet(curMax, myThreadCrashes)) break
        }
    }

    internal fun clearCrashes() = crashes.also {
        crashesCount.value = 0
        maxCrashesPerThread.value = 0
        crashes = initCrashes()
        executedActors = IntArray(NVMCache.MAX_THREADS_NUMBER) { -1 }
    }

    internal fun actorStarted(threadId: Int) {
        executedActors[threadId]++
    }

    fun crashesCount() = crashesCount.value
    fun maxCrashesCountPerThread() = maxCrashesPerThread.value
}

internal class RecoverableParallelThreadsRunner(
    strategy: Strategy,
    testClass: Class<*>,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?,
    timeoutMs: Long,
    useClocks: UseClocks,
    recoverModel: RecoverabilityModel
) : ParallelThreadsRunner(
    strategy,
    testClass,
    validationFunctions,
    stateRepresentationFunction,
    timeoutMs,
    useClocks,
    recoverModel
) {
    override fun needsTransformation() = true
    override fun createTransformer(cv: ClassVisitor) =
        recoverModel.createTransformer(super.createTransformer(cv), _testClass)

    override fun onFinish(iThread: Int) {
        super.onFinish(iThread)
        Crash.exit(iThread + 1)
    }

    override fun onStart(iThread: Int) {
        super.onStart(iThread)
        Crash.register(iThread + 1)
    }

    override fun beforeInit() {
        super.beforeInit()
        NVMCache.clear()
        val actors = scenario.parallelExecution.sumBy { it.size }
        Probability.setNewInvocation(actors, recoverModel)
        Crash.reset(recoverModel)
        RecoverableStateContainer.state = ExecutionState.INIT
        Crash.register(0)
        RecoverableStateContainer.crashesEnabled = false
    }

    override fun beforeParallel(threads: Int) {
        Crash.exit(0)
        super.beforeParallel(threads)
        RecoverableStateContainer.threads = threads
        RecoverableStateContainer.state = ExecutionState.PARALLEL
        RecoverableStateContainer.crashesEnabled = true
    }

    override fun beforePost() {
        super.beforePost()
        RecoverableStateContainer.crashesEnabled = false
        RecoverableStateContainer.state = ExecutionState.POST
        Crash.register(scenario.threads + 1)
    }

    override fun afterPost() {
        super.afterPost()
        Crash.exit(scenario.threads + 1)
        RecoverableStateContainer.state = ExecutionState.INIT
    }

    override fun onActorStart(iThread: Int) {
        super.onActorStart(iThread)
        RecoverableStateContainer.actorStarted(iThread + 1)
    }

    override fun onBeforeActorStart() {
        super.onBeforeActorStart()
        RecoverableStateContainer.actorStarted(0)
    }

    override fun onAfterActorStart() {
        super.onAfterActorStart()
        RecoverableStateContainer.actorStarted(scenario.parallelExecution.size + 1)
    }

    override fun getCrashes(): List<List<CrashError>> = RecoverableStateContainer.clearCrashes().toList()

}
