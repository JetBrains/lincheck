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

class Spinner(
    val nThreads: Int = -1,
    shouldYield: Boolean = true,
) {

    private var counter: Int = 0

    private val shouldSpin: Boolean = run {
        val nProcessors = Runtime.getRuntime().availableProcessors()
        (nProcessors >= nThreads)
    }

    private val spinLoopIterationsPerCall: Int =
        if (shouldSpin) SPIN_LOOP_ITERATIONS_PER_CALL else 1

    private val yieldLimit = when {
        shouldYield && shouldSpin -> SPIN_LOOP_ITERATIONS_BEFORE_YIELD
        shouldYield -> 1
        else -> -1
    }

    private inline val shouldYield: Boolean
        get() = yieldLimit > 0

    private val exitLimit = when {
        shouldSpin -> SPIN_LOOP_ITERATIONS_BEFORE_EXIT
        else -> 1
    }

    fun spin(): Boolean {
        // if exit limit is approached,
        // reset counter and signal to exit the spin-loop
        if (counter >= exitLimit) {
            counter = 0
            return false
        }
        // if yield limit is approached,
        // then yield and give other threads the possibility to work
        if (shouldYield && counter % yieldLimit == 0 && counter >= 0) {
            Thread.yield()
        }
        // spin a few iterations
        repeat(spinLoopIterationsPerCall) {
            counter++
        }
        return true
    }

    fun reset() {
        counter = 0
    }

}

@JvmInline
value class SpinnerGroup private constructor(private val spinners: Array<Spinner>) {

    constructor(nThreads: Int, shouldYield: Boolean = true)
            : this(Array(nThreads) { Spinner(nThreads, shouldYield = shouldYield) })

    operator fun get(i: Int): Spinner =
        spinners[i]
}

inline fun Spinner.wait(condition: () -> Boolean) {
    while (!condition()) {
        spin()
    }
    reset()
}

inline fun Spinner.boundedWait(condition: () -> Boolean): Boolean {
    var result = true
    while (!condition()) {
        if (spin()) continue
        result = condition()
        break
    }
    reset()
    return result
}

inline fun <T> Spinner.boundedWaitFor(getter: () -> T?): T? {
    boundedWait {
        val result = getter()
        if (result != null)
            return result
        false
    }
    return null
}

private const val SPIN_LOOP_ITERATIONS_PER_CALL : Int = 1 shl 5 // 32
private const val SPIN_LOOP_ITERATIONS_BEFORE_YIELD : Int = 1 shl 14 // 16,384
private const val SPIN_LOOP_ITERATIONS_BEFORE_EXIT : Int = 1 shl 20 // 1,048,576