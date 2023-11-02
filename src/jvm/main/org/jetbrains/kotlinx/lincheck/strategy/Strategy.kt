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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
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
    open fun needsTransformation() = false

    open fun createTransformer(cv: ClassVisitor): ClassVisitor {
        throw UnsupportedOperationException("$javaClass strategy does not transform classes")
    }

    internal fun run(verifier: Verifier, planner: InvocationsPlanner, tracker: RunTracker? = null): LincheckFailure? {
        var invocation = 0
        var spinning = false
        while (planner.shouldDoNextInvocation(invocation)) {
            if (!spinning && !nextInvocation()) {
                return null
            }
            spinning = false
            initializeInvocation()
            val failure = tracker.trackInvocation(invocation) {
                val result = runInvocation()
                spinning = (result is SpinCycleFoundAndReplayRequired)
                verify(result, verifier)
            }
            if (failure != null)
                return failure
            invocation++
        }
        return null
    }

    fun verify(result: InvocationResult, verifier: Verifier): LincheckFailure? = when (result) {
        is CompletedInvocationResult ->
            if (!verifier.verifyResults(scenario, result.results)) {
                IncorrectResultsFailure(scenario, result.results, result.tryCollectTrace())
            } else null

        is SpinCycleFoundAndReplayRequired -> null

        else -> result.toLincheckFailure(scenario, result.tryCollectTrace())
    }

    /**
     * Sets the internal state of strategy to run next invocation.
     *
     * @returns true if there is next invocation to run, false if all invocations have been studied.
     */
    open fun nextInvocation(): Boolean = true

    /**
     * Initializes the invocation.
     * Should be called before each call to [runInvocation].
     */
    open fun initializeInvocation() {}

    /**
     * Runs the current invocation and returns its result.
     * For deterministic strategies, consecutive calls to [runInvocation]
     * (without intervening [nextInvocation] calls) should run the same invocation, leading to the same results.
     *
     * Should be called after [initializeInvocation] and only if previous call to [nextInvocation] returned `true`.
     */
    abstract fun runInvocation(): InvocationResult

    open fun InvocationResult.tryCollectTrace(): Trace? = null

    override fun close() {}

    open fun beforePart(part: ExecutionPart) {}

    /**
     * Is invoked before each actor execution.
     */
    open fun onActorStart(iThread: Int) {}
}

