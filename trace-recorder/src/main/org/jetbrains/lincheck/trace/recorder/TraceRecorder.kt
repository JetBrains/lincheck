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
import org.jetbrains.lincheck.util.*
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
    private var traceStarterThread: Thread? = null

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
        Logger.info { "Trace recorder has been started from $className::$methodName in thread \"${Thread.currentThread().name}\" (installCount=$startedCount)" }

        if (startedCount > 1) return

        // Set a signal "void" object from Injections for better text output
        INJECTIONS_VOID_OBJECT = Injections.VOID_RESULT

        // this method does not need 'runInsideIgnoredSection' because analysis is not enabled until its completion
        eventTracker = TraceCollectingEventTracker(
            className = className,
            methodName = methodName,
            traceDumpPath = traceFileName,
            mode = parseOutputMode(format, formatOption),
            packTrace = pack
        )
        traceStarterThread = Thread.currentThread()
        val descriptor = ThreadDescriptor.getCurrentThreadDescriptor()
            ?: Injections.registerCurrentThread(eventTracker)

        if (trackAllThreads) {
            Injections.enableGlobalEventTracking(eventTracker)
        } else {
            Injections.enableThreadLocalEventTracking()
            ThreadDescriptor.setCurrentThreadAsRoot(descriptor)
        }
        eventTracker!!.enableTrace()

        descriptor.enableAnalysis()
    }

    fun finishTraceAndDumpResults() {
        val startedCount = installCount.decrementAndGet()
        Logger.info { "Trace recorder has been stopped in thread \"${Thread.currentThread().name}\" (installCount=$startedCount)" }

        if (startedCount > 0) {
            // Try to deregister itself as root descriptor if we started tracing
            if (traceStarterThread == Thread.currentThread()) {
                val descriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
                ThreadDescriptor.unsetRootThread().ensure { it == descriptor }
                traceStarterThread = null
            }
            return
        }
        if (startedCount < 0) {
            Logger.error { "Recording has been stopped more times than started: $startedCount (${Thread.currentThread().name})" }
            return
        }

        // this method does not need 'runInsideIgnoredSection' because we do not call instrumented code,
        // and we call `disableAnalysis` as a first action
        val descriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        descriptor.disableAnalysis()

        if (eventTracker != Injections.getEventTracker(descriptor)) {
            Logger.warn { "Unexpected event tracker observed during trace finishing" }
        }

        val mode = Injections.getEventTrackingMode()
        if (mode == Injections.EventTrackingMode.GLOBAL) {
            Injections.disableGlobalEventTracking()
        } else if (mode == Injections.EventTrackingMode.THREAD_LOCAL) {
            ThreadDescriptor.unsetRootThread().ensure { it == descriptor }
            Injections.disableThreadLocalEventTracking()
        } else {
            throw IllegalStateException("Unexpected event tracking mode $mode")
        }

        if (traceStarterThread == Thread.currentThread()) {
            ThreadDescriptor.unsetRootThread().ensure { it == descriptor }
            traceStarterThread = null
        }

        eventTracker?.finishAndDumpTrace()
        eventTracker = null
    }
}