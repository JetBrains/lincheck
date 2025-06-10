/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.tracerecorder

import org.jetbrains.kotlinx.lincheck.strategy.tracerecorder.TraceRecorder.finishTraceAndDumpResults
import org.jetbrains.kotlinx.lincheck.traceagent.TraceAgentParameters
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.ensureObjectIsTransformed
import org.jetbrains.kotlinx.lincheck.transformation.withLincheckJavaAgent
import sun.nio.ch.lincheck.ThreadDescriptor

/**
 * This object is glue between injections into method user wants to record trace of and real trace recording code.
 *
 * Call which leads to [installAndStartTrace] must be placed as first instruction of method in question.
 *
 * Call which leads to [finishTraceAndDumpResults] must be placed before each exit point (`return` or `throw`) in
 * a method in question.
 *
 * This is effectively an implementation of such Java code:
 *
 * ```java
 * methodInQuestion() {
 *  TraceRecorder.installAndStartTrace(...);
 *  try {
 *    <original method code>
 *  } finally {
 *    TraceRecorder.finishTraceAndDumpResults();
 *  }
 * }
 * ```
 *
 * This class is used to avoid coupling between instrumented code and `bootstrap.jar`, to enable very early
 * instrumentation before `bootstrap.jar` is added to class
 */
object TraceRecorder {
    private var eventTracker: TraceCollectingEventTracker? = null

    fun installAndStartTrace(className: String, methodName: String, methodDesc: String, traceFileName: String?) {
        // this method does need 'runInsideIgnoredSection' because analysis is not enabled until its completion
        eventTracker = TraceCollectingEventTracker(className, methodName, methodDesc, traceFileName)
        val desc = ThreadDescriptor.getCurrentThreadDescriptor() ?: ThreadDescriptor(Thread.currentThread()).also {
            ThreadDescriptor.setCurrentThreadDescriptor(it)
        }
        desc.eventTracker = eventTracker

        eventTracker!!.enableTrace()
        desc.enableAnalysis()
    }

    fun finishTraceAndDumpResults() {
        // this method does not need 'runInsideIgnoredSection' because we do not call instrumented code
        // and 'eventTracker.finishAndDumpTrace()' is called after analysis is disabled
        val desc = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val currentTracker = desc.eventTracker
        if (currentTracker == eventTracker) {
            desc.disableAnalysis()
            eventTracker?.finishAndDumpTrace()
            eventTracker = null
        }
    }




    /**
     * WARNING: This is an internal function for testing purposes only.
     * Records the execution trace from [block]
     * The captured trace is written to [outputFile]
     */
    fun recordTraceInternal(outputFile: String, block: Runnable) {
        check(outputFile.isNotBlank()) { "Output file name must not be blank" }
        TraceAgentParameters.classUnderTraceDebugging = TraceRecordingWrapper::class.java.name
        TraceAgentParameters.methodUnderTraceDebugging = "runTraceRecording"
        TraceAgentParameters.traceDumpFilePath = outputFile

        withLincheckJavaAgent(InstrumentationMode.TRACE_RECORDING) {
            ensureObjectIsTransformed(block)
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(TraceAgentParameters.classUnderTraceDebugging)
            TraceRecordingWrapper().runTraceRecording(block)

        }
        finishTraceAndDumpResults()
    }
}


internal class TraceRecordingWrapper {
    fun runTraceRecording(block: Runnable) = block.run()
}