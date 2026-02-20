/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.recorder

import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.trace.recorder.TraceRecorderAgent.ARGUMENT_FORMAT
import org.jetbrains.lincheck.trace.recorder.TraceRecorderAgent.ARGUMENT_FOPTION
import org.jetbrains.lincheck.trace.recorder.TraceRecorderAgent.ARGUMENT_PACK
import org.jetbrains.lincheck.util.*
import java.util.concurrent.atomic.AtomicBoolean

internal object LiveDebugger {
    // TODO: reduce the copy-paste wrt. TraceRecorder class

    private val shutdownHookInstalled = AtomicBoolean(false)

    fun loadBreakpointsFromFile(breakpointsFilePath: String?) {
        if (breakpointsFilePath == null) {
            Logger.warn { "Breakpoints file path is not set, skipping breakpoints loading" }
            return
        }
        try {
            Logger.info { "Loading breakpoints from file: $breakpointsFilePath" }

            val breakpoints = BreakpointsFileParser.parseBreakpointsFile(breakpointsFilePath)
            val settings = LincheckClassFileTransformer.liveDebuggerSettings
            val addedBreakpoints = settings.addBreakpoints(breakpoints)

            Logger.info { "Registered ${addedBreakpoints.size} new breakpoints from $breakpointsFilePath" }
        } catch (e: Exception) {
            Logger.error(e) { "Failed to load breakpoints from file: $breakpointsFilePath" }
        }
    }

    fun startRecording() {
        val traceDumpFilePath = TraceAgentParameters.traceDumpFilePath
        val packTrace = (TraceAgentParameters.getArg(ARGUMENT_PACK) ?: "true").toBoolean()
        val recordingMode = TraceRecordingMode.parse(
            outputMode = TraceAgentParameters.getArg(ARGUMENT_FORMAT),
            outputOption = TraceAgentParameters.getArg(ARGUMENT_FOPTION),
            outputFilePath = traceDumpFilePath,
        )
        if (recordingMode.isFileMode) {
            if (traceDumpFilePath == null) {
                Logger.error { "Trace dump file path is not set for live debugger mode" }
            }
        }

        try {
            val session = TraceRecorder.startRecording(
                recordingMode = recordingMode,
                startMode = TraceRecorderSession.StartMode.Dynamic,
            )
            if (session == null) {
                Logger.warn { "Trace recording was not started" }
                return
            }
            if (recordingMode.isFileMode && traceDumpFilePath != null) {
                session.installOnFinishHook {
                    dumpTrace(traceDumpFilePath, packTrace)
                }
            }
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot start trace recording in live debugger mode" }
            return
        }
        registerShutdownHook()
    }

    fun stopRecording() {
        if (!TraceRecorder.isRecording()) return
        try {
            TraceRecorder.stopRecording()
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot stop trace recording in live debugger mode" }
        }
    }

    private fun registerShutdownHook() {
        if (!shutdownHookInstalled.compareAndSet(false, true)) return
        try {
            Runtime.getRuntime().addShutdownHook(Thread(::stopRecording))
        } catch (e: Exception) {
            Logger.error(e) { "Failed to register shutdown hook for live debugger tracing" }
        }
    }
}