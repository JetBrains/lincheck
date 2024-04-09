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
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*

const val MINIMAL_PLUGIN_VERSION = "0.0.1"

// ============== This methods are used by debugger from IDEA plugin to communicate with Lincheck ============== //

/**
 * Invoked from the strategy [ModelCheckingStrategy] when Lincheck found a bug.
 * The Debugger creates a breakpoint on this method, so when it's called, debugger receives all the information about the
 * failed test.
 * This method is called first, to provide all required information,
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
    return eventIdSequentialCheck
}

/**
 * This is a marker method for the plugin to detect the Lincheck test start.
 *
 * The plugin uses this method to disable breakpoints until a failure is found.
 */
fun lincheckVerificationStarted() {}

/**
 * If Debugger needs to replay execution (due to earlier trace point selection), it replaces the result of this
 * method to `true`.
 */
fun replay(): Boolean {
    return false // should be replaced with `true` to replay the failure
}

/**
 * This method is called on every trace point shown to the user.
 * The Debugger creates a breakpoint inside this method and if [eventId] is the selected one, the breakpoint is triggered.
 *
 * @param eventId id of this trace point. Consistent with `trace`, provided in [testFailed] method.
 * @param type type of this event, just for debugging.
 */
@Suppress("UNUSED_PARAMETER", "unused")
fun beforeEvent(eventId: Int, type: String) {
    if (needVisualization()) {
        val strategy = (Thread.currentThread() as? TestThread)?.eventTracker ?: return
        visualize(strategy)
    }
}

fun needVisualization(): Boolean = false // may be replaced with 'true' in plugin

/**
 * This method receives all information about the test object instance to visualize.
 * The Debugger creates a breakpoint inside this method and uses this method parameters to create the diagram.
 *
 * We pass Maps as Arrays due to difficulties with passing objects to the debugger (class version, etc.).
 *
 * @param testObject tested data structure.
 * @param numbersArrayMap an array structured like [Object, objectNumber, Object, objectNumber, ...]. Represents a `Map<Any, Int>`.
 * @param threadsArrayMap an array structured like [Thread, threadId, Thread, threadId, ...]. Represents a `Map<Any, Int>`.
 * @param threadToLincheckThreadIdMap an array structured like [CancellableContinuation, threadId, CancellableContinuation, threadId, ...]. Represents a `Map<Any, Int>`.
 */
@Suppress("UNUSED_PARAMETER")
fun visualizeInstance(
    testObject: Any,
    numbersArrayMap: Array<Any>,
    threadsArrayMap: Array<Any>,
    threadToLincheckThreadIdMap: Array<Any>
) {
}

fun onThreadSwitchesOrActorFinishes() {}

// ======================================================================================================== //

internal val eventIdSequentialCheck = System.getProperty("lincheck.debug.eventIdSequentialCheck") != null

private fun visualize(strategyObject: Any) = runCatching {
    val strategy = strategyObject as ModelCheckingStrategy
    val runner = strategy.runner as ParallelThreadsRunner
    val testObject = runner.testInstance
    val threads = runner.executor.threads

    val labelsMap = createObjectToNumberMapAsArray(testObject)
    val continuationToLincheckThreadIdMap = createContinuationToThreadIdMap(threads)
    val threadToLincheckThreadIdMap = createThreadToLincheckThreadIdMap(threads)

    visualizeInstance(testObject, labelsMap, continuationToLincheckThreadIdMap, threadToLincheckThreadIdMap)
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

    val numbersMap = createObjectToNumberMap(testObject)
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