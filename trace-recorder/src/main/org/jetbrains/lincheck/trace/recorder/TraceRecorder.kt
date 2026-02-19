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

import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.util.*
import sun.nio.ch.lincheck.*
import java.util.concurrent.atomic.*

/**
 * The `TraceRecorder` object manages the trace recording process.
 *
 * The trace recording process involves:
 * - Starting the trace recording using [startRecording].
 * - Stopping the trace recording using [stopRecording].
 * - Optionally dumping the recorded trace output to a specified location using [dumpTrace].
 *
 * Proper usage of the [TraceRecorder] involves ensuring that:
 * - the trace recorder is stopped before an attempt to dump the recorded trace to file;
 * - the trace recorder is stopped before the next trace recording sessions start.
 */
object TraceRecorder {
    @Volatile
    private var session: TraceRecorderSession? = null

    fun isRecording(): Boolean =
        session?.isRunning() ?: false

    @Synchronized
    fun startRecording(
        recordingMode: TraceRecordingMode,
        startMode: TraceRecorderSession.StartMode,
    ): TraceRecorderSession {
        // Set a signal "void" object from Injections for better text output
        INJECTIONS_VOID_OBJECT = Injections.VOID_RESULT

        val previousSession = this.session
        if (previousSession != null) {
            if (previousSession.isFinished()) {
                Logger.info { "Previous trace recorder session was finished, it will be replaced by a new session" }
                this.session = null
            } else {
                check(previousSession.isRunning())
                Logger.info { "A trace recorder session is already running, returning the existing session" }
                return previousSession
            }
        }

        // this method does not need 'runInsideIgnoredSection' because analysis is not enabled until its completion
        val session = createSession(recordingMode)
            .also { this.session = it }
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

        Logger.info {
            when (startMode) {
                is TraceRecorderSession.StartMode.FromMethod -> {
                    val className = startMode.className
                    val methodName = startMode.methodName
                    val threadName = Thread.currentThread().name
                    "Trace recorder has been started from $className::$methodName in thread $threadName"
                }
                is TraceRecorderSession.StartMode.Dynamic -> {
                    val threadName = Thread.currentThread().name
                    "Trace recorder has been started in thread $threadName"
                }
            }
        }

        currentThreadDescriptor?.enableAnalysis()
        return session
    }

    @Synchronized
    fun stopRecording() {
        val session = this.session ?: error("No trace recorder session is running to stop")
        check(session.isRunning()) { "Trace recorder session was not started" }

        val eventTracker = session.eventTracker

        // this method does not need 'runInsideIgnoredSection' because we do not call instrumented code,
        // and we call `disableAnalysis` as a first action
        val descriptor = ThreadDescriptor.getCurrentThreadDescriptor()
        descriptor?.disableAnalysis()

        if (descriptor != null && eventTracker != Injections.getEventTracker(descriptor)) {
            Logger.warn { "Unexpected event tracker observed during trace recorder session finishing" }
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

    @Synchronized
    fun dumpTrace(traceDumpFilePath: String, packTrace: Boolean) {
        val session = this.session ?: error("No trace recorder session is running to dump trace")
        check(session.isFinished()) { "Trace recorder session was not stopped" }

        session.dumpTrace(traceDumpFilePath, packTrace)
    }

    fun addBreakpoints(breakpoints: List<String>) {
        Logger.info { "Adding breakpoints: $breakpoints" }

        val addedBreakpoints = LincheckClassFileTransformer.liveDebuggerSettings
            .addBreakpoints(breakpoints.map { SnapshotBreakpoint.parseFromString(it) })
        val classNamesToRetransform = addedBreakpoints.map { it.className }.toSet()
        val classesToRetransform = LincheckInstrumentation.instrumentation.allLoadedClasses
            .filter { it.name in classNamesToRetransform }

        LincheckInstrumentation.retransformClasses(classesToRetransform)
    }

    fun removeBreakpoints(breakpoints: List<String>) {
        Logger.info { "Removing breakpoints: $breakpoints" }

        val removedBreakpoints = LincheckClassFileTransformer.liveDebuggerSettings
            .removeBreakpoints(breakpoints.map { SnapshotBreakpoint.parseFromString(it) })
        val classNamesToRetransform = removedBreakpoints.map { it.className }.toSet()
        val classesToRetransform = LincheckInstrumentation.instrumentation.allLoadedClasses
            .filter { it.name in classNamesToRetransform }

        LincheckInstrumentation.retransformClasses(classesToRetransform)
    }

    private fun createTraceContext(): TraceContext {
        // TODO: currently we always re-use the same global context
        return LincheckInstrumentation.context
    }

    private fun createSession(recordingMode: TraceRecordingMode): TraceRecorderSession {
        val eventTracker = TraceCollectingEventTracker(
            mode = recordingMode,
            layout = if (isInLiveDebuggerMode) TraceDataLayout.FLAT else TraceDataLayout.TREE,
            context = createTraceContext(),
            traceStreamingFilePath = (recordingMode as? TraceRecordingMode.BinaryFileStream)?.streamingFilePath
        )

        var tcpServer: TcpTraceServer? = null
        if (recordingMode is TraceRecordingMode.BinaryTcpStream) {
            try {
                tcpServer = TcpTraceServer(
                    port = TraceAgentParameters.DEFAULT_TRACE_PORT,
                    subscriptionService = eventTracker.subscriptionService!!,
                )
                Logger.info { "Started TCP trace streaming server on port $${tcpServer.port}" }
            } catch (t: Throwable) {
                Logger.error(t) { "Cannot start TCP trace trace server" }
            }
        }

        return TraceRecorderSession(eventTracker, tcpServer)
    }
}