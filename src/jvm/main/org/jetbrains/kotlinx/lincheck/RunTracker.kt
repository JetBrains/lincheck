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
    fun runEnd(name: String, failure: LincheckFailure? = null, statistics: Statistics? = null) {}

    fun iterationStart(iteration: Int, scenario: ExecutionScenario) {}
    fun iterationEnd(iteration: Int, failure: LincheckFailure? = null) {}

    fun invocationStart(invocation: Int) {}
    fun invocationEnd(invocation: Int, failure: LincheckFailure? = null) {}
}

inline fun RunTracker?.trackRun(name: String, options: LincheckOptions,
                                block: () -> Pair<LincheckFailure?, Statistics>): LincheckFailure? {
    this?.runStart(name, options)
    try {
        val (failure, statistics) = block()
        this?.runEnd(name, failure, statistics)
        return failure
    } catch (exception: Throwable) {
        // TODO: once https://github.com/JetBrains/lincheck/issues/170 is implemented,
        //   we can put `check(false)` here instead
        this?.runEnd(name)
        throw exception
    }
}

inline fun RunTracker?.trackIteration(iteration: Int, scenario: ExecutionScenario, block: () -> LincheckFailure?): LincheckFailure? {
    this?.iterationStart(iteration, scenario)
    try {
        return block().also {
            this?.iterationEnd(iteration, failure = it)
        }
    } catch (exception: Throwable) {
        // TODO: once https://github.com/JetBrains/lincheck/issues/170 is implemented,
        //   we can put `check(false)` here instead
        this?.iterationEnd(iteration)
        throw exception
    }
}

inline fun RunTracker?.trackInvocation(invocation: Int, block: () -> LincheckFailure?): LincheckFailure? {
    this?.invocationStart(invocation)
    try {
        return block().also {
            this?.invocationEnd(invocation, failure = it)
        }
    } catch (exception: Throwable) {
        // TODO: once https://github.com/JetBrains/lincheck/issues/170 is implemented,
        //   we can put `check(false)` here instead
        this?.invocationEnd(invocation)
        throw exception
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

        override fun runEnd(name: String, failure: LincheckFailure?, statistics: Statistics?) {
            for (tracker in trackers) {
                tracker.runEnd(name, failure, statistics)
            }
        }

        override fun iterationStart(iteration: Int, scenario: ExecutionScenario) {
            for (tracker in trackers) {
                tracker.iterationStart(iteration, scenario)
            }
        }

        override fun iterationEnd(iteration: Int, failure: LincheckFailure?) {
            for (tracker in trackers) {
                tracker.iterationEnd(iteration, failure)
            }
        }

        override fun invocationStart(invocation: Int) {
            for (tracker in trackers) {
                tracker.invocationStart(invocation)
            }
        }

        override fun invocationEnd(invocation: Int, failure: LincheckFailure?) {
            for (tracker in trackers) {
                tracker.invocationEnd(invocation, failure)
            }
        }

    }