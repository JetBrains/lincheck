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
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.util.*
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.ThreadDescriptor
import java.util.concurrent.atomic.AtomicInteger

/**
 * The `TraceRecorder` object manages the trace recording process.
 *
 * The trace recording process involves:
 * - Initializing the trace recorder using [install].
 * - Starting the trace recording using [startRecording].
 * - Stopping the trace recording using [stopRecording].
 * - Optionally dumping the recorded trace output to a specified location using [dumpTrace].
 * - Uninstalling the trace recorder to clean up resources with [uninstall].
 *
 * Proper usage of the [TraceRecorder] involves ensuring that:
 * - the trace recorder is correctly installed before starting any recording;
 * - the trace recorder is stopped before an attempt to dump the recorded trace to file;
 * - the trace recorder is stopped before uninstallation.
 */
object TraceRecorder {
    private var eventTracker: TraceCollectingEventTracker? = null

    private val startCount = AtomicInteger(0)

    @Volatile
    private var traceStarterThread: Thread? = null

    fun install(
        format: String?,
        formatOption: String?,
        traceDumpFilePath: String?,
        context: TraceContext,
    ) {
        // Set a signal "void" object from Injections for better text output
        INJECTIONS_VOID_OBJECT = Injections.VOID_RESULT

        check(eventTracker == null) {
            "Trace recorder has already been installed"
        }

        val mode = parseOutputMode(format, formatOption)
        // this method does not need 'runInsideIgnoredSection' because analysis is not enabled until its completion
        eventTracker = TraceCollectingEventTracker(mode, context,
            traceDumpFilePath = if (mode == TraceCollectorMode.BINARY_STREAM) traceDumpFilePath else null
        )
    }

    fun startRecording(className: String, methodName: String, startingCodeLocationId: Int) {
        val count = startCount.incrementAndGet()
        Logger.info {
            val threadName = Thread.currentThread().name
            "Trace recorder has been started from $className::$methodName in thread $threadName (startCount=$count)"
        }
        if (count > 1) return

        val eventTracker = this.eventTracker ?: error("Trace recorder has not been installed")

        traceStarterThread = Thread.currentThread()
        val descriptor = ThreadDescriptor.getCurrentThreadDescriptor()
            ?: Injections.registerCurrentThread(eventTracker)

        eventTracker.startTracing(className, methodName, startingCodeLocationId)
        Injections.enableGlobalEventTracking(TraceRecorder.eventTracker)
        descriptor.enableAnalysis()
    }

    fun startRecording() {
        val count = startCount.incrementAndGet()
        Logger.info {
            val threadName = Thread.currentThread().name
            "Trace recorder has been started in thread $threadName (startCount=$count)"
        }
        if (count > 1) return

        val eventTracker = this.eventTracker ?: error("Trace recorder has not been installed")

        eventTracker.startTracing()
        Injections.enableGlobalEventTracking(TraceRecorder.eventTracker)
    }

    fun stopRecording() {
        val startedCount = startCount.decrementAndGet()
        Logger.info { "Trace recorder has been stopped in thread \"${Thread.currentThread().name}\" (installCount=$startedCount)" }

        if (startedCount > 0) {
            // Try to deregister itself as the root descriptor if we started tracing
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

        val eventTracker = this.eventTracker ?: error("Trace recorder has not been installed")

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
        } else {
            throw IllegalStateException("Unexpected event tracking mode $mode")
        }

        eventTracker.finishTracing()
        LincheckInstrumentation.reportStatistics()
    }

    fun dumpTrace(traceDumpFilePath: String, packTrace: Boolean) {
        val eventTracker = this.eventTracker ?: error("Trace recorder has not been installed")
        eventTracker.dumpTrace(traceDumpFilePath, packTrace)
    }

    fun uninstall() {
        eventTracker = null
    }
}