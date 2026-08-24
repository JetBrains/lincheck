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
import org.jetbrains.lincheck.trace.TRTracePoint
import org.jetbrains.lincheck.trace.printing.printTraceTree
import org.jetbrains.lincheck.trace.serialization.*
import org.jetbrains.lincheck.util.Logger
import org.jetbrains.lincheck.util.tree.Tree
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
            val points: Int,
        ) : State()
    }

    /**
     * This class hierarchy denotes various modes of tracing session.
     *
     * - [MethodCall] means that the tracing was started from a specific method.
     * - [ApplicationStart] means that the tracing was started from the application startup.
     * - [ExternalRequest] means that the tracing was started dynamically by external request during application run.
     */
    sealed class StartMode {
        data class MethodCall(
            val thread: Thread,
            val className: String,
            val methodName: String,
            val startingCodeLocationId: Int,
        ) : StartMode()

        data object ApplicationStart : StartMode()
        data object ExternalRequest : StartMode()
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
            points = eventTracker.collectedPoints
        )
        Logger.debug { "Trace collected in ${endTime - currentState.startTime} ms" }

        finishHook.get()?.invoke(this)
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
            is StartMode.MethodCall -> {
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
            points = currentState.points,
        )

        val traceWriteStartTime = System.currentTimeMillis()

        try {
            when (mode) {
                is TraceOutputMode.BinaryFileDump -> {
                    saveRecorderTrace(traceDumpFilePath, context, recordedTrees())
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
                    printTraceTree(traceDumpFilePath, context, recordedTrees(), verbose = mode.verbose)
                }
                TraceOutputMode.Null -> {}
            }
            Logger.info { "Trace was saved to $traceDumpFilePath" }
        } catch (t: Throwable) {
            Logger.error { "Cannot dump trace output file $traceDumpFilePath: ${t.message} at ${t.stackTraceToString()}" }
            return
        } finally {
            if (mode != TraceOutputMode.Null) {
                Logger.debug { "Trace written in ${System.currentTimeMillis() - traceWriteStartTime} ms" }
            }
        }
    }

    private fun recordedTrees(): List<Tree<TRTracePoint>> {
        val strategy = checkNotNull(eventTracker.memoryStrategy) {
            "Trace dump to a file requires the in-memory trace collecting strategy"
        }
        return strategy.getRecordedTrees()
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