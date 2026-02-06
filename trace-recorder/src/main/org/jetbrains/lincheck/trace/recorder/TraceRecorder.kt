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
 * - Starting the trace recording using [startRecording].
 * - Stopping the trace recording using [stopRecording].
 * - Optionally dumping the recorded trace output to a specified location using [dumpTrace].
 *
 * Proper usage of the [TraceRecorder] involves ensuring that:
 * - the trace recorder is stopped before an attempt to dump the recorded trace to file;
 * - the trace recorder is stopped before the next trace recording sessions start.
 */
object TraceRecorder {
    private val startCount = AtomicInteger(0)

    @Volatile
    private var session: TraceRecorderSession? = null

    fun isRecording(): Boolean =
        session?.isRunning() ?: false

    fun startRecording(
        recordingMode: TraceRecordingMode,
        startMode: TraceRecorderSession.StartMode,
    ): TraceRecorderSession? {
        // Set a signal "void" object from Injections for better text output
        INJECTIONS_VOID_OBJECT = Injections.VOID_RESULT

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
        if (count > 1) return null

        val previousSession = this.session
        if (previousSession != null) {
            check(previousSession.isFinished()) {
                "Previous trace recorder session was not stopped before starting a new one"
            }
            this.session = null
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
        currentThreadDescriptor?.enableAnalysis()

        return session
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
                tcpServer = TcpTraceServer(eventTracker.subscriptionService!!)
                Logger.info { "Started TCP trace streaming server on port $${tcpServer.port}" }
            } catch (t: Throwable) {
                Logger.error { "Cannot start TCP trace trace server" }
                Logger.error(t)
            }
        }

        return TraceRecorderSession(eventTracker, tcpServer)
    }
}