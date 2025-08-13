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
 */
class Spinner private constructor(
    private val threadCount: Int,
    private val threadCounter: (() -> Int)?,
) {

    /**
     * Creates an instance of the [Spinner] class.
     */
    constructor() : this(threadCount = -1, threadCounter = null)

    /**
     * Creates an instance of the [Spinner] class.
     *
     * @param threadCount Denotes the number of threads in a group that
     *   may wait for a common condition in the spin-loop.
     *   This information is used to check if the number of available CPUs is greater than
     *   the number of threads and avoid spinning if that is not the case.
     */
    constructor(threadCount: Int) : this(threadCount, threadCounter = null)

    /**
     * Creates an instance of the [Spinner] class.
     *
     * @param threadCounter Denotes the number of threads in a group that
     *   may wait for a common condition in the spin-loop.
     *   The number of threads in a group is allowed to change dynamically ---
     *   the spinner queries this number on each spin iteration, avoiding the spinning if necessary.
     *   This information is used to check if the number of available CPUs is greater than
     *   the number of threads in the group and avoid spinning if that is not the case.
     */
    constructor(threadCounter: () -> Int) : this(threadCount = -1, threadCounter = threadCounter)

    /**
     * Determines whether the spinner should actually spin in a loop or if it should exit immediately.
     *
     * The value is calculated based on the number of available processors
     * and the number of threads (if provided in the constructor).
     * If the number of processors is lower than the number of threads in the group,
     * then the spinner should exit the loop immediately.
     */
    fun isSpinning(): Boolean {
        val nThreads = threadCounter?.invoke() ?: threadCount
        val nProcessors = Runtime.getRuntime().availableProcessors()
        return (nProcessors > 1) && (nProcessors >= nThreads)
    }

    /**
     * Determines the limit for the number of iterations
     * the spin-loop should perform before yielding to other threads.
     */
    fun pollYieldLimit(): Int =
        if (isSpinning()) YIELD_LIMIT else 1

    /**
     * Defines the limit for iterations in a spin-loop before it exits.
     */
    fun pollExitLimit(): Int =
        if (isSpinning()) EXIT_LIMIT else 0

    /**
     * Calculates the elapsed time in nanoseconds since the provided start time.
     *
     * @param startTimeNano The starting time in nanoseconds.
     * @return The elapsed time in nanoseconds.
     */
    fun pollElapsedTime(startTimeNano: Long): Long =
        (System.nanoTime() - startTimeNano)

    /**
     * Waits in the spin-loop until the given condition is true
     * with periodical yielding to other threads.
     *
     * @param condition A lambda function that determines the condition to wait for.
     *   The function should return true when the condition is satisfied, and false otherwise.
     */
    inline fun spinWaitUntil(condition: () -> Boolean) {
        var counter = 0
        var yieldLimit = pollYieldLimit()
        while (!condition()) {
            counter++
            if (counter % yieldLimit == 0) {
                Thread.yield()
            }
            if (counter % POLL_COUNT == 0) {
                yieldLimit = pollYieldLimit()
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
        var result = true
        var exitLimit = pollExitLimit()
        while (!condition()) {
            if (counter >= exitLimit) {
                result = condition()
                break
            }
            counter++
            if (counter % POLL_COUNT == 0) {
                exitLimit = pollExitLimit()
            }
        }
        return result
    }

    /**
     * Waits in a spin-loop until the specified condition is met or the timeout is reached.
     *
     * @param timeoutNano The maximum time to wait in nanoseconds.
     * @param condition A lambda function that determines the condition to wait for.
     *                  The function should return true when the condition is satisfied, and false otherwise.
     * @return The elapsed time in nanoseconds if the condition is met before the timeout;
     *   -1 if the timeout is reached.
     */
    inline fun spinWaitTimedUntil(timeoutNano: Long, condition: () -> Boolean): Long {
        var counter = 0
        val startTime = System.nanoTime()
        var elapsedTime = 0L
        while (!condition()) {
            if (elapsedTime >= timeoutNano) {
                return -1
            }
            counter++
            if (counter % POLL_COUNT == 0) {
                elapsedTime = pollElapsedTime(startTime)
            }
        }
        return pollElapsedTime(startTime)
    }

    companion object {
        const val POLL_COUNT        = 64            // 2^6
        const val YIELD_LIMIT       = 4096          // 2^12
        const val EXIT_LIMIT        = 1024 * 1024   // 2^20
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