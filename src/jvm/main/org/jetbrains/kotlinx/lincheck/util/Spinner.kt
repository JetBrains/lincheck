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
 * A spinner implements spinning in a loop.
 *
 * This class provides a method [spin], that should be called inside a spin-loop.
 * For example, a simple spin-lock class can be implemented with the help of the [Spinner] as follows:
 *
 * ```
 *  class SpinLock {
 *      private val lock = AtomicBoolean()
 *      private val spinner = Spinner()
 *
 *      fun lock() {
 *          while (!lock.compareAndSet(false, true)) {
*               spinner.spin()
*           }
 *      }
 *
 *      fun unlock() {
 *          lock.set(false)
 *      }
 *  }
 * ```
 *
 * @property nThreads If passed, denotes the number of threads in a group that
 *   may wait for a common condition in the spin-loop.
 *   This information is used to check if the number of available CPUs is greater than
 *   the number of threads, and avoid spinning if that is not the case.
 *
 * @constructor Creates an instance of the [Spinner] class.
 */
class Spinner(val nThreads: Int = -1) {

    /**
     * Determines whether the spinner should actually spin in a loop,
     * or if it should exit immediately.
     *
     * The value is calculated based on the number of available processors
     * and the number of threads (if provided in the constructor).
     * If the number of processors is less than the number of threads,
     * then the spinner should exit the loop immediately.
     */
    private val shouldSpin: Boolean = run {
        val nProcessors = Runtime.getRuntime().availableProcessors()
        (nProcessors >= nThreads)
    }

    /**
     * The number of spin-loop iterations before yielding the current thread
     * to give other threads the opportunity to run.
     */
    val yieldLimit: Int
        get() = if (shouldSpin) SPIN_CALLS_BEFORE_YIELD else 1

    /**
     * The exit limit determines the number of spin-loop iterations
     * after which the spin-loop is advised to exit.
     */
    val exitLimit: Int
        get() = if (shouldSpin) SPIN_CALLS_BEFORE_EXIT else 1

    /**
     * Spins the counter for a few iterations.
     */
    fun spin() {
        if (!shouldSpin) return
        Thread.onSpinWait()
        spinWait()
    }

    /**
     * Auxiliary variable storing a pseudo-random value,
     * which is used inside the [spinWait] to perform spin waiting.
     */
    private var sink: Long = System.nanoTime()

    /**
     * Implements a spin waiting procedure.
     */
    private fun spinWait() {
        // Initialize with a pseudo-random number to prevent optimizations.
        var x = sink
        // We want to perform few spins while avoiding accesses to the shared memory.
        // To achieve this, we do some arithmetic operations on a local variable
        // and try to obfuscate the loop body so that the compiler
        // would not be able to optimize it out.
        for (i in SPIN_LOOP_ITERATIONS_PER_CALL downTo 1) {
            x += (31 * x + 0xBEEF + i) and (0xFFFFFFFFFFFFFFFL)
        }
        // This if statement ensures that the result of the computation
        // will have a visible side effect and thus will not be optimized,
        // but at the same time it avoids the shared memory store on a hot-path.
        if (x == 0xDEADL) {
            sink += x
        }
    }

}

/**
 * A [SpinnerGroup] function creates a list of spinners to be used by the specified number of threads.
 * It provides a convenient way to manage multiple spinners together.
 *
 * @param nThreads The number of threads in the group.
 */
fun SpinnerGroup(nThreads: Int): List<Spinner> {
    return Array(nThreads) { Spinner(nThreads) }.asList()
}

/**
 * Waits in the spin-loop until the given condition is true
 * with periodical yielding to other threads.
 *
 * For example, the spin-lock's `lock` method can be implemented with
 * the help of the [spinWaitUntil] as follows:
 *
 * ```
 *  fun lock() {
 *      spinner.spinWaitUntil { lock.compareAndSet(false, true) }
 *  }
 * ```
 *
 * @param condition A lambda function that determines the condition to wait for.
 *   The function should return true when the condition is satisfied, and false otherwise.
 *
 * @see Spinner
 */
inline fun Spinner.spinWaitUntil(condition: () -> Boolean) {
    var counter = 0
    while (!condition()) {
        spin()
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
 * For example, a simple spin-lock that fall-backs into thread parking can be implemented with
 * the help of the [spinWaitBoundedUntil] as follows:
 *
 * ```
 *  class SimpleQueuedLock {
 *      private val lock = AtomicBoolean()
 *      private val queue = ConcurrentLinkedQueue<Thread>()
 *      private val spinner = Spinner()
 *
 *      fun lock() {
 *          while (true) {
 *              val locked = spinner.spinWaitBoundedUntil {
 *                  lock.compareAndSet(false, true)
 *              }
 *              if (locked) return
 *              val thread = Thread.currentThread()
 *              do {
 *                  if (!queue.contains(thread)
 *                      queue.add(thread)
 *                  LockSupport.park()
 *              } while (lock.get())
 *          }
 *      }
 *
 *      fun unlock() {
 *          lock.set(false)
 *          queue.poll()?.also {
 *              LockSupport.unpark(it)
 *          }
 *      }
 *  }
 * ```
 *
 * @param condition A lambda function that determines the condition to wait for.
 *   The function should return true when the condition is satisfied, and false otherwise.
 *
 * @return `true` if the condition is met; `false` if the condition was not met and
 *   the spin-wait loop exited because the bound was reached.
 *
 * @see Spinner
 */
inline fun Spinner.spinWaitBoundedUntil(condition: () -> Boolean): Boolean {
    var result = true
    var counter = 0
    while (!condition()) {
        spin()
        counter++
        if (counter % exitLimit == 0) {
            result = condition()
            break
        }
    }
    return result
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
inline fun <T> Spinner.spinWaitBoundedFor(getter: () -> T?): T? {
    spinWaitBoundedUntil {
        val result = getter()
        if (result != null)
            return result
        false
    }
    return null
}


private const val SPIN_LOOP_ITERATIONS_PER_CALL : Int = 32

const val SPIN_CALLS_BEFORE_YIELD : Int = 10_000
const val SPIN_CALLS_BEFORE_EXIT  : Int = 10_000