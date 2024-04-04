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
 * A spinner implements spinning in a loop with optional yielding to other threads.
 *
 * This class provides a method [spin], that should be called inside a spin-loop.
 * This method performs a few spin-loop iterations and optionally periodically yields.
 *
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
 * The `lock` method can be shortened with the help of the [spinWaitUntil] extension method:
 *
 * ```
 *  fun lock() {
 *      spinner.spinWaitUntil { lock.compareAndSet(false, true) }
 *  }
 * ```
 *
 * Sometimes, it is useful to fall back into a blocking synchronization if the spin-loop spins for too long.
 * For this purpose, the [spin] method has a boolean return value.
 * It returns `true` if the spinning should be continued,
 * or `false` if it is advised to exit the spin-loop.
 *
 * ```
 *  class SimpleQueuedLock {
 *      private val lock = AtomicBoolean()
 *      private val queue = ConcurrentLinkedQueue<Thread>()
 *      private val spinner = Spinner()
 *
 *      fun lock() {
 *          while (!lock.compareAndSet(false, true)) {
 *              if (spinner.spin())
 *                  continue
 *              val thread = Thread.currentThread()
 *              if (!queue.contains(thread))
 *                  queue.add(thread)
 *              LockSupport.park()
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
 * @property nThreads If passed, denotes the number of threads in a group that
 *   may wait for a common condition in the spin-loop.
 *
 * @constructor Creates an instance of the [Spinner] class.
 */
class Spinner(val nThreads: Int = -1) {

    /**
     * Counter of performed spin-loop iterations.
     */
    private var counter: Int = 0

    /**
     * Counter of performed yield calls.
     */
    private var yieldCounter: Int = 0

    /**
     * Number of the current backoff step.
     * It is used to implement the exponential backoff strategy:
     * each subsequent call to the [spin] performs exponentially more
     * iterations of the spin-wait loop.
     */
    private var backoffStep: Int = 0

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
     * The number of spin-loop iterations to be performed per call to [spin].
     */
    private val spinLoopIterations: Int
        get() = (1 shl backoffStep)

    /**
     * The number of spin-loop iterations before yielding the current thread
     * to give other threads the opportunity to run.
     */
    private val yieldLimit: Int
        get() = (1 shl SPIN_LOOP_ITERATIONS_BEFORE_YIELD)

    /**
     * The exit limit determines the number of spin-loop iterations
     * after which the spin-loop is advised to exit.
     */
    private val exitLimit: Int
        get() = (1 shl SPIN_LOOP_ITERATIONS_BEFORE_EXIT)

    /**
     * Spins the counter for a few iterations.
     * In addition, in the case of a yielding spinner, it yields occasionally
     * to give other threads the opportunity to run.
     *
     * @return `true` if the spin-loop should continue;
     *   `false` if the spin-loop is advised to exit,
     *   for example, to fall back into a blocking synchronization.
     */
    fun spin(): Boolean {
        // yield and exit early if we should not spin
        if (!shouldSpin) {
            Thread.yield()
            return false
        }
        // perform spin waiting
        Thread.onSpinWait()
        spinWait()
        // update the counters
        counter += spinLoopIterations
        backoffStep = (backoffStep + 1).coerceAtMost(SPIN_LOOP_ITERATIONS_PER_CALL)
        // if yield limit is approached,
        // then yield and give other threads the opportunity to run
        if (counter >= (1 + yieldCounter) * yieldLimit) {
            yieldCounter += 1
            Thread.yield()
        }
        // if exit limit is approached,
        // reset counter and signal to exit the spin-loop
        if (counter >= exitLimit) {
            reset()
            return false
        }
        return true
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
        for (i in spinLoopIterations downTo 1) {
            x += (31 * x + 0xBEEF + i) and (0xFFFFFFFFFFFFFFFL)
        }
        // This if statement ensures that the result of the computation
        // will have a visible side effect and thus will not be optimized,
        // but at the same time it avoids the shared memory store on a hot-path.
        if (x == 0xDEADL) {
            sink += x
        }
    }

    /**
     * Resets the state of the spinner.
     */
    fun reset() {
        counter = 0
        yieldCounter = 0
        backoffStep = 0
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
 * Waits in the spin-loop until the given condition is true.
 *
 * @param condition a lambda function that determines the condition to wait for.
 *   The function should return true when the condition is satisfied, and false otherwise.
 *
 * @see Spinner
 */
inline fun Spinner.spinWaitUntil(condition: () -> Boolean) {
    while (!condition()) {
        spin()
    }
    reset()
}

/**
 * Waits in the spin-loop until the given condition is true.
 * Exits the spin-loop after a certain number of spin-loop iterations.
 *
 * @param condition a lambda function that determines the condition to wait for.
 *   The function should return true when the condition is satisfied, and false otherwise.

 * @return `true` if the condition is met; `false` if the condition is not met.
 *
 * @see Spinner
 */
inline fun Spinner.spinWaitBoundedUntil(condition: () -> Boolean): Boolean {
    var result = true
    while (!condition()) {
        if (spin()) continue
        result = condition()
        break
    }
    reset()
    return result
}

/**
 * Waits for the result of the given [getter] function in the spin-loop until the result is not null.
 * Exits the spin-loop after a certain number of spin-loop iterations.
 *
 * @param getter a lambda function that returns the result to wait for.
 *
 * @return the result of waiting.
 *
 * @see Spinner
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

/* NOTE:
 * spin limit constants should be powers of 2 to play nicely with
 * the exponential backoff strategy with the exponent base being 2;
 * here we define them via their exponent number.
 */

private const val SPIN_LOOP_ITERATIONS_PER_CALL     : Int = 6   // 2^6  = 64
private const val SPIN_LOOP_ITERATIONS_BEFORE_YIELD : Int = 20  // 2^20 = 1,048,576
private const val SPIN_LOOP_ITERATIONS_BEFORE_EXIT  : Int = 26  // 2^26 = 67,108,864