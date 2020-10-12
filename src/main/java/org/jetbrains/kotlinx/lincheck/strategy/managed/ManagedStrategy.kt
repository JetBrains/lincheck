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
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyTransformer.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.*
import java.lang.reflect.Method
import java.util.*

/**
 * This is an abstract class for all managed strategies.
 * This abstraction helps to choose a proper [Runner],
 * to transform byte-code in order to insert required for managing instructions,
 * and to hide class loading problems from the strategy algorithm.
 */
abstract class ManagedStrategy(testClass: Class<*>, scenario: ExecutionScenario, validationFunctions: List<Method>,
                               stateRepresentation: Method?, private val guarantees: List<ManagedStrategyGuarantee>,
                               timeoutMs: Long, private val eliminateLocalObjects: Boolean) : Strategy(scenario) {
    /**
     * Number of threads
     */
    protected val nThreads: Int = scenario.parallelExecution.size
    protected val runner: Runner
    private val shouldMakeStateRepresentation: Boolean = stateRepresentation != null
    protected var loggingEnabled = false
    private val codeLocationConstructors: MutableList<(() -> CodePoint)?> = ArrayList() // for trace construction
    protected val codePoints: MutableList<CodePoint> = ArrayList()

    init {
        runner = ManagedStrategyRunner(this, testClass, validationFunctions, stateRepresentation, timeoutMs, UseClocks.ALWAYS)
        // Managed state should be initialized before test class transformation
        initializeManagedState()
        runner.transformTestClass()
    }

    override fun createTransformer(cv: ClassVisitor?): ClassVisitor
        = ManagedStrategyTransformer(
            cv,
            codeLocationConstructors,
            guarantees,
            shouldMakeStateRepresentation,
            eliminateLocalObjects,
            loggingEnabled
        )

    override fun createRemapper(): Remapper? = JavaUtilRemapper()

    override fun needsTransformation(): Boolean = true

    override fun run(): LincheckFailure?
        = try {
            runImpl()
        } finally {
            runner.close()
        }

    /**
     * This method implements the strategy logic
     */
    protected abstract fun runImpl(): LincheckFailure?

    // == LISTENING EVENTS ==

    /**
     * This method is executed as the first thread action.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     */
    open fun onStart(iThread: Int) {}

    /**
     * This method is executed as the last thread action if no exception has been thrown.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     */
    open fun onFinish(iThread: Int) {}

    /**
     * This method is executed if an exception has been thrown.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     */
    open fun onFailure(iThread: Int, e: Throwable) {}

    /**
     * This method is executed before a shared variable read operation.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    open fun beforeSharedVariableRead(iThread: Int, codeLocation: Int) {}

    /**
     * This method is executed before a shared variable write operation.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    open fun beforeSharedVariableWrite(iThread: Int, codeLocation: Int) {}

    /**
     * This method is executed before an atomic method call.
     * Atomic method is a method that is marked by ManagedGuarantee to be treated as atomic.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    open fun beforeAtomicMethodCall(iThread: Int, codeLocation: Int) {}

    /**
     *
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @return whether lock should be actually acquired
     */
    open fun beforeLockAcquire(iThread: Int, codeLocation: Int, monitor: Any): Boolean = true

    /**
     *
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @return whether lock should be actually released
     */
    open fun beforeLockRelease(iThread: Int, codeLocation: Int, monitor: Any): Boolean = true

    /**
     *
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @param withTimeout `true` if is invoked with timeout, `false` otherwise.
     * @return whether park should be executed
     */
    open fun beforePark(iThread: Int, codeLocation: Int, withTimeout: Boolean): Boolean = true

    /**
     *
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    open fun afterUnpark(iThread: Int, codeLocation: Int, thread: Any) {}

    /**
     *
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @param withTimeout `true` if is invoked with timeout, `false` otherwise.
     * @return whether wait should be executed
     */
    open fun beforeWait(iThread: Int, codeLocation: Int, monitor: Any, withTimeout: Boolean): Boolean = true

    /**
     *
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    open fun afterNotify(iThread: Int, codeLocation: Int, monitor: Any, notifyAll: Boolean) {}

    /**
     * This method is invoked by a test thread
     * if a coroutine was suspended
     * @param iThread number of invoking thread
     */
    open fun afterCoroutineSuspended(iThread: Int) {}

    /**
     * This method is invoked by a test thread
     * if a coroutine was resumed
     * @param iThread number of invoking thread
     */
    open fun afterCoroutineResumed(iThread: Int) {}

    /**
     * This method is invoked by a test thread
     * if a coroutine was cancelled
     * @param iThread number of invoking thread
     */
    open fun afterCoroutineCancelled(iThread: Int) {}

    /**
     * This method is invoked by a test thread
     * before each ignored section start.
     * These sections are determined by Strategy.ignoredEntryPoints()
     * @param iThread number of invoking thread
     */
    open fun enterIgnoredSection(iThread: Int) {}

    /**
     * This method is invoked by a test thread
     * after each ignored section end.
     * @param iThread number of invoking thread
     */
    open fun leaveIgnoredSection(iThread: Int) {}

    /**
     * This method is invoked by a test thread
     * before each method invocation.
     * @param codeLocation the byte-code location identifier of this invocation
     * @param iThread number of invoking thread
     */
    open fun beforeMethodCall(iThread: Int, codeLocation: Int) {}

    /**
     * This method is invoked by a test thread
     * after each method invocation.
     * @param iThread number of invoking thread
     * @param codePoint the identifier of the call code point for the invocation
     */
    open fun afterMethodCall(iThread: Int, codePoint: Int) {}

    /**
     * This method is invoked by a test thread
     * after each write or atomic method invocation
     */
    open fun makeStateRepresentation(iThread: Int) {}

    // == LOGGING METHODS ==

    /**
     * Returns a [CodePoint] which describes the specified visit to a code location
     * @param codePoint code location identifier which is inserted by transformer
     */
    fun getCodePoint(codePoint: Int): CodePoint = codePoints[codePoint]

    /**
     * Creates a new [CodePoint].
     * The type of the created code location is defined by the used constructor.
     * @param constructorId which constructor to use for createing code location
     * @return index of the created code location
     */
    fun createCodePoint(constructorId: Int): Int {
        codePoints.add(codeLocationConstructors[constructorId]!!.invoke())
        return codePoints.size - 1
    }

    // == UTILITY METHODS ==

    /**
     * This method is invoked by transformed via [ManagedStrategyTransformer] code,
     * it helps to determine the number of thread we are executing on.
     *
     * @return the number of the current thread according to the [execution scenario][ExecutionScenario].
     */
    fun currentThreadNumber(): Int {
        val t = Thread.currentThread()
        return if (t is FixedActiveThreadsExecutor.TestThread) {
            t.iThread
        } else {
            nThreads
        }
    }

    protected fun initializeManagedState() {
        ManagedStateHolder.setState(runner.classLoader, this)
    }
}

/**
 * This class is a [ParallelThreadsRunner] with some overrides that add callbacks
 * to strategy so that strategy can learn about execution events.
 */
private class ManagedStrategyRunner(private val managedStrategy: ManagedStrategy, testClass: Class<*>, validationFunctions: List<Method>,
                                    stateRepresentationMethod: Method?, timeoutMs: Long, useClocks: UseClocks
) : ParallelThreadsRunner(managedStrategy, testClass, validationFunctions, stateRepresentationMethod, timeoutMs, useClocks) {
    override fun onStart(iThread: Int) {
        super.onStart(iThread)
        managedStrategy.onStart(iThread)
    }

    override fun onFinish(iThread: Int) {
        managedStrategy.onFinish(iThread)
        super.onFinish(iThread)
    }

    override fun onFailure(iThread: Int, e: Throwable) {
        managedStrategy.onFailure(iThread, e)
        super.onFailure(iThread, e)
    }

    public override fun afterCoroutineSuspended(iThread: Int) {
        super.afterCoroutineSuspended(iThread)
        managedStrategy.afterCoroutineSuspended(iThread)
    }

    public override fun afterCoroutineResumed(iThread: Int) {
        super.afterCoroutineResumed(iThread)
        managedStrategy.afterCoroutineResumed(iThread)
    }

    public override fun afterCoroutineCancelled(iThread: Int) {
        super.afterCoroutineCancelled(iThread)
        managedStrategy.afterCoroutineCancelled(iThread)
    }
}
