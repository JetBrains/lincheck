/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.livedebugger

import org.jetbrains.lincheck.jvm.agent.LincheckClassFileTransformer
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.settings.BreakpointsFileParser
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.tracer.Tracer
import org.jetbrains.lincheck.tracer.TraceOutputMode
import org.jetbrains.lincheck.tracer.TracingSession
import org.jetbrains.lincheck.tracer.isFileMode
import org.jetbrains.lincheck.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

internal object LiveDebugger {

    private val shutdownHookInstalled = AtomicBoolean(false)

    fun startRecording(mode: TraceOutputMode, traceDumpFilePath: String? = null, packTrace: Boolean = true) {
        try {
            val session = Tracer.startTracing(
                outputMode = mode,
                startMode = TracingSession.StartMode.Static,
            )
            Logger.info { "Live debugging has been started" }

            if (mode.isFileMode && traceDumpFilePath != null) {
                session.installOnFinishHook {
                    dumpTrace(traceDumpFilePath, packTrace)
                }
            }
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot start live debugging" }
            return
        }
        registerShutdownHook()
    }

    fun stopRecording() {
        try {
            Tracer.stopTracing()
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot stop live debugging" }
        }
    }

    private fun registerShutdownHook() {
        if (!shutdownHookInstalled.compareAndSet(false, true)) return
        try {
            Runtime.getRuntime().addShutdownHook(Thread(::stopRecording))
        } catch (e: Exception) {
            Logger.error(e) { "Failed to register shutdown hook for live debugger" }
        }
    }

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
}