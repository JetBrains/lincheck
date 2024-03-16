/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy

import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.Trace
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import org.objectweb.asm.ClassVisitor
import java.io.Closeable

/**
 * Implementation of this class describes how to run the generated execution.
 *
 * Note that strategy can run execution several times. For strategy creating
 * [.createStrategy] method is used. It is impossible to add a new strategy
 * without any code change.
 */
abstract class Strategy protected constructor(
    val scenario: ExecutionScenario
) : Closeable {
    /**
     * Determines if the strategy requires bytecode transformation.
     *
     * @return `true` if a transformation is needed, `false` otherwise.
     */
    open fun needsTransformation() = false

    /**
     * Creates a bytecode transformer required by the strategy..
     *
     * @param cv the [ClassVisitor] to create a transformer for.
     * @return a [ClassVisitor] representing the transformer.
     */
    open fun createTransformer(cv: ClassVisitor): ClassVisitor {
        throw UnsupportedOperationException("$javaClass strategy does not transform classes")
    }

    /**
     * Sets the internal state of strategy to run the next invocation.
     *
     * @return true if there is next invocation to run, false if all invocations have been studied.
     */
    open fun nextInvocation(): Boolean = true

    /**
     * Initializes the invocation.
     * Should be called before each call to [runInvocation].
     */
    open fun initializeInvocation() {}

    /**
     * Runs the current invocation and returns its result.
     *
     * Should be called after [initializeInvocation] and only if previous call to [nextInvocation] returned `true`:
     *
     * ```kotlin
     *  with(strategy) {
     *      if (nextInvocation()) {
     *          initializeInvocation()
     *          runInvocation()
     *      }
     *  }
     * ```
     *
     * For deterministic strategies, consecutive calls to [runInvocation]
     * (without intervening [nextInvocation] calls)
     * should run the same invocation, leading to the same results.
     *
     * @return the result of the invocation run.
     */
    abstract fun runInvocation(): InvocationResult

    /**
     * Tries to construct the trace leading to the given invocation result.
     *
     * @param result The invocation result.
     * @return The collected trace, or null if it was not possible to collect the trace.
     */
    open fun tryCollectTrace(result: InvocationResult): Trace? = null

    /**
     * This method is called before the execution of a specific scenario part.
     *
     * @param part The execution part that is about to be executed.
     */
    open fun beforePart(part: ExecutionPart) {}

    /**
     * This method is called before each actor execution.
     *
     * @param iThread the thread index on which the actor is starting
     */
    open fun onActorStart(iThread: Int) {}

    /**
     * Closes the strategy and releases any resources associated with it.
     */
    override fun close() {}
}

/**
 * Runs one Lincheck's test iteration with the given strategy and verifier.
 *
 * @param iteration the id of the iteration.
 * @param invocationsBound number of invocations to run.
 * @param verifier the verifier to be used.
 *
 * @return the failure, if detected, null otherwise.
 */
fun Strategy.runIteration(iteration: Int, invocationsBound: Int, verifier: Verifier): LincheckFailure? {
    var spinning = false
    for (invocation in 0 until invocationsBound) {
        if (!(spinning || nextInvocation()))
            return null
        spinning = false
        initializeInvocation()
        val failure = run {
            val result = runInvocation()
            spinning = (result is SpinCycleFoundAndReplayRequired)
            if (!spinning)
                verify(result, verifier)
            else null
        }
        if (failure != null)
            return failure
    }
    return null
}

/**
 * Verifies the results of the given invocation.
 * Attempts to collect the trace in case of incorrect results.
 *
 * @param result invocation result to verify.
 * @param verifier the verifier to be used.
 *
 * @return failure, if invocation results are incorrect, null otherwise.
 */
fun Strategy.verify(result: InvocationResult, verifier: Verifier): LincheckFailure? = when (result) {
    is CompletedInvocationResult ->
        if (!verifier.verifyResults(scenario, result.results)) {
            IncorrectResultsFailure(scenario, result.results, tryCollectTrace(result))
        } else null
    else ->
        result.toLincheckFailure(scenario, tryCollectTrace(result))
}