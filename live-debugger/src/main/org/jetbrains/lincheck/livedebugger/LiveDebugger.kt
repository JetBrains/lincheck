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
import org.jetbrains.lincheck.jvm.agent.analysis.SafetyViolation
import org.jetbrains.lincheck.settings.BreakpointExpressionSlot
import org.jetbrains.lincheck.settings.BreakpointId
import org.jetbrains.lincheck.settings.BreakpointsFileParser
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.settings.isApplicableTo
import org.jetbrains.lincheck.trace.network.LiveDebuggerNotification
import org.jetbrains.lincheck.trace.network.TracingNotificationListener
import org.jetbrains.lincheck.util.Logger
import sun.nio.ch.lincheck.BreakpointStorage
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object LiveDebugger {

    /**
     * Listener responsible for handling live debugging notifications.
     */
    private val notificationListener = AtomicReference<TracingNotificationListener>()

    /**
     * Single-threaded executor used to process hit-limit events off the instrumented thread.
     * Retransforming classes inside the instrumented execution thread can be risky;
     * scheduling it here keeps the hot path lightweight.
     */
    private val notificationsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LiveDebugger-Notifications-Handler").also { it.isDaemon = true }
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

    fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>) {
        Logger.info { "Adding breakpoints: $breakpoints" }

        val addedBreakpoints = LincheckClassFileTransformer.liveDebuggerSettings
            .addBreakpoints(breakpoints)
        retransformBreakpointClasses(addedBreakpoints)
    }

    fun removeBreakpoints(uuids: List<UUID>) {
        Logger.info { "Removing breakpoints: $uuids" }

        val removedBreakpoints = LincheckClassFileTransformer.liveDebuggerSettings
            .removeBreakpoints(uuids)
        retransformBreakpointClasses(removedBreakpoints)
    }

    fun removeAllBreakpoints() {
        Logger.info { "Removing all breakpoints" }

        val removedBreakpoints = LincheckClassFileTransformer.liveDebuggerSettings
            .removeAllBreakpoints()
        retransformBreakpointClasses(removedBreakpoints)
    }

    /**
     * Disables the breakpoint by removing it and retransforming the affected class.
     */
    private fun disableBreakpoint(id: BreakpointId) {
        // Remove specifically by id, not by location equality.
        // If the user re-added the breakpoint at the same location in the window between
        // the hit-limit callback firing and this executor task running,
        // the re-added breakpoint will have a different id and must not be touched.
        val removedBreakpoint = LincheckClassFileTransformer.liveDebuggerSettings
            .removeBreakpoint(id)
        if (removedBreakpoint != null) {
            retransformBreakpointClasses(listOf(removedBreakpoint))
        }
    }

    /**
     * Retransforms the classes that contain the given breakpoints.
     *
     * `Class.getName` returns a canonical name, so we use the class-only
     * [SnapshotBreakpoint.isApplicableTo] overload — at this point we don't have the
     * source file for each loaded class, and the retransformation pipeline does the
     * file-aware narrowing in `LincheckClassVisitor` anyway.
     */
    private fun retransformBreakpointClasses(breakpoints: Collection<SnapshotBreakpoint>) {
        val classesToRetransform = LincheckInstrumentation.instrumentation.allLoadedClasses
            .filter { loadedClass ->
                breakpoints.any { it.isApplicableTo(loadedClass.name) }
            }
        LincheckInstrumentation.retransformClasses(classesToRetransform)
    }

    /** Guard ensuring the hit-limit callback is registered exactly once. */
    private val hitLimitCallbackInstalled = AtomicBoolean(false)

    /**
     * Registers the hit-limit callback on [BreakpointStorage], if not yet installed.
     *
     * [BreakpointStorage] passes to the callback the [SnapshotBreakpoint] stored at registration time
     * as userData (see [BreakpointStorage.BreakpointState.userData]),
     * so no id-to-object lookup is needed here.
     *
     * Must be called before the tracing is started so that no hit-limit event
     * can fire before the callback is in place.
     */
    fun ensureHitLimitCallbackInstalled() {
        if (!hitLimitCallbackInstalled.compareAndSet(false, true)) return

        BreakpointStorage.setOnHitLimitReached { id, userData ->
            onHitLimitReached(id, userData as SnapshotBreakpoint)
        }
        Logger.debug { "Hit limit callback installed" }
    }

    /**
     * Called when a breakpoint's hit count reaches its configured limit.
     * Sends a notification to the connected client, then removes the breakpoint, and retransforms the class.
     */
    private fun onHitLimitReached(id: BreakpointId, breakpoint: SnapshotBreakpoint) {
        val timestamp = System.currentTimeMillis()
        Logger.info {
            with (breakpoint) {
                "Hit limit reached for breakpoint in $className at $fileName:$lineNumber"
            }
        }

        notificationsExecutor.submit {
            disableBreakpoint(id)

            val notification = LiveDebuggerNotification.BreakpointHitLimitReached(
                timestamp = timestamp,
                breakpointData = LiveDebuggerNotification.BreakpointData(
                    breakpointUuid = breakpoint.uuid,
                    className = breakpoint.className,
                    fileName = breakpoint.fileName,
                    lineNumber = breakpoint.lineNumber,
                ),
            )
            notificationListener.get()?.invoke(notification)
        }
    }

    /** Guard ensuring the breakpoint-expression-unsafety callback is registered exactly once. */
    private val breakpointExpressionUnsafetyCallbackInstalled = AtomicBoolean(false)

    /**
     * Registers the breakpoint-expression-unsafety callback on [BreakpointStorage], if not yet installed.
     *
     * The callback is fired at class-transformation time when a breakpoint's condition
     * or watch expression is detected to have side effects.
     *
     * Must be called before any class transformation can occur so that no
     * unsafety event can fire before the callback is in place.
     */
    fun ensureBreakpointExpressionUnsafetyCallbackInstalled() {
        if (!breakpointExpressionUnsafetyCallbackInstalled.compareAndSet(false, true)) return

        BreakpointStorage.setOnBreakpointExpressionUnsafetyDetected { id, userData, slot, safetyViolation ->
            onBreakpointExpressionUnsafetyDetected(
                id,
                userData as SnapshotBreakpoint,
                slot as BreakpointExpressionSlot,
                safetyViolation as SafetyViolation,
            )
        }
        Logger.debug { "Breakpoint expression unsafety callback installed" }
    }

    /**
     * Called when a breakpoint's condition or watch expression is detected to be unsafe
     * (has side effects). Sends a notification, then removes the breakpoint, and
     * retransforms the class.
     */
    private fun onBreakpointExpressionUnsafetyDetected(
        id: BreakpointId,
        breakpoint: SnapshotBreakpoint,
        slot: BreakpointExpressionSlot,
        safetyViolation: SafetyViolation,
    ) {
        val timestamp = System.currentTimeMillis()
        Logger.info {
            with (breakpoint) {
                "Unsafe $slot expression detected for breakpoint in $className at $fileName:$lineNumber"
            }
        }

        notificationsExecutor.submit {
            disableBreakpoint(id)

            val notification = LiveDebuggerNotification.BreakpointExpressionUnsafetyDetected(
                timestamp = timestamp,
                breakpointData = LiveDebuggerNotification.BreakpointData(
                    breakpointUuid = breakpoint.uuid,
                    className = breakpoint.className,
                    fileName = breakpoint.fileName,
                    lineNumber = breakpoint.lineNumber,
                ),
                slot = slot,
                safetyViolationMessage = safetyViolation.toString(),
            )
            notificationListener.get()?.invoke(notification)
        }
    }

    fun installNotificationListener(listener: TracingNotificationListener) {
        val wasAlreadySet = !notificationListener.compareAndSet(null, listener)
        if (wasAlreadySet) {
            error("Live Debugger notification listener was already set")
        }
    }
}
