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
import org.jetbrains.lincheck.util.Logger
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.ThreadDescriptor
import java.util.concurrent.atomic.AtomicInteger

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

    private val installCount = AtomicInteger(0)
    @Volatile
    private var traceStarter: Thread? = null

    fun installAndStartTrace(
        className: String,
        methodName: String,
        traceFileName: String?,
        format: String?,
        formatOption: String?,
        pack: Boolean,
        trackAllThreads: Boolean
    ) {
        val startedCount = installCount.incrementAndGet()
        if (startedCount > 1) {
            Logger.debug { "Trace recorder has been started multiple times: $startedCount (${Thread.currentThread().name})" }
            return
        }
        Logger.debug { "Trace recorder has been started first time: $startedCount (${Thread.currentThread().name})" }
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
        traceStarter = Thread.currentThread()
        desc.setAsRootDescriptor()
        desc.eventTracker = eventTracker

        eventTracker!!.enableTrace()
        desc.enableAnalysis()
        if (trackAllThreads) {
            Injections.enableGlobalThreadsTracking(eventTracker)
        }
    }

    fun finishTraceAndDumpResults() {
        val startedCount = installCount.decrementAndGet()
        if (startedCount > 0) {
            Logger.debug { "Recording has been stopped less times than started: $startedCount (${Thread.currentThread().name})" }
            // But try to de-register itself as root descriptor, if we started tracing
            if (traceStarter == Thread.currentThread()) {
                val desc = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
                desc.removeAsRootDescriptor()
                traceStarter = null
            }
            return
        }
        if (startedCount < 0) {
            Logger.error { "Recording has been stopped more times than started: $startedCount (${Thread.currentThread().name})" }
            return
        }
        Logger.debug { "Recording has been stopped properly: $startedCount (${Thread.currentThread().name})" }
        // this method does not need 'runInsideIgnoredSection' because we do not call instrumented code
        // and 'eventTracker.finishAndDumpTrace()' is called after analysis is disabled
        val desc = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        Injections.disableGlobalThreadsTracking()
        if (traceStarter == Thread.currentThread()) {
            desc.removeAsRootDescriptor()
            traceStarter = null
        }

        val currentTracker = desc.eventTracker
        if (currentTracker == eventTracker) {
            desc.disableAnalysis()
            eventTracker?.finishAndDumpTrace()
            eventTracker = null
        }
    }
}