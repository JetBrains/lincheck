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
import sun.nio.ch.lincheck.BreakpointStorage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object LiveDebugger {

    private val shutdownHookInstalled = AtomicBoolean(false)

    /**
     * Called by [LiveDebuggerAgent] to wire the JMX notification sender.
     * Invoked with a `"className:fileName:lineNumber"` breakpoint-ID string when a hit limit fires.
     */
    @Volatile
    var notificationSender: ((String) -> Unit)? = null

    /**
     * Single-threaded executor used to process hit-limit events off the instrumented thread.
     * Retransforming classes inside the instrumented execution thread can be risky;
     * scheduling it here keeps the hot path lightweight.
     */
    private val hitLimitExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LiveDebugger-HitLimit-Handler").also { it.isDaemon = true }
    }

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

            ensureHitLimitCallbackInstalled()
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

        ensureHitLimitCallbackInstalled()
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
        cleanupRemovedBreakpoints(removedBreakpoints)
    }

    /**
     * Called (on [hitLimitExecutor]) when a breakpoint's hit count reaches its configured limit.
     * Sends a JMX notification, then removes the breakpoint and retransforms the class.
     */
    private fun onHitLimitReached(breakpoint: SnapshotBreakpoint) {
        hitLimitExecutor.submit {
            val breakpointId = "${breakpoint.className}:${breakpoint.fileName}:${breakpoint.lineNumber}"
            Logger.info { "Breakpoint hit limit reached: $breakpointId" }

            // Notify the IDE first (lightweight — just sends a JMX message).
            notificationSender?.invoke(breakpointId)

            // Remove specifically by id, not by location equality.
            // If the user re-added the breakpoint at the same location in the window between
            // the hit-limit callback firing and this executor task running, the re-added
            // breakpoint will have a different id and must not be touched.
            val removed = LincheckClassFileTransformer.liveDebuggerSettings
                .removeBreakpointById(breakpoint.id)
            if (removed != null) cleanupRemovedBreakpoints(listOf(removed))
        }
    }

    /**
     * Retransforms the classes that contained the given removed breakpoints.
     * Condition factory and hit-counter cleanup is handled automatically by
     * [LiveDebuggerSettings.removeBreakpointById] / [LiveDebuggerSettings.removeBreakpoints],
     * which call [BreakpointStorage.removeBreakpoint].
     */
    private fun cleanupRemovedBreakpoints(removedBreakpoints: List<SnapshotBreakpoint>) {
        val classNamesToRetransform = removedBreakpoints.map { it.className }.toSet()
        val classesToRetransform = LincheckInstrumentation.instrumentation.allLoadedClasses
            .filter { it.name in classNamesToRetransform }
        LincheckInstrumentation.retransformClasses(classesToRetransform)
    }
    
    fun removeAllBreakpoints() {
        Logger.info { "Removing all breakpoints" }

        val removedBreakpoints = LincheckClassFileTransformer.liveDebuggerSettings.removeAllBreakpoints()
        val classNamesToRetransform = removedBreakpoints.map { it.className }.toSet()
        val classesToRetransform = LincheckInstrumentation.instrumentation.allLoadedClasses
            .filter { it.name in classNamesToRetransform }

        LincheckInstrumentation.retransformClasses(classesToRetransform)
    }

    /** Guard ensuring the hit-limit callback is registered exactly once. */
    private val hitLimitCallbackInstalled = AtomicBoolean(false)

    /**
     * Registers the hit-limit callback on [BreakpointStorage], if not yet installed.
     * [BreakpointStorage] passes the breakpoint's [userData][BreakpointStorage.BreakpointState.userData]
     * (the [SnapshotBreakpoint] stored at registration time) directly to the callback,
     * so no id-to-object lookup is needed here.
     *
     * Must be called before the first breakpoint is registered so that no hit-limit event
     * can fire before the callback is in place.
     */
    private fun ensureHitLimitCallbackInstalled() {
        if (!hitLimitCallbackInstalled.compareAndSet(false, true)) return
        BreakpointStorage.setOnHitLimitReached { userData ->
            onHitLimitReached(userData as SnapshotBreakpoint)
        }
    }
}