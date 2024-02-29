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

class Spinner(val nThreads: Int = -1, shouldYield: Boolean = true) {

    private var counter: Int = 0

    private inline val iterationsCount: Int
        get() = SPINNING_LOOP_ITERATIONS_PER_CALL

    private val yieldLimit = run {
        val nProcessors = Runtime.getRuntime().availableProcessors()
        if (nProcessors < nThreads) 1 else SPINNING_LOOP_ITERATIONS_BEFORE_YIELD
    }

    val isYielding: Boolean = shouldYield

    fun spin(): Boolean {
        if (counter >= yieldLimit) {
            if (isYielding)
                Thread.yield()
            counter = 0
            return false
        }
        repeat(iterationsCount) { counter++ }
        return true
    }

    fun reset() {
        counter = 0
    }

}

@JvmInline
value class SpinnerGroup private constructor(private val spinners: Array<Spinner>) {

    constructor(nThreads: Int) : this(spinners = Array(nThreads) { Spinner(nThreads) })

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
    while (!condition()) {
        if (!spin()) return condition()
    }
    reset()
    return false
}

private const val SPINNING_LOOP_ITERATIONS_PER_CALL : Int = 1 shl 5 // 32
private const val SPINNING_LOOP_ITERATIONS_BEFORE_YIELD : Int = 1 shl 14 // 16384