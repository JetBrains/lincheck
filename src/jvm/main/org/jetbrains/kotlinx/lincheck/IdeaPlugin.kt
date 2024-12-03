@file:JvmName("IdeaPluginKt")
/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import sun.nio.ch.lincheck.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*

const val MINIMAL_PLUGIN_VERSION = "0.2"

// ============== This methods are used by debugger from IDEA plugin to communicate with Lincheck ============== //

/**
 * Invoked from the strategy [ModelCheckingStrategy] when Lincheck finds a bug.
 * The debugger creates a breakpoint on this method, so when it's called, the debugger receives all the information about the
 * failed test.
 * When a failure is found this method is called to provide all required information (trace points, failure type),
 * then [beforeEvent] method is called on each trace point.
 *
 * @param failureType string representation of the failure type.
 * (`INCORRECT_RESULTS`, `OBSTRUCTION_FREEDOM_VIOLATION`, `UNEXPECTED_EXCEPTION`, `VALIDATION_FAILURE`, `DEADLOCK` or `INTERNAL_BUG`).
 * @param trace failed test trace, where each trace point is represented as a string
 * (because it's the easiest way to provide some information to the debugger).
 * @param version current Lincheck version
 * @param minimalPluginVersion minimal compatible plugin version
 * @param exceptions representation of the exceptions with their stacktrace occurred during the execution
 */
@Suppress("UNUSED_PARAMETER")
fun testFailed(
    failureType: String,
    trace: Array<String>,
    version: String?,
    minimalPluginVersion: String,
    exceptions: Array<String>,
    threadNames: Array<String> = arrayOf(), // TODO maybe remove from plugin
) {}

/**
 * Debugger replaces the result of this method to `true` if idea plugin is enabled.
 */
fun ideaPluginEnabled(): Boolean {
    // treat as enabled in tests if we want so
    return eventIdStrictOrderingCheck
}

/**
 * This is a marker method for the plugin to detect the Lincheck test start.
 *
 * The plugin uses this method to disable breakpoints until a failure is found.
 */
fun lincheckVerificationStarted() {}

/**
 * If the debugger needs to replay the execution (due to earlier trace point selection), it replaces the result of this
 * method to `true`.
 */
fun shouldReplayInterleaving(): Boolean {
    return false // should be replaced with `true` to replay the failure
}

/**
 * The Debugger creates a breakpoint on this method call to know when the thread is switched.
 * The following "step over" call expects that the next suspension point is in the same thread.
 * So we have to track if a thread is changed by Lincheck to interrupt stepping,
 * otherwise the debugger skips all breakpoints in the thread desired by Lincheck.
 */
fun onThreadSwitchesOrActorFinishes() {}

// ======================================================================================================== //

/**
 * Internal property to check that trace point IDs are in a strict sequential order.
 */
internal val eventIdStrictOrderingCheck = System.getProperty("lincheck.debug.withEventIdSequentialCheck") != null

/**
 * This method is called from the debugger evaluation
 */
private fun visualize(): Array<Any>? {
    return try {
        val strategy = (Thread.currentThread() as? TestThread)?.eventTracker as ModelCheckingStrategy
        val runner = strategy.runner as ParallelThreadsRunner
        val testObject = runner.testInstance

        return createObjectToNumberMapAsArray(testObject)
    } catch (e: Throwable) {
        null
    }
}


/**
 * Creates an array [Object, objectNumber, Object, objectNumber, ...].
 * It represents a `Map<Any, Int>`, but due to difficulties with passing objects (Map)
 * to debugger, we represent it as an Array.
 *
 * The Debugger uses this information to enumerate objects.
 */
private fun createObjectToNumberMapAsArray(testObject: Any): Array<Any> {
    val resultArray = arrayListOf<Any>()

    val numbersMap = enumerateObjects(testObject)
    numbersMap.forEach { (any, objectNumber) ->
        resultArray.add(any)
        resultArray.add(objectNumber)
    }
    return resultArray.toTypedArray()
}

/**
 * Creates an array [Thread, threadId, Thread, threadId, ...].
 * It represents a `Map<Thread, ThreadId>`, but due to difficulties with passing objects (Map)
 * to debugger, we represent it as an Array.
 *
 * The Debugger uses this information to enumerate threads.
 */
private fun createThreadToLincheckThreadIdMap(threads: Array<TestThread>): Array<Any> {
    val array = arrayListOf<Any>()
    for (thread in threads) {
        array.add(thread)
        array.add(thread.threadId)
    }

    return array.toTypedArray()
}

/**
 * Creates an array [CancellableContinuation, threadId, CancellableContinuation, threadId, ...].
 * It represents a `Map<CancellableContinuation, ThreadId>`, but due to difficulties with passing objects (Map)
 * to debugger, we represent it as an Array.
 *
 * The Debugger uses this information to enumerate continuations.
 */
private fun createContinuationToThreadIdMap(threads: Array<TestThread>): Array<Any> {
    val array = arrayListOf<Any>()
    for (thread in threads) {
        array.add(thread.suspendedContinuation ?: continue)
        array.add(thread.threadId)
    }

    return array.toTypedArray()
}