/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.recorder

import org.jetbrains.lincheck.trace.INJECTIONS_VOID_OBJECT
import sun.nio.ch.lincheck.Injections
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

    fun installAndStartTrace(
        className: String,
        methodName: String,
        traceFileName: String?,
        format: String?,
        formatOption: String?,
        pack: Boolean,
        traceAllThreads: Boolean
    ) {
        // Set signal "void" object from Injections for better text output
        INJECTIONS_VOID_OBJECT = Injections.VOID_RESULT

        // this method does not need 'runInsideIgnoredSection' because analysis is not enabled until its completion
        eventTracker = TraceCollectingEventTracker(
            className = className,
            methodName = methodName,
            traceDumpPath = traceFileName,
            mode = parseOutputMode(format, formatOption),
            packTrace = pack
        )
        val desc = ThreadDescriptor.getCurrentThreadDescriptor() ?: ThreadDescriptor(Thread.currentThread()).also {
            ThreadDescriptor.setCurrentThreadDescriptor(it)
        }
        desc.setAsRootDescriptor()
        desc.eventTracker = eventTracker

        eventTracker!!.enableTrace()
        desc.enableAnalysis()
        if (traceAllThreads) {
            Injections.enableGlobalThreadsTracking(eventTracker)
        }
    }

    fun finishTraceAndDumpResults() {
        // this method does not need 'runInsideIgnoredSection' because we do not call instrumented code
        // and 'eventTracker.finishAndDumpTrace()' is called after analysis is disabled
        val desc = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        Injections.disableGlobalThreadsTracking()
        desc.removeAsRootDescriptor()
        val currentTracker = desc.eventTracker
        if (currentTracker == eventTracker) {
            desc.disableAnalysis()
            eventTracker?.finishAndDumpTrace()
            eventTracker = null
        }
    }
}