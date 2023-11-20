/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure

/**
 * The LincheckRunTracker interface defines methods for tracking the progress and results of a Lincheck test run.
 *
 * Each Lincheck test run consists of a number of iterations,
 * with each iteration corresponding to the specific scenario.
 * On each iteration, the same scenario can be invoked multiple times in an attempt to uncover a failure.
 * By overriding the methods of LincheckRunTracker interface,
 * it is possible to track the beginning and end of each iteration or invocation, as well as their results.
 */
interface LincheckRunTracker {

    /**
     * This method is called before the start of each iteration of the Lincheck test.
     *
     * @param iteration The iteration id.
     * @param scenario The execution scenario to be run.
     */
    fun iterationStart(iteration: Int, scenario: ExecutionScenario, parameters: IterationParameters) {}

    /**
     * This method is called at the end of each iteration of the Lincheck test.
     *
     * @param iteration The iteration id.
     * @param failure The failure that occurred during the iteration or null if no failure occurred.
     * @param exception The exception that occurred during the iteration or null if no exception occurred.
     */
    fun iterationEnd(iteration: Int, failure: LincheckFailure? = null, exception: Throwable? = null) {}

    /**
     * This method is called before the start of each invocation of the specific iteration.
     *
     * @param iteration The iteration id.
     * @param invocation The invocation number.
     */
    fun invocationStart(iteration: Int, invocation: Int) {}

    /**
     * This method is called at the end of each invocation of the specific iteration.
     *
     * @param iteration The iteration id.
     * @param invocation The invocation number.
     * @param failure the failure that occurred during the invocation or null if no failure occurred.
     * @param exception the exception that occurred during the invocation or null if no exception occurred.
     */
    fun invocationEnd(iteration: Int, invocation: Int, failure: LincheckFailure? = null, exception: Throwable? = null) {}

    /**
     * For a composite trackers, encompassing several internal sub-trackers,
     * this method should return a list of internal sub-trackers in the order
     * they are called by the parent tracker.
     *
     * @return a list of internal trackers.
     */
    fun internalTrackers(): List<LincheckRunTracker> = listOf()

}

/**
 * Represents the parameters for controlling the iteration of a Lincheck test run.
 *
 * @property invocationsBound The maximum number of invocations to be performed on the iteration.
 * @property warmUpInvocationsCount The number of warm-up invocations to be performed.
 */
data class IterationParameters(
    val strategy: LincheckStrategy,
    val invocationsBound: Int,
    val warmUpInvocationsCount: Int,
)

/**
 * Represents the testing strategies that can be used in Lincheck.
 */
enum class LincheckStrategy {
    Stress, ModelChecking
}

/**
 * Tracks the execution of a given Lincheck test iteration.
 *
 * @param iteration The iteration id.
 * @param scenario The execution scenario for the iteration.
 * @param block The code to be executed for the iteration.
 *
 * @return The failure, if any, that occurred during the execution of the iteration.
 */
inline fun LincheckRunTracker?.trackIteration(
    iteration: Int,
    scenario: ExecutionScenario,
    params: IterationParameters,
    block: () -> LincheckFailure?
): LincheckFailure? {
    var failure: LincheckFailure? = null
    var exception: Throwable? = null
    this?.iterationStart(iteration, scenario, params)
    try {
        return block().also {
            failure = it
        }
    } catch (throwable: Throwable) {
        exception = throwable
        throw throwable
    } finally {
        this?.iterationEnd(iteration, failure, exception)
    }
}


/**
 * Tracks the invocation of the specific Lincheck test iteration.
 *
 * @param iteration The iteration id.
 * @param invocation The current invocation within the iteration.
 * @param block The block of code to be executed.
 *
 * @return The failure, if any, that occurred during the execution of the invocation.
 */
inline fun LincheckRunTracker?.trackInvocation(
    iteration: Int,
    invocation: Int,
    block: () -> LincheckFailure?
): LincheckFailure? {
    var failure: LincheckFailure? = null
    var exception: Throwable? = null
    this?.invocationStart(iteration, invocation)
    try {
        return block().also {
            failure = it
        }
    } catch (throwable: Throwable) {
        exception = throwable
        throw throwable
    } finally {
        this?.invocationEnd(iteration, invocation, failure, exception)
    }
}


/**
 * Chains multiple Lincheck run trackers into a single tracker.
 * The chained tracker delegates method calls to each tracker in the chain.
 *
 * @return The chained LincheckRunTracker, or null if the original list is empty.
 */
fun List<LincheckRunTracker>.chainTrackers(): LincheckRunTracker? =
    if (this.isEmpty()) null else ChainedRunTracker(this)

internal class ChainedRunTracker(trackers: List<LincheckRunTracker> = listOf()) : LincheckRunTracker {

    private val trackers = mutableListOf<LincheckRunTracker>()

    init {
        this.trackers.addAll(trackers)
    }

    fun addTracker(tracker: LincheckRunTracker) {
        trackers.add(tracker)
    }

    override fun iterationStart(iteration: Int, scenario: ExecutionScenario, parameters: IterationParameters) {
        for (tracker in trackers) {
            tracker.iterationStart(iteration, scenario, parameters)
        }
    }

    override fun iterationEnd(iteration: Int, failure: LincheckFailure?, exception: Throwable?) {
        for (tracker in trackers) {
            tracker.iterationEnd(iteration, failure, exception)
        }
    }

    override fun invocationStart(iteration: Int, invocation: Int) {
        for (tracker in trackers) {
            tracker.invocationStart(iteration, invocation)
        }
    }

    override fun invocationEnd(iteration: Int, invocation: Int, failure: LincheckFailure?, exception: Throwable?) {
        for (tracker in trackers) {
            tracker.invocationEnd(iteration, invocation, failure, exception)
        }
    }

    override fun internalTrackers(): List<LincheckRunTracker> {
        return trackers
    }
}

/**
 * Searches for a first LincheckRunTracker of the specified type
 * among the internal sub-trackers of a given tracker.
 *
 * @param T The type of LincheckRunTracker to search for.
 *
 * @return The LincheckRunTracker of the specified type if found, otherwise null.
 */
inline fun<reified T : LincheckRunTracker> LincheckRunTracker.findTracker(): T? {
    if (this is T)
        return this
    val trackers = ArrayDeque<LincheckRunTracker>()
    trackers.addAll(internalTrackers())
    while (trackers.isNotEmpty()) {
        val tracker = trackers.removeFirst()
        if (tracker is T)
            return tracker
        trackers.addAll(tracker.internalTrackers())
    }
    return null
}

internal inline fun<reified T : LincheckRunTracker> ChainedRunTracker.addTrackerIfAbsent(createTracker: () -> T): T {
    val tracker = findTracker<T>()
    if (tracker != null)
        return tracker
    return createTracker().also {
        addTracker(it)
    }
}