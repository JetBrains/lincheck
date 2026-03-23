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

import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.trace.INDEX_FILENAME_EXT
import org.jetbrains.lincheck.trace.PACK_FILENAME_EXT
import org.jetbrains.lincheck.trace.TraceMetaInfo
import org.jetbrains.lincheck.trace.network.TracingServer
import org.jetbrains.lincheck.trace.printPostProcessedTrace
import org.jetbrains.lincheck.trace.packRecordedTrace
import org.jetbrains.lincheck.trace.saveRecorderTrace
import org.jetbrains.lincheck.util.Logger
import java.util.concurrent.atomic.AtomicReference

class TracingSession(
    val eventTracker: TraceCollectingEventTracker,
) {
    internal sealed class State {
        object NotStarted : State()

        class InProgress(
            val startMode: StartMode,
            val startTime: Long,
        ) : State()

        class Finished(
            val startMode: StartMode,
            val startTime: Long,
            val endTime: Long,
        ) : State()
    }

    /**
     * This class hierarchy denotes various modes of trace recorder session.
     *
     * - [FromMethod] means that the tracing was started from a specific method.
     * - [Static] means that the tracing was started from the application startup.
     * - [Dynamic] means that the tracing was started dynamically by external request during application run.
     */
    sealed class StartMode {
        data class FromMethod(
            val thread: Thread,
            val className: String,
            val methodName: String,
            val startingCodeLocationId: Int,
        ) : StartMode()

        object Static : StartMode()
        object Dynamic : StartMode()
    }

    @Volatile
    internal var state: State = State.NotStarted
        private set

    /**
     * Gets the current mode if the session is running or finished.
     */
    internal val startMode: StartMode? get() = when (val s = state) {
        is State.NotStarted -> null
        is State.InProgress -> s.startMode
        is State.Finished   -> s.startMode
    }

    private val finishHook = AtomicReference<TracingSession.() -> Unit>()

    /**
     * Checks if the session has been started.
     */
    fun hasStarted(): Boolean = state !is State.NotStarted

    /**
     * Checks if the session is currently running.
     */
    fun isRunning(): Boolean = state is State.InProgress

    /**
     * Checks if the session is finished.
     */
    fun isFinished(): Boolean = state is State.Finished

    fun start(mode: StartMode) {
        val currentState = state
        check(currentState is State.NotStarted) {
            "Cannot start tracing session: it is already started"
        }
        state = State.InProgress(
            startMode = mode,
            startTime = System.currentTimeMillis()
        )
    }

    /**
     * Finishes the tracing session.
     */
    fun finish() {
        val currentState = state
        check(currentState is State.InProgress)

        val endTime = System.currentTimeMillis()
        state = State.Finished(
            startMode = currentState.startMode,
            startTime = currentState.startTime,
            endTime = endTime,
        )
        Logger.debug { "Trace collected in ${endTime - currentState.startTime} ms" }

        stopNetworkServer()
        finishHook.get()?.invoke(this)
    }

    private fun stopNetworkServer() {
        // TODO is this needed?
    }

    fun installOnFinishHook(hook: TracingSession.() -> Unit) {
        val wasAlreadySet = !finishHook.compareAndSet(null, hook)
        if (wasAlreadySet) {
            error("Finish hook was already set for this session")
        }
    }

    /**
     * Dumps the collected trace to the specified file.
     */
    fun dumpTrace(traceDumpFilePath: String, packTrace: Boolean) {
        val currentState = state
        check(currentState is State.Finished)

        val mode = eventTracker.mode
        val context = eventTracker.context

        var className: String? = null
        var methodName: String? = null
        when (val mode = startMode) {
            is StartMode.FromMethod -> {
                className = mode.className
                methodName = mode.methodName
            }
            else -> {}
        }
        val metaInfo = TraceMetaInfo.create(
            agentArgs = TraceAgentParameters.rawArgs,
            className = className ?: "",
            methodName = methodName ?: "",
            startTime = currentState.startTime,
            endTime = currentState.endTime,
        )

        val traceWriteStartTime = System.currentTimeMillis()

        try {
            val roots = eventTracker.getThreadRoots()
            when (mode) {
                is TraceOutputMode.BinaryFileDump -> {
                    saveRecorderTrace(traceDumpFilePath, context, roots)
                    if (packTrace) {
                        packRecordedTrace(traceDumpFilePath, metaInfo)
                    }
                }
                is TraceOutputMode.BinaryFileStream -> {
                    check(traceDumpFilePath == eventTracker.traceStreamingFilePath) {
                        // TODO: it should be easy to support dumping to a different file later: just copy file
                        "Trace dump filename in binary stream mode should match the filename of streaming file"
                    }
                    if (packTrace) {
                        packRecordedTrace(traceDumpFilePath, metaInfo)
                    }
                }
                is TraceOutputMode.BinaryNetworkStream -> {
                    // WebSocket streaming - trace already sent over network, nothing to dump to file
                    error("Trace is streamed over WebSocket, no data stored to save into a file")
                }
                is TraceOutputMode.Text -> {
                    printPostProcessedTrace(traceDumpFilePath, context, roots, verbose = mode.verbose)
                }
                TraceOutputMode.Null -> {}
            }
            Logger.info { "Trace was saved to $traceDumpFilePath" }
        } catch (t: Throwable) {
            Logger.error { "TraceRecorder: Cannot write output file $traceDumpFilePath: ${t.message} at ${t.stackTraceToString()}" }
            return
        } finally {
            if (mode != TraceOutputMode.Null) {
                Logger.debug { "Trace written in ${System.currentTimeMillis() - traceWriteStartTime} ms" }
            }
        }
    }

    private fun packRecordedTrace(baseFileName: String, metaInfo: TraceMetaInfo) {
        packRecordedTrace(
            dataFileName = baseFileName,
            indexFileName = "$baseFileName.${INDEX_FILENAME_EXT}",
            outputFileName = "$baseFileName.${PACK_FILENAME_EXT}",
            metaInfo = metaInfo,
        )
    }
}