/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import java.io.*
import java.lang.reflect.*
import java.util.concurrent.atomic.*

abstract class Runner protected constructor(
    protected val strategy: Strategy,
    protected val testClass: Class<*>,
    protected val validationFunction: Actor?,
    protected val stateRepresentationFunction: Method?
) : Closeable {
    val classLoader = ExecutionClassLoader()
    protected val scenario: ExecutionScenario = strategy.scenario

    protected val completedOrSuspendedThreads = AtomicInteger(0)

    var currentExecutionPart: ExecutionPart? = null
        private set

    /**
     * Returns the current state representation of the test instance constructed via
     * the function marked with [StateRepresentation] annotation, or `null`
     * if no such function is provided.
     *
     * Please note, that it is unsafe to call this method concurrently with the running scenario.
     * However, it is fine to call it if the execution is paused somewhere in the middle.
     */
    open fun constructStateRepresentation(): String? = null

    /**
     * Runs the next invocation.
     */
    abstract fun run(): InvocationResult

    /**
     * This method is invoked by every test thread as the first operation.
     * @param iThread number of invoking thread
     */
    abstract fun onThreadStart(iThread: Int)

    /**
     * This method is invoked by every test thread as the last operation
     * if no exception has been thrown.
     * @param iThread number of invoking thread
     */
    abstract fun onThreadFinish(iThread: Int)

    /**
     * This method is invoked by the corresponding test thread
     * when the current coroutine suspends.
     * @param iThread number of invoking thread
     */
    abstract fun afterCoroutineSuspended(iThread: Int)

    /**
     * This method is invoked by the corresponding test thread
     * when the current coroutine is resumed.
     */
    abstract fun afterCoroutineResumed(iThread: Int)

    /**
     * This method is invoked by the corresponding test thread
     * when the current coroutine is cancelled.
     */
    abstract fun afterCoroutineCancelled(iThread: Int)

    /**
     * Returns `true` if the coroutine corresponding to
     * the actor `actorId` in the thread `iThread` is resumed.
     */
    abstract fun isCoroutineResumed(iThread: Int, actorId: Int): Boolean

    /**
     * Is invoked before each actor execution from the specified thread.
     * The invocations are inserted into the generated code.
     */
    fun onActorStart(iThread: Int) {
        strategy.onActorStart(iThread)
    }

    /**
     * Is invoked after each actor execution from the specified thread.
     * The invocations are inserted into the generated code.
     */
    fun onActorFinish() {
        strategy.onActorFinish()
    }

    /**
     * Is invoked if an actor execution has thrown an exception.
     *
     * Default implementation checks if the failure
     * was caused by an internal exception (see [isInternalException]) and re-throw in this case,
     * otherwise it treats the exception as a normal actor result.
     *
     * @param iThread the number of the invoking thread where the failure occurred.
     * @param throwable the exception that caused the actor failure.
     */
    // used in byte-code generation
    open fun onActorFailure(iThread: Int, throwable: Throwable) {
        if (isInternalException(throwable)) {
            throw throwable
        }
    }

    fun beforePart(part: ExecutionPart) {
        completedOrSuspendedThreads.set(0)
        currentExecutionPart = part
        strategy.beforePart(part)
    }

    /**
     * Closes the resources used in this runner.
     */
    override fun close() {}

    /**
     * Determines if this runner manages provided thread.
     */
    abstract fun isCurrentRunnerThread(thread: Thread): Boolean

    /**
     * @return whether all scenario threads are completed or suspended
     * Used by generated code.
     */
    val isParallelExecutionCompleted: Boolean
        get() = completedOrSuspendedThreads.get() == scenario.nThreads
}

enum class ExecutionPart {
    INIT, PARALLEL, POST, VALIDATION
}

/**
 * Checks if the provided exception is considered an internal exception.
 * Internal exceptions are those used by the Lincheck itself
 * to control execution of the analyzed code.
 */
@Suppress("DEPRECATION") // ThreadDeath
internal fun isInternalException(exception: Throwable): Boolean =
    // is used to stop thread in `FixedActiveThreadsExecutor` via `thread.stop()`
    exception is ThreadDeath ||
    // is used to abort thread in `ManagedStrategy`
    exception is LincheckAnalysisAbortedError
