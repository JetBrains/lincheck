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

import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.util.*
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
    private val startCount = AtomicInteger(0)

    @Volatile
    private var session: TraceRecorderSession? = null

    fun install(mode: TraceCollectorMode, traceDumpFilePath: String?) {
        // Set a signal "void" object from Injections for better text output
        INJECTIONS_VOID_OBJECT = Injections.VOID_RESULT

        check(session == null) {
            "Trace recorder session has already been started"
        }

        // this method does not need 'runInsideIgnoredSection' because analysis is not enabled until its completion
        val eventTracker = TraceCollectingEventTracker(mode, createTraceContext(),
            traceStreamingFilePath = if (mode == TraceCollectorMode.BINARY_STREAM) traceDumpFilePath else null
        )
        session = TraceRecorderSession(eventTracker)
    }

    fun startRecording() {
        startRecording(TraceRecorderSession.StartMode.Dynamic)
    }

    fun startRecording(className: String, methodName: String, startingCodeLocationId: Int) {
        startRecording(TraceRecorderSession.StartMode.FromMethod(
            thread = Thread.currentThread(),
            className = className,
            methodName = methodName,
            startingCodeLocationId = startingCodeLocationId,
        ))
    }

    private fun startRecording(startMode: TraceRecorderSession.StartMode) {
        val count = startCount.incrementAndGet()
        Logger.info {
            when (startMode) {
                is TraceRecorderSession.StartMode.FromMethod -> {
                    val className = startMode.className
                    val methodName = startMode.methodName
                    val threadName = Thread.currentThread().name
                    "Trace recorder has been started from $className::$methodName in thread $threadName (startCount=$count)"
                }
                is TraceRecorderSession.StartMode.Dynamic -> {
                    val threadName = Thread.currentThread().name
                    "Trace recorder has been started in thread $threadName (startCount=$count)"
                }
            }
        }
        if (count > 1) return

        val session = this.session ?: error("Trace recording session has not been initialized")
        check(!session.hasStarted()) {
            "Trace recording session has already been started"
        }

        val eventTracker = session.eventTracker

        var currentThreadDescriptor: ThreadDescriptor? = null
        when (startMode) {
            is TraceRecorderSession.StartMode.Dynamic -> {
                session.startDynamic()
            }

            is TraceRecorderSession.StartMode.FromMethod -> {
                val className = startMode.className
                val methodName = startMode.methodName
                val startingCodeLocationId = startMode.startingCodeLocationId
                val traceStarterThread = startMode.thread.ensure {
                    it == Thread.currentThread()
                }

                currentThreadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor()
                    ?: Injections.registerCurrentThread(eventTracker)

                eventTracker.registerCurrentThread(className, methodName, startingCodeLocationId)
                session.startFromMethod(traceStarterThread, className, methodName, startingCodeLocationId)
            }
        }

        Injections.enableGlobalEventTracking(eventTracker)
        currentThreadDescriptor?.enableAnalysis()
    }

    fun stopRecording() {
        val startedCount = startCount.decrementAndGet()
        Logger.info {
            val threadName = Thread.currentThread().name
            "Trace recorder has been stopped in thread $threadName (installCount=$startedCount)"
        }

        if (startedCount > 0) {
            val traceStarterThread = (session?.startMode as? TraceRecorderSession.StartMode.FromMethod)?.thread
            // Try to deregister itself as the root descriptor if we started tracing
            if (traceStarterThread == Thread.currentThread()) {
                val descriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
                ThreadDescriptor.unsetRootThread().ensure { it == descriptor }
            }
            return
        }
        if (startedCount < 0) {
            Logger.error { "Recording has been stopped more times than started: $startedCount (${Thread.currentThread().name})" }
            return
        }

        val session = this.session ?: error("Session has not been initialized")
        check(session.isRunning()) { "Tracing was not started" }

        val eventTracker = session.eventTracker

        // this method does not need 'runInsideIgnoredSection' because we do not call instrumented code,
        // and we call `disableAnalysis` as a first action
        val descriptor = ThreadDescriptor.getCurrentThreadDescriptor()
        descriptor?.disableAnalysis()

        if (descriptor != null && eventTracker != Injections.getEventTracker(descriptor)) {
            Logger.warn { "Unexpected event tracker observed during trace finishing" }
        }

        val mode = Injections.getEventTrackingMode()
        if (mode == Injections.EventTrackingMode.GLOBAL) {
            Injections.disableGlobalEventTracking()
        } else {
            throw IllegalStateException("Unexpected event tracking mode $mode")
        }

        eventTracker.finishTracing()
        session.finish()

        LincheckInstrumentation.reportStatistics()
    }

    fun dumpTrace(traceDumpFilePath: String, packTrace: Boolean) {
        val session = this.session ?: error("Session has not been initialized")
        check(session.isFinished()) { "Tracing was not stopped" }

        session.dumpTrace(traceDumpFilePath, packTrace)
    }

    fun uninstall() {
        session = null
    }

    private fun createTraceContext(): TraceContext {
        // TODO: currently we always re-use the same global context
        return LincheckInstrumentation.context
    }
}