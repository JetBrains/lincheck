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
 * (`INCORRECT_RESULTS`, `OBSTRUCTION_FREEDOM_VIOLATION`, `UNEXPECTED_EXCEPTION`, `VALIDATION_FAILURE`, `DEADLOCK`).
 * @param trace failed test trace, where each trace point is represented as a string
 * (because it's the easiest way to provide some information to the debugger).
 * @param version current Lincheck version
 * @param minimalPluginVersion minimal compatible plugin version
 */
@Suppress("UNUSED_PARAMETER")
fun testFailed(
    failureType: String,
    trace: Array<String>,
    version: String?,
    minimalPluginVersion: String
) {
}

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
 * This method is called on every trace point shown to the user,
 * but before the actual event, such as the read/write/MONITORENTER/MONITOREXIT/, etc.
 * The Debugger creates a breakpoint inside this method and if [eventId] is the selected one, the breakpoint is triggered.
 * Then the debugger performs step-out action, so we appear in the user's code.
 * That's why this method **must** be called from a user-code, not from a nested function.
 *
 * @param eventId id of this trace point. Consistent with `trace`, provided in [testFailed] method.
 * @param type type of this event, just for debugging.
 */
@Suppress("UNUSED_PARAMETER")
fun beforeEvent(eventId: Int, type: String) {
    val strategy = (Thread.currentThread() as? TestThread)?.eventTracker ?: return
    visualize(strategy)
}


/**
 * This method receives all information about the test object instance to visualize.
 * The Debugger creates a breakpoint inside this method and uses this method parameters to create the diagram.
 *
 * We pass Maps as Arrays due to difficulties with passing objects (java.util.Map) to the debugger
 * (class version, etc.).
 *
 * @param testInstance tested data structure.
 * @param numbersArrayMap an array structured like [Object, objectNumber, Object, objectNumber, ...]. Represents a `Map<Any, Int>`.
 * @param threadsArrayMap an array structured like [Thread, threadId, Thread, threadId, ...]. Represents a `Map<Any, Int>`.
 * @param threadToLincheckThreadIdMap an array structured like [CancellableContinuation, threadId, CancellableContinuation, threadId, ...]. Represents a `Map<Any, Int>`.
 */
@Suppress("UNUSED_PARAMETER")
fun visualizeInstance(
    testInstance: Any,
    numbersArrayMap: Array<Any>,
    threadsArrayMap: Array<Any>,
    threadToLincheckThreadIdMap: Array<Any>
) {
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

private fun visualize(strategyObject: Any) = runCatching {
    val strategy = strategyObject as ModelCheckingStrategy
    val runner = strategy.runner as ParallelThreadsRunner
    val testObject = runner.testInstance
    val threads = runner.executor.threads

    val objectToNumberMap = createObjectToNumberMapAsArray(testObject)
    val continuationToLincheckThreadIdMap = createContinuationToThreadIdMap(threads)
    val threadToLincheckThreadIdMap = createThreadToLincheckThreadIdMap(threads)

    visualizeInstance(testObject, objectToNumberMap, continuationToLincheckThreadIdMap, threadToLincheckThreadIdMap)
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