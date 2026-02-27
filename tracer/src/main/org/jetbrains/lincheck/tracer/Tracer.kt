/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.tracer

import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.trace.INJECTIONS_VOID_OBJECT
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.NetworkTraceServer
import org.jetbrains.lincheck.util.Logger
import org.jetbrains.lincheck.util.isInLiveDebuggerMode
import org.jetbrains.lincheck.util.unreachable
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.ThreadDescriptor

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
     * @param outputMode The output mode that configures the trace collection strategy,
     *   such as in-memory, file streaming, or network transfer
     *   (see [TraceOutputMode] for more details).
     * @param startMode Specifies how the tracing session should begin.
     *   It could start dynamically at an arbitrary point or from a specific method with additional context
     *   (see [TracingSession.StartMode] for more details).
     * @return The newly created or existing tracing session.
     */
    @Synchronized
    fun startTracing(
        outputMode: TraceOutputMode,
        startMode: TracingSession.StartMode,
    ): TracingSession {
        // Set a signal "void" object from Injections for better text output
        INJECTIONS_VOID_OBJECT = Injections.VOID_RESULT

        val previousSession = this.session
        if (previousSession != null) {
            if (previousSession.isFinished()) {
                Logger.info { "Previous tracing session was finished, it will be replaced by a new session" }
                this.session = null
            } else {
                check(previousSession.isRunning())
                Logger.info { "A tracing session is already running, returning the existing session" }
                return previousSession
            }
        }

        // this method does not need 'runInsideIgnoredSection' because analysis is not enabled until its completion
        val session = createSession(outputMode)
            .also { this.session = it }
        val eventTracker = session.eventTracker

        var currentThreadDescriptor: ThreadDescriptor? = null
        if (startMode is TracingSession.StartMode.FromMethod) {
            val className = startMode.className
            val methodName = startMode.methodName
            val startingCodeLocationId = startMode.startingCodeLocationId
            check(startMode.thread == Thread.currentThread())

            currentThreadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor()
                ?: Injections.registerCurrentThread(eventTracker)

            eventTracker.registerCurrentThread(className, methodName, startingCodeLocationId)
        }

        session.start(startMode)
        Injections.enableGlobalEventTracking(eventTracker)

        Logger.info {
            when (startMode) {
                is TracingSession.StartMode.FromMethod -> {
                    val className = startMode.className
                    val methodName = startMode.methodName
                    val threadName = Thread.currentThread().name
                    "Tracing session has been started from $className::$methodName in thread $threadName"
                }
                is TracingSession.StartMode.Static -> {
                    val threadName = Thread.currentThread().name
                    "Tracing session has been started at start-up from thread $threadName"
                }
                is TracingSession.StartMode.Dynamic -> {
                    val threadName = Thread.currentThread().name
                    "Tracing session has been started dynamically from thread $threadName"
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
            Logger.warn { "No tracing session is running to stop" }
            return null
        }

        check(session.hasStarted()) {
            "Tracing session has not started yet"
        }
        if (session.isFinished()) return session

        val eventTracker = session.eventTracker

        // this method does not need 'runInsideIgnoredSection' because we do not call instrumented code,
        // and we call `disableAnalysis` as a first action
        val descriptor = ThreadDescriptor.getCurrentThreadDescriptor()
        descriptor?.disableAnalysis()

        if (descriptor != null && eventTracker != Injections.getEventTracker(descriptor)) {
            Logger.warn { "Unexpected event tracker observed during tracing session finishing" }
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
                    "Tracing session has been stopped from $className::$methodName in thread $threadName"
                }
                is TracingSession.StartMode.Static -> {
                    val threadName = Thread.currentThread().name
                    "Tracing session has been stopped from thread $threadName"
                }
                is TracingSession.StartMode.Dynamic -> {
                    val threadName = Thread.currentThread().name
                    "Tracing session has been stopped from thread $threadName"
                }
                null -> unreachable()
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
            Logger.warn { "Cannot dump trace: tracing session was not started" }
            return false
        }
        if (!session.isFinished()) {
            Logger.warn { "Cannot dump trace: tracing session was not stopped" }
            return false
        }

        session.dumpTrace(traceDumpFilePath, packTrace)
        return true
    }

    private fun createTraceContext(): TraceContext {
        // TODO: currently we always re-use the same global context
        return LincheckInstrumentation.context
    }

    private fun createSession(mode: TraceOutputMode): TracingSession {
        val eventTracker = TraceCollectingEventTracker(
            mode = mode,
            layout = if (isInLiveDebuggerMode) TraceDataLayout.FLAT else TraceDataLayout.TREE,
            context = createTraceContext(),
            traceStreamingFilePath = (mode as? TraceOutputMode.BinaryFileStream)?.streamingFilePath
        )

        var networkServer: NetworkTraceServer? = null
        if (mode is TraceOutputMode.BinaryNetworkStream) {
            try {
                networkServer = NetworkTraceServer(
                    port = TraceAgentParameters.DEFAULT_TRACE_PORT,
                    subscriptionService = eventTracker.subscriptionService!!,
                    onDisconnected = { 
                        mode.onDisconnect() 
                        eventTracker.subscriptionService.clearBuffers()
                    }
                )
                Logger.info { "Started trace streaming server on at ${networkServer.url}" }
            } catch (t: Throwable) {
                Logger.error(t) { "Cannot start trace server" }
            }
        }

        return TracingSession(eventTracker, networkServer)
    }
}