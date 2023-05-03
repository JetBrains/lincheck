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
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.objectweb.asm.*
import java.lang.reflect.*
import java.util.concurrent.atomic.*
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import java.io.*

/**
 * Runner determines how to run your concurrent test. In order to support techniques
 * like fibers, it may require code transformation, so that [createTransformer] should
 * provide the corresponding transformer and [needsTransformation] should return `true`.
 */
abstract class Runner protected constructor(
    protected val strategy: Strategy,
    private val _testClass: Class<*>, // will be transformed later
    protected val validationFunctions: List<Method>,
    protected val stateRepresentationFunction: Method?
) : Closeable {
    protected var scenario = strategy.scenario // `strategy.scenario` will be transformed in `initialize`
    protected lateinit var testClass: Class<*> // not available before `initialize` call
    @Suppress("LeakingThis")
    val classLoader: ExecutionClassLoader = if (needsTransformation() || strategy.needsTransformation()) TransformationClassLoader(strategy, this)
                                            else ExecutionClassLoader()
    protected val completedOrSuspendedThreads = AtomicInteger(0)

    /**
     * This method is a part of `Runner` initialization and should be invoked after this runner
     * creation. It is separated from the constructor to perform the strategy initialization at first.
     */
    open fun initialize() {
        scenario = strategy.scenario.convertForLoader(classLoader)
        testClass = loadClass(_testClass.typeName)
    }

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
     * Loads the specified class via this runner' class loader.
     */
    private fun loadClass(className: String): Class<*> = classLoader.loadClass(className)

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
