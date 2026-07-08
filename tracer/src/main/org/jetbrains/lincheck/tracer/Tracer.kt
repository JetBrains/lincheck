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
import org.jetbrains.lincheck.trace.INJECTIONS_VOID_OBJECT
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.tracer.Tracer.dumpTrace
import org.jetbrains.lincheck.tracer.Tracer.startTracing
import org.jetbrains.lincheck.tracer.Tracer.stopTracing
import org.jetbrains.lincheck.util.Logger
import org.jetbrains.lincheck.util.isInLiveDebuggerMode
import org.jetbrains.lincheck.util.unreachable
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.ThreadDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    private val lock = ReentrantLock()

    private val shutdownHookInstalled = AtomicBoolean(false)

    /**
     * Starts a new tracing session and sets up its full lifecycle management.
     *
     * Unlike [startTracing], which only starts the session and returns it, this method additionally:
     * - Installs a finish hook to dump the trace to [traceDumpFilePath] when the session ends,
     *   if [outputMode] is a file-based mode and [traceDumpFilePath] is provided.
     * - Registers a JVM shutdown hook to stop the session gracefully on process exit.
     *
     * @param startMode Specifies how the tracing session should begin.
     *   It could start dynamically at an arbitrary point or from a specific method with additional context
     *   (see [TracingSession.StartMode] for more details).
     * @param outputMode The output mode that configures the trace collection strategy,
     *   such as in-memory, file streaming, or network transfer
     *   (see [TraceOutputMode] for more details).
     * @param traceDumpFilePath The file path to dump the trace to when the session finishes.
     *   If `null` or if [outputMode] is not a file-based mode, no dump is performed.
     * @param packTrace Whether to pack the trace after dumping. Defaults to `true`.
     */
    fun launchTracingSession(
        startMode: TracingSession.StartMode,
        outputMode: TraceOutputMode,
        traceDumpFilePath: String? = null,
        packTrace: Boolean = true
    ) = lock.withLock {
        try {
            val session = startTracing(outputMode, startMode)
            if (outputMode.isFileMode && traceDumpFilePath != null) {
                session.installOnFinishHook {
                    dumpTrace(traceDumpFilePath, packTrace)
                }
            }
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot launch tracing session" }
            return
        }
        registerShutdownHook()
    }

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
    fun startTracing(
        outputMode: TraceOutputMode,
        startMode: TracingSession.StartMode,
    ): TracingSession = lock.withLock {
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
        if (startMode is TracingSession.StartMode.MethodCall) {
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
                is TracingSession.StartMode.MethodCall -> {
                    val className = startMode.className
                    val methodName = startMode.methodName
                    val threadName = Thread.currentThread().name
                    "Tracing session has been started from $className::$methodName in thread $threadName"
                }
                is TracingSession.StartMode.ApplicationStart -> {
                    val threadName = Thread.currentThread().name
                    "Tracing session has been started at start-up from thread $threadName"
                }
                is TracingSession.StartMode.ExternalRequest -> {
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
    fun stopTracing(): TracingSession? = lock.withLock {
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
                is TracingSession.StartMode.MethodCall -> {
                    val className = startMode.className
                    val methodName = startMode.methodName
                    val threadName = Thread.currentThread().name
                    "Tracing session has been stopped from $className::$methodName in thread $threadName"
                }
                is TracingSession.StartMode.ApplicationStart -> {
                    val threadName = Thread.currentThread().name
                    "Tracing session has been stopped from thread $threadName"
                }
                is TracingSession.StartMode.ExternalRequest -> {
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
    fun dumpTrace(traceDumpFilePath: String, packTrace: Boolean): Boolean = lock.withLock {
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
            traceStreamingFilePath = (mode as? TraceOutputMode.BinaryFileStream)?.streamingFilePath,
        )

        return TracingSession(eventTracker)
    }

    private fun registerShutdownHook() {
        if (!shutdownHookInstalled.compareAndSet(false, true)) return
        try {
            Runtime.getRuntime().addShutdownHook(Thread(::stopTracing))
        } catch (e: Exception) {
            Logger.error(e) { "Failed to register tracing shutdown hook" }
        }
    }
}