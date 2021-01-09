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

package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.execution.*

/**
 * Runner determines how to run your concurrent test. In order to support techniques
 * like fibers, it may require code transformation, so that [createTransformer] should
 * provide the corresponding transformer and [needsTransformation] should return `true`.
 */
expect abstract class Runner {
    protected var scenario: ExecutionScenario

    /**
     * This method is a part of `Runner` initialization and should be invoked after this runner
     * creation. It is separated from the constructor to perform the strategy initialization at first.
     */
    open fun initialize()

    /**
     * Returns the current state representation of the test instance constructed via
     * the function marked with [StateRepresentation] annotation, or `null`
     * if no such function is provided.
     *
     * Please note, that it is unsafe to call this method concurrently with the running scenario.
     * However, it is fine to call it if the execution is paused somewhere in the middle.
     */
    open fun constructStateRepresentation(): String?

    /**
     * This method should return `true` if code transformation
     * is required for this runner; returns `false` by default.
     */
    open fun needsTransformation(): Boolean

    /**
     * This method is invoked by every test thread as the first operation.
     * @param iThread number of invoking thread
     */
    open fun onStart(iThread: Int)

    /**
     * This method is invoked by every test thread as the last operation
     * if no exception has been thrown.
     * @param iThread number of invoking thread
     */
    open fun onFinish(iThread: Int)

    /**
     * This method is invoked by the corresponding test thread
     * when an unexpected exception is thrown.
     */
    open fun onFailure(iThread: Int, e: Throwable)

    /**
     * This method is invoked by the corresponding test thread
     * when the current coroutine suspends.
     * @param iThread number of invoking thread
     */
    open fun afterCoroutineSuspended(iThread: Int)

    /**
     * This method is invoked by the corresponding test thread
     * when the current coroutine is resumed.
     */
    open fun afterCoroutineResumed(iThread: Int)

    /**
     * This method is invoked by the corresponding test thread
     * when the current coroutine is cancelled.
     */
    open fun afterCoroutineCancelled(iThread: Int)

    /**
     * Returns `true` if the coroutine corresponding to
     * the actor `actorId` in the thread `iThread` is resumed.
     */
    open fun isCoroutineResumed(iThread: Int, actorId: Int): Boolean

    /**
     * Is invoked before each actor execution from the specified thread.
     * The invocations are inserted into the generated code.
     */
    fun onActorStart(iThread: Int)

    /**
     * Closes the resources used in this runner.
     */
    fun close()

    /**
     * @return whether all scenario threads are completed or suspended
     * Used by generated code.
     */
    val isParallelExecutionCompleted: Boolean

}