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

import org.jetbrains.lincheck.tracer.TraceOutputMode
import org.jetbrains.lincheck.tracer.Tracer
import org.jetbrains.lincheck.tracer.TracingSession
import org.jetbrains.lincheck.tracer.isFileMode
import org.jetbrains.lincheck.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages program-scoped trace recording for trace recorder mode.
 * Recording starts at program startup and stops at shutdown via a shutdown hook.
 */
internal object ProgramScopedTraceRecorder {

    private val shutdownHookInstalled = AtomicBoolean(false)

    fun startRecording(mode: TraceOutputMode, traceDumpFilePath: String? = null, packTrace: Boolean = true) {
        try {
            // Force BinaryFileDump mode for program-scoped recording.
            // BinaryFileStream has a race condition where multiple threads can write descriptors
            // in the wrong order due to shared ContextSavingState across threads.
            val effectiveMode = when (mode) {
                is TraceOutputMode.BinaryFileStream -> TraceOutputMode.BinaryFileDump()
                else -> mode
            }
            val session = Tracer.startTracing(
                outputMode = effectiveMode,
                startMode = TracingSession.StartMode.Static,
            )
            Logger.info { "Program-scoped trace recording has been started" }

            if (mode.isFileMode && traceDumpFilePath != null) {
                session.installOnFinishHook {
                    Tracer.dumpTrace(traceDumpFilePath, packTrace)
                }
            }
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot start trace recording in trace recorder mode" }
            return
        }
        registerShutdownHook()
    }

    fun stopRecording() {
        try {
            Tracer.stopTracing()
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot stop trace recording in trace recorder mode" }
        }
    }

    private fun registerShutdownHook() {
        if (!shutdownHookInstalled.compareAndSet(false, true)) return
        try {
            Runtime.getRuntime().addShutdownHook(Thread(::stopRecording))
        } catch (e: Exception) {
            Logger.error(e) { "Failed to register shutdown hook for trace recorder" }
        }
    }
}
