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

// This is org.jetbrains.kotlinx.lincheck.IdeaPluginKt class

const val MINIMAL_PLUGIN_VERSION = "0.0.1"

// ============== This methods are used by debugger from IDEA plugin to communicate with Lincheck ============== //

// Invoked by Lincheck after the minimization is applied.
@Suppress("UNUSED_PARAMETER")
fun testFailed(
    executionResultsInitPart: Array<String>?,
    executionResultsParallelPart: Array<Array<String>>?,
    executionResultsPostPart: Array<String>?,
    trace: Array<String>,
    version: String?,
    minimalPluginVersion: String
) {
}

fun ideaPluginEnabled(): Boolean { // should be replaced with `true` to debug the failure
    // treat as enabled in tests
    return isDebuggerTestMode
}

fun replay(): Boolean {
    return false // should be replaced with `true` to replay the failure
}

@Suppress("UNUSED_PARAMETER", "unused")
fun beforeEvent(eventId: Int, type: String, strategyObject: Any) {
    if (needVisualization()) {
        visualize(strategyObject)
    }
}

@Suppress("UNUSED_PARAMETER")
fun visualizeInstance(
    testObject: Any,
    numbersArrayMap: Array<Any>,
    threadsArrayMap: Array<Any>,
    threadToLincheckThreadIdMap: Array<Any>
) {
}

fun needVisualization(): Boolean = false // may be replaced with 'true' in plugin

fun onThreadChange() {}

// ======================================================================================================== //

internal val isDebuggerTestMode = System.getProperty("lincheck.debug.test") != null

private fun visualize(strategyObject: Any) = runCatching {
    val strategy = strategyObject as ModelCheckingStrategy
    val runner = strategy.runner as ParallelThreadsRunner
    val testObject = runner.testInstance
    val threads = runner.executor.threads

    val labelsMap = createObjectToNumberMap(testObject)
    val continuationToLincheckThreadIdMap = createContinuationToThreadIdMap(threads)
    val threadToLincheckThreadIdMap = createThreadToLincheckThreadIdMap(threads)

    visualizeInstance(testObject, labelsMap, continuationToLincheckThreadIdMap, threadToLincheckThreadIdMap)
}

private fun createObjectToNumberMap(testObject: Any): Array<Any> {
    val resultArray = arrayListOf<Any>()

    val numbersMap = traverseTestObject(testObject)
    numbersMap.forEach { (labeledObject, label) -> // getObjectNumbersMap()
        resultArray.add(labeledObject)
        resultArray.add(label)
    }
    return resultArray.toTypedArray()
}

private fun createThreadToLincheckThreadIdMap(threads: Array<FixedActiveThreadsExecutor.TestThread>): Array<Any> {
    val array = arrayListOf<Any>()
    for (thread in threads) {
        array.add(thread)
        array.add(thread.iThread)
    }

    return array.toTypedArray()
}

private fun createContinuationToThreadIdMap(threads: Array<FixedActiveThreadsExecutor.TestThread>): Array<Any> {
    val array = arrayListOf<Any>()
    for (thread in threads) {
        array.add(thread.cont ?: continue)
        array.add(thread.iThread)
    }

    return array.toTypedArray()
}