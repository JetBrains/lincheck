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
     * Runner used for executing the test scenario.
     */
    internal abstract val runner: Runner

    /**
     * Sets the internal state of strategy to run the next invocation.
     *
     * @return true if there is next invocation to run, false if all invocations have been studied.
     */
    open fun nextInvocation(): Boolean = true

    /**
     * Runs the current invocation and returns its result.
     *
     * Should be called only if previous call to [nextInvocation] returned `true`:
     *
     * ```kotlin
     *  with(strategy) {
     *      if (nextInvocation()) {
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
     * This method is called after the execution of a specific part of the scenario.
     *
     * @param part The execution part that has been executed.
     */
    open fun afterPart(part: ExecutionPart) {}

    /**
     * Is invoked before each actor execution.
     */
    open fun onActorStart(iThread: Int) {}

    /**
     * Is invoked after each actor execution, even if a legal exception was thrown
     */
    open fun onActorFinish() {}

    /**
     * Closes the strategy and releases any resources associated with it.
     */
    override fun close() {
        runner.close()
    }
}

/**
 * Runs one Lincheck's test iteration with the given strategy and verifier.
 *
 * @param invocations number of invocations to run.
 * @param verifier the verifier to be used.
 *
 * @return the failure, if detected, null otherwise.
 */
fun Strategy.runIteration(invocations: Int, verifier: Verifier): LincheckFailure? {
    for (invocation in 0 until invocations) {
        if (!nextInvocation())
            return null
        val result = runInvocation()
        val failure = verify(result, verifier)
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