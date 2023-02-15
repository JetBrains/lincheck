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
package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.objectweb.asm.*
import java.io.*
import java.lang.reflect.*
import java.util.concurrent.atomic.*

/**
 * Runner determines how to run your concurrent test. In order to support techniques
 * like fibers, it may require code transformation, so that [createTransformer] should
 * provide the corresponding transformer and [needsTransformation] should return `true`.
 */
abstract class Runner protected constructor(
    protected val strategy: Strategy,
    protected val testClass: Class<*>, // will be transformed later
    protected val validationFunctions: List<Method>,
    protected val stateRepresentationFunction: Method?
) : Closeable {
    protected val scenario = strategy.scenario // `strategy.scenario` will be transformed in `initialize`
    @Suppress("LeakingThis")
    val classLoader = ExecutionClassLoader()
    protected val completedOrSuspendedThreads = AtomicInteger(0)

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
     * Creates a transformer required for this runner.
     * Throws [UnsupportedOperationException] by default.
     *
     * @return class visitor which transform the code due to support this runner.
     */
    open fun createTransformer(cv: ClassVisitor): ClassVisitor? = null

    /**
     * This method should return `true` if code transformation
     * is required for this runner; returns `false` by default.
     */
    open fun needsTransformation(): Boolean = false

    /**
     * Runs the next invocation.
     */
    abstract fun run(): InvocationResult

    /**
     * This method is invoked by every test thread as the first operation.
     * @param iThread number of invoking thread
     */
    open fun onStart(iThread: Int) {}

    /**
     * This method is invoked by every test thread as the last operation
     * if no exception has been thrown.
     * @param iThread number of invoking thread
     */
    open fun onFinish(iThread: Int) {}

    /**
     * This method is invoked by the corresponding test thread
     * when an unexpected exception is thrown.
     */
    open fun onFailure(iThread: Int, e: Throwable) {}

    /**
     * This method is invoked by the corresponding test thread
     * when the current coroutine suspends.
     * @param iThread number of invoking thread
     */
    open fun afterCoroutineSuspended(iThread: Int): Unit = throw UnsupportedOperationException("Coroutines are not supported")

    /**
     * This method is invoked by the corresponding test thread
     * when the current coroutine is resumed.
     */
    open fun afterCoroutineResumed(iThread: Int): Unit = throw UnsupportedOperationException("Coroutines are not supported")

    /**
     * This method is invoked by the corresponding test thread
     * when the current coroutine is cancelled.
     */
    open fun afterCoroutineCancelled(iThread: Int): Unit = throw UnsupportedOperationException("Coroutines are not supported")

    /**
     * Returns `true` if the coroutine corresponding to
     * the actor `actorId` in the thread `iThread` is resumed.
     */
    open fun isCoroutineResumed(iThread: Int, actorId: Int): Boolean = throw UnsupportedOperationException("Coroutines are not supported")

    /**
     * Is invoked before each actor execution from the specified thread.
     * The invocations are inserted into the generated code.
     */
    fun onActorStart(iThread: Int) {
        strategy.onActorStart(iThread)
    }

    /**
     * Closes the resources used in this runner.
     */
    override fun close() {}

    /**
     * @return whether all scenario threads are completed or suspended
     * Used by generated code.
     */
    val isParallelExecutionCompleted: Boolean
        get() = completedOrSuspendedThreads.get() == scenario.threads
}
