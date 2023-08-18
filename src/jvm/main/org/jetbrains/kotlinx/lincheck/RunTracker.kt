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

    fun iterationStart(iteration: Int, scenario: ExecutionScenario, mode: LincheckMode) {}

    fun iterationEnd(iteration: Int, failure: LincheckFailure? = null, exception: Throwable? = null) {}

    fun invocationStart(invocation: Int) {}

    fun invocationEnd(invocation: Int, failure: LincheckFailure? = null, exception: Throwable? = null) {}

    fun internalTrackers(): List<RunTracker> = listOf()

}

inline fun RunTracker?.trackIteration(
    iteration: Int,
    scenario: ExecutionScenario,
    mode: LincheckMode,
    block: () -> LincheckFailure?
): LincheckFailure? {
    var failure: LincheckFailure? = null
    var exception: Throwable? = null
    this?.iterationStart(iteration, scenario, mode)
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

inline fun RunTracker?.trackInvocation(
    invocation: Int,
    block: () -> LincheckFailure?
): LincheckFailure? {
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

fun List<RunTracker>.chainTrackers(): RunTracker? =
    if (this.isEmpty())
        null
    else object : RunTracker {

        val trackers = this@chainTrackers

        override fun iterationStart(iteration: Int, scenario: ExecutionScenario, mode: LincheckMode) {
            for (tracker in trackers) {
                tracker.iterationStart(iteration, scenario, mode)
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

        override fun internalTrackers(): List<RunTracker> {
            return trackers
        }

    }

inline fun<reified T : RunTracker> RunTracker.findTracker(): T? {
    if (this is T)
        return this
    val trackers = ArrayDeque<RunTracker>()
    trackers.addAll(internalTrackers())
    while (trackers.isNotEmpty()) {
        val tracker = trackers.removeFirst()
        if (tracker is T)
            return tracker
        trackers.addAll(tracker.internalTrackers())
    }
    return null
}
