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
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.objectweb.asm.*
import java.lang.reflect.*
import java.util.concurrent.atomic.*
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation

/**
 * Runner determines how to run your concurrent test. In order to support techniques
 * like fibers, it may require code transformation, so [.needsTransformation]
 * method has to return `true` and [.createTransformer]
 * one has to be implemented.
 */
abstract class Runner protected constructor(protected val strategy: Strategy, protected var testClass: Class<*>,
                                            protected val validationFunctions: List<Method>, protected val stateRepresentationFunction: Method?) {
    protected var scenario: ExecutionScenario = strategy.scenario // will be transformed later
    @Suppress("LeakingThis")
    val classLoader: ExecutionClassLoader = if (needsTransformation() || strategy.needsTransformation()) TransformationClassLoader(strategy, this) else ExecutionClassLoader()
    protected val completedOrSuspendedThreads = AtomicInteger(0)

    /**
     * This method is a part of Runner initialization.
     * It is separated from constructor to allow certain strategy initialization steps in between.
     * That may be needed, for example, for transformation logic and `ManagedStateHolder` initialization.
     */
    open fun initialize() {
        scenario = strategy.scenario.convertForLoader(classLoader)
        testClass = loadClass(testClass.typeName)
    }

    /**
     * Returns the current state representation of the test instance constructed via
     * the function marked with [StateRepresentation] annotation, or `null`
     * if no such function is provided.
     *
     * Please not, that it is unsafe to call this method concurrently with the running scenario.
     * However, it is fine to call it if the execution is paused somewhere in the middle.
     */
    open fun constructStateRepresentation(): String? = null

    /**
     * Loads class using runner's class loader
     */
    private fun loadClass(className: String): Class<*> {
        return try {
            classLoader.loadClass(className)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("Cannot load class $className", e)
        }
    }

    /**
     * Creates required for this runner transformer.
     * Throws [UnsupportedOperationException] by default.
     *
     * @return class visitor which transform the code due to support this runner.
     */
    open fun createTransformer(cv: ClassVisitor): ClassVisitor = throw UnsupportedOperationException("$javaClass runner does not transform classes")

    /**
     * This method has to return `true` if code transformation is required for runner.
     * Returns `false` by default.
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
     * This method is invoked by a test thread
     * if an exception has been thrown.
     * @param iThread number of invoking thread
     */
    open fun onFailure(iThread: Int, e: Throwable) {}

    /**
     * This method is invoked by a test thread
     * if a coroutine was suspended
     * @param iThread number of invoking thread
     */
    open fun afterCoroutineSuspended(iThread: Int): Unit = throw UnsupportedOperationException("Coroutines are not supported")

    /**
     * This method is invoked by a test thread
     * if a coroutine was resumed
     * @param iThread number of invoking thread
     */
    open fun afterCoroutineResumed(iThread: Int): Unit = throw UnsupportedOperationException("Coroutines are not supported")

    /**
     * This method is invoked by a test thread
     * if a coroutine was cancelled
     * @param iThread number of invoking thread
     */
    open fun afterCoroutineCancelled(iThread: Int): Unit = throw UnsupportedOperationException("Coroutines are not supported")

    /**
     * Returns `true` if the coroutine corresponding to
     * the actor `iActor` in the thread `iThread` is resumed.
     */
    open fun isCoroutineResumed(iThread: Int, iActor: Int): Boolean = throw UnsupportedOperationException("Coroutines are not supported")

    /**
     * Is invoked before each actor execution in a thread.
     * Its invocations are inserted into generated code.
     */
    fun onActorStart(iThread: Int) {
        strategy.onActorStart(iThread)
    }

    /**
     * Closes used for this runner resources.
     */
    open fun close() {}

    /**
     * @return whether all scenario threads are completed or suspended
     * Used by generated code.
     */
    val isParallelExecutionCompleted: Boolean
        get() = completedOrSuspendedThreads.get() == scenario.threads
}
