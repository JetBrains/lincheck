/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

/**
 * A spinner implements utility functions for spinning in a loop.
 *
 * @property nThreads If passed, denotes the number of threads in a group that
 *   may wait for a common condition in the spin-loop.
 *   This information is used to check if the number of available CPUs is greater than
 *   the number of threads, and avoid spinning if that is not the case.
 *
 * @constructor Creates an instance of the [Spinner] class.
 */
internal class Spinner(val nThreads: Int = -1) {

    /**
     * Determines whether the spinner should actually spin in a loop,
     * or if it should exit immediately.
     *
     * The value is calculated based on the number of available processors
     * and the number of threads (if provided in the constructor).
     * If the number of processors is less than the number of threads,
     * then the spinner should exit the loop immediately.
     */
    val shouldSpin: Boolean = run {
        val nProcessors = Runtime.getRuntime().availableProcessors()
        (nProcessors >= nThreads)
    }

    /**
     * Waits in the spin-loop until the given condition is true
     * with periodical yielding to other threads.
     *
     * @param condition A lambda function that determines the condition to wait for.
     *   The function should return true when the condition is satisfied, and false otherwise.
     */
    inline fun spinWaitUntil(condition: () -> Boolean) {
        var counter = 0
        val yieldLimit = 1 + if (shouldSpin) SPIN_CYCLES_BOUND else 0
        while (!condition()) {
            Thread.onSpinWait()
            counter++
            if (counter % yieldLimit == 0) {
                Thread.yield()
            }
        }
    }

    /**
     * Waits in the spin-loop until the given condition is true.
     * Exits the spin-loop after a certain number of spin-loop iterations ---
     * typically, in this case, one may want to fall back into some blocking synchronization.
     *
     * @param condition A lambda function that determines the condition to wait for.
     *   The function should return true when the condition is satisfied, and false otherwise.
     *
     * @return `true` if the condition is met; `false` if the condition was not met and
     *   the spin-wait loop exited because the bound was reached.
     */
    inline fun Spinner.spinWaitBoundedUntil(condition: () -> Boolean): Boolean {
        var counter = 0
        val exitLimit = if (shouldSpin) SPIN_CYCLES_BOUND else 0
        var result = true
        while (!condition()) {
            if (counter == exitLimit) {
                result = condition()
                break
            }
            Thread.onSpinWait()
            counter++
        }
        return result
    }
}

/**
 * Waits for the result of the given [getter] function in the spin-loop until the result is not null.
 * Exits the spin-loop after a certain number of spin-loop iterations.
 *
 * @param getter A lambda function that returns the result to wait for.
 *
 * @return The result of waiting, or null if
 *   the spin-wait loop exited because the bound was reached.
 *
 * @see Spinner.spinWaitBoundedFor
 */
internal inline fun <T> Spinner.spinWaitBoundedFor(getter: () -> T?): T? {
    spinWaitBoundedUntil {
        val result = getter()
        if (result != null)
            return result
        false
    }
    return null
}

/**
 * A [SpinnerGroup] function creates a list of spinners to be used by the specified number of threads.
 * It provides a convenient way to manage multiple spinners together.
 *
 * @param nThreads The number of threads in the group.
 */
@Suppress("FunctionName")
internal fun SpinnerGroup(nThreads: Int): List<Spinner> {
    return Array(nThreads) { Spinner(nThreads) }.asList()
}


const val SPIN_CYCLES_BOUND: Int = 1_000_000