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

interface RunTracker {
    fun runStart(name: String, options: LincheckOptions) {}

    fun runEnd(
        name: String,
        failure: LincheckFailure? = null,
        exception: Throwable? = null,
        statistics: Statistics? = null
    ) {}

    fun iterationStart(iteration: Int, scenario: ExecutionScenario) {}

    fun iterationEnd(
        iteration: Int,
        failure: LincheckFailure? = null,
        exception: Throwable? = null,
    ) {}

    fun invocationStart(invocation: Int) {}

    fun invocationEnd(
        invocation: Int,
        failure: LincheckFailure? = null,
        exception: Throwable? = null,
    ) {}

}

inline fun RunTracker?.trackRun(
    name: String,
    options: LincheckOptions,
    block: () -> Pair<LincheckFailure?, Statistics>
): Pair<LincheckFailure?, Statistics> {
    var failure: LincheckFailure? = null
    var exception: Throwable? = null
    var statistics: Statistics? = null
    this?.runStart(name, options)
    try {
        return block().also {
            failure = it.first
            statistics = it.second
        }
    } catch (throwable: Throwable) {
        exception = throwable
        throw throwable
    } finally {
        this?.runEnd(name, failure, exception, statistics)
    }
}

inline fun RunTracker?.trackIteration(iteration: Int, scenario: ExecutionScenario, block: () -> LincheckFailure?): LincheckFailure? {
    var failure: LincheckFailure? = null
    var exception: Throwable? = null
    this?.iterationStart(iteration, scenario)
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

inline fun RunTracker?.trackInvocation(invocation: Int, block: () -> LincheckFailure?): LincheckFailure? {
    var failure: LincheckFailure? = null
    var exception: Throwable? = null
    this?.invocationStart(invocation)
    try {
        return block().also {
            failure = it
        }
    } catch (throwable: Throwable) {
        exception = throwable
        throw throwable
    } finally {
        this?.invocationEnd(invocation, failure, exception)
    }
}

fun trackersList(trackers: List<RunTracker>): RunTracker? =
    if (trackers.isEmpty())
        null
    else object : RunTracker {

        override fun runStart(name: String, options: LincheckOptions) {
            for (tracker in trackers) {
                tracker.runStart(name, options)
            }
        }

        override fun runEnd(name: String, failure: LincheckFailure?, exception: Throwable?, statistics: Statistics?) {
            for (tracker in trackers) {
                tracker.runEnd(name, failure, exception, statistics)
            }
        }

        override fun iterationStart(iteration: Int, scenario: ExecutionScenario) {
            for (tracker in trackers) {
                tracker.iterationStart(iteration, scenario)
            }
        }

        override fun iterationEnd(iteration: Int, failure: LincheckFailure?, exception: Throwable?) {
            for (tracker in trackers) {
                tracker.iterationEnd(iteration, failure, exception)
            }
        }

        override fun invocationStart(invocation: Int) {
            for (tracker in trackers) {
                tracker.invocationStart(invocation)
            }
        }

        override fun invocationEnd(invocation: Int, failure: LincheckFailure?, exception: Throwable?) {
            for (tracker in trackers) {
                tracker.invocationEnd(invocation, failure, exception)
            }
        }

    }