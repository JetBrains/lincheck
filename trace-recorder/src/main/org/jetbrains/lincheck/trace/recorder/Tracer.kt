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
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.tracer.TracingMode
import org.jetbrains.lincheck.util.*
import sun.nio.ch.lincheck.*

/**
 * The [Tracer] object manages the tracing process.
 *
 * The tracing process involves:
 * - Starting a tracing session via [startTracing].
 * - Stopping a tracing session using [stopTracing].
 * - Optionally dumping the recorded trace output to a specified location using [dumpTrace].
 */
object Tracer {
    @Volatile
    private var session: TracingSession? = null

    /**
     * Starts a new tracing session with the specified recording mode and start mode.
     *
     * If some session is already running, returns the existing session.
     * If another session was started earlier and finished, replaces it with a new session.
     *
     * @param recordingMode The recording mode that configures the trace collection strategy,
     *   such as in-memory, file streaming, or network transfer
     *   (see [org.jetbrains.lincheck.tracer.TracingMode] for more details).
     * @param startMode Specifies how the tracing session should begin.
     *   It could start dynamically at an arbitrary point or from a specific method with additional context
     *   (see [TracingSession.StartMode] for more details).
     * @return The newly created or existing tracing session.
     */
    @Synchronized
    fun startTracing(
        recordingMode: TracingMode,
        startMode: TracingSession.StartMode,
    ): TracingSession {
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
            is TracingSession.StartMode.Dynamic -> {
                session.startDynamic()
            }

            is TracingSession.StartMode.FromMethod -> {
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
                is TracingSession.StartMode.FromMethod -> {
                    val className = startMode.className
                    val methodName = startMode.methodName
                    val threadName = Thread.currentThread().name
                    "Trace recorder session has been started from $className::$methodName in thread $threadName"
                }
                is TracingSession.StartMode.Dynamic -> {
                    val threadName = Thread.currentThread().name
                    "Trace recorder session has been started in thread $threadName"
                }
            }
        }

        currentThreadDescriptor?.enableAnalysis()
        return session
    }

    /**
     * Stops the currently running tracing session, if any. Returns the finished session.
     * If there is no running session or a current session was already finished earlier, returns `null`.
     *
     * @return The stopped tracing session or `null` if no session was running.
     */
    @Synchronized
    fun stopTracing(): TracingSession? {
        val session = this.session
        if (session == null) {
            Logger.warn { "No trace recorder session is running to stop" }
            return null
        }

        check(session.hasStarted()) {
            "Trace recorder session has not started yet"
        }
        if (session.isFinished()) return session

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

        Logger.info {
            when (val startMode = session.startMode) {
                is TracingSession.StartMode.FromMethod -> {
                    val className = startMode.className
                    val methodName = startMode.methodName
                    val threadName = Thread.currentThread().name
                    "Trace recorder session has been stopped from $className::$methodName in thread $threadName"
                }
                is TracingSession.StartMode.Dynamic -> {
                    val threadName = Thread.currentThread().name
                    "Trace recorder session has been stopped in thread $threadName"
                }
                else -> unreachable()
            }
        }

        return session
    }

    /**
     * Dumps the trace to the specified file path.
     * If there is no current session or a current session was not stopped yet, does nothing.
     *
     * @return `true` if the trace was successfully dumped, `false` otherwise.
     */
    @Synchronized
    fun dumpTrace(traceDumpFilePath: String, packTrace: Boolean): Boolean {
        val session = this.session
        if (session == null) {
            Logger.warn { "No trace recorder session is running to dump trace" }
            return false
        }
        if (!session.isFinished()) {
            Logger.warn { "Trace recorder session was not stopped" }
            return false
        }

        session.dumpTrace(traceDumpFilePath, packTrace)
        return true
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

    private fun createSession(recordingMode: TracingMode): TracingSession {
        val eventTracker = TraceCollectingEventTracker(
            mode = recordingMode,
            layout = if (isInLiveDebuggerMode) TraceDataLayout.FLAT else TraceDataLayout.TREE,
            context = createTraceContext(),
            traceStreamingFilePath = (recordingMode as? TracingMode.BinaryFileStream)?.streamingFilePath
        )

        var tcpServer: TcpTraceServer? = null
        if (recordingMode is TracingMode.BinaryTcpStream) {
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

        return TracingSession(eventTracker, tcpServer)
    }
}