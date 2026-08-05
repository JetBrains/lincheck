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
import org.jetbrains.lincheck.settings.BlocklistFileParser
import org.jetbrains.lincheck.settings.BreakpointExpressionSlot
import org.jetbrains.lincheck.settings.BreakpointId
import org.jetbrains.lincheck.settings.BreakpointsFileParser
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.settings.isApplicableTo
import org.jetbrains.lincheck.trace.network.LiveDebuggerNotification
import org.jetbrains.lincheck.trace.network.TracingNotificationListener
import org.jetbrains.lincheck.util.Logger
import sun.nio.ch.lincheck.BreakpointStorage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * De-duplicates blocked notifications to once per (breakpoint uuid, class), so a breakpoint blocked
     * across many methods of a class — or re-checked on every retransformation — notifies the user once.
     * Keyed by `"<uuid>|<className>"`; cleared when all breakpoints are removed.
     */
    private val blockedNotified = ConcurrentHashMap.newKeySet<String>()

    /**
     * De-duplicates dynamic-extent hit-suppression notifications to once per
     * (breakpoint uuid, blocked frame class) — sensitive paths are hit repeatedly, the user needs to
     * learn about each distinct blocked area once, not per request. Keyed by `"<uuid>|<frameClass>"`;
     * cleared on policy change and when all breakpoints are removed.
     */
    private val hitSuppressedNotified = ConcurrentHashMap.newKeySet<String>()

    fun loadBreakpointsFromFile(breakpointsFilePath: String?) {
        if (breakpointsFilePath == null) {
            Logger.warn { "Breakpoints file path is not set, skipping breakpoints loading" }
            return
        }
        try {
            Logger.info { "Loading breakpoints from file: $breakpointsFilePath" }

            val breakpoints = BreakpointsFileParser.parseBreakpointsFile(breakpointsFilePath)
            val settings = LincheckClassFileTransformer.liveDebuggerSettings
            val result = settings.addBreakpoints(breakpoints)
            result.rejected.forEach { notifyBreakpointBlocked(it.breakpoint, it.match.reason) }

            Logger.info {
                "Registered ${result.added.size} new breakpoints from $breakpointsFilePath" +
                    if (result.rejected.isNotEmpty()) " (${result.rejected.size} rejected by blocklist)" else ""
            }
        } catch (e: Exception) {
            Logger.error(e) { "Failed to load breakpoints from file: $breakpointsFilePath" }
        }
    }

    fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>) {
        Logger.info { "Adding breakpoints: $breakpoints" }

        val result = LincheckClassFileTransformer.liveDebuggerSettings
            .addBreakpoints(breakpoints)
        result.rejected.forEach { notifyBreakpointBlocked(it.breakpoint, it.match.reason) }
        retransformBreakpointClasses(result.added)
    }

    /**
     * Loads sensitive-area blocklists from an INI file (the `blocklistFile=` startup argument).
     * Must run before any breakpoint source is processed
     * so that Stage-1 registration rejection sees the policy.
     */
    fun loadBlocklistsFromFile(blocklistFilePath: String?) {
        if (blocklistFilePath == null) {
            Logger.debug { "Blocklist file path is not set, skipping blocklist loading" }
            return
        }
        try {
            val blocklists = BlocklistFileParser.parseBlocklistsFile(blocklistFilePath)
            LincheckClassFileTransformer.liveDebuggerSettings.blocklistRegistry.add(blocklists)
            LincheckClassFileTransformer.dynamicExtentChecker.invalidate()
            Logger.info { "Loaded ${blocklists.size} blocklist(s) from $blocklistFilePath" }
        } catch (e: Exception) {
            Logger.error(e) { "Failed to load blocklists from file: $blocklistFilePath" }
        }
    }

    /**
     * Pulls the sensitive-area policy from the control plane (`policyBootstrap=controlPlane`)
     * and adds it to the active policy. Must run before any breakpoint source
     * is processed so that Stage-1 registration rejection sees the control-plane policy.
     *
     * Combines by union with any policy from `blocklistFile=`. If the pull
     * fails, no control-plane policy is applied (any file-sourced policy still stands).
     */
    fun bootstrapPolicyFromControlPlane() {
        val blocklists = ControlPlane.fetchPolicyBlocklists()
        if (blocklists == null) {
            Logger.warn { "No control-plane policy applied (pull failed); breakpoints will register without it" }
            return
        }
        LincheckClassFileTransformer.liveDebuggerSettings.blocklistRegistry.add(blocklists)
        LincheckClassFileTransformer.dynamicExtentChecker.invalidate()
        Logger.info { "Applied ${blocklists.size} control-plane blocklist(s)" }
    }

    /**
     * Adds the given blocklists to the active policy (add-only: rules can only tighten at runtime)
     * and retransforms the breakpoint classes so hooks in newly-blocked areas are removed.
     * There is a natural lag until retransformation completes; already-injected hooks keep firing
     * during that window (as they do during the network delay of the policy push itself).
     */
    fun addSensitiveAreaBlocklists(blocklists: List<SensitiveAreaBlocklist>) {
        Logger.info { "Adding ${blocklists.size} blocklist(s)" }
        LincheckClassFileTransformer.liveDebuggerSettings.blocklistRegistry.add(blocklists)
        LincheckClassFileTransformer.dynamicExtentChecker.invalidate()
        hitSuppressedNotified.clear()
        retransformBreakpointClasses(LincheckClassFileTransformer.liveDebuggerSettings.lineBreakpoints.values)
    }

    fun removeBreakpoints(uuids: List<UUID>) {
        Logger.info { "Removing breakpoints: $uuids" }

        val result = LincheckClassFileTransformer.liveDebuggerSettings
            .removeBreakpoints(uuids)
        if (result.notFound.isNotEmpty()) {
            Logger.warn { "No registered breakpoints found for UUIDs: ${result.notFound}" }
        }
        retransformBreakpointClasses(result.removed)
    }

    fun removeAllBreakpoints() {
        Logger.info { "Removing all breakpoints" }

        val result = LincheckClassFileTransformer.liveDebuggerSettings
            .removeAllBreakpoints()
        blockedNotified.clear()
        hitSuppressedNotified.clear()
        retransformBreakpointClasses(result.removed)
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
     * file-aware narrowing in `buildClassInformation` anyway.
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

    /** Guard ensuring the breakpoint-blocked callback is registered exactly once. */
    private val breakpointBlockedCallbackInstalled = AtomicBoolean(false)

    /**
     * Registers the callback that fires when instrumentation-time suppression (Stage 2) rejects a
     * breakpoint because its class or method is a blocked sensitive area.
     *
     * Must be called before any class transformation can occur so that no blocked event can fire
     * before the callback is in place.
     */
    fun ensureBreakpointBlockedCallbackInstalled() {
        if (!breakpointBlockedCallbackInstalled.compareAndSet(false, true)) return

        BreakpointStorage.setOnBreakpointBlocked { _, userData, reason ->
            onBreakpointBlocked(userData as SnapshotBreakpoint, reason as String)
        }
        Logger.debug { "Breakpoint blocked callback installed" }
    }

    private fun onBreakpointBlocked(breakpoint: SnapshotBreakpoint, reason: String) {
        Logger.info {
            with(breakpoint) { "Breakpoint in $className at $fileName:$lineNumber blocked: $reason" }
        }
        notifyBreakpointBlocked(breakpoint, reason)
    }

    /** Guard ensuring the dynamic-extent hit-suppressed callback is registered exactly once. */
    private val hitSuppressedCallbackInstalled = AtomicBoolean(false)

    /**
     * Registers the callback that fires when a hit is suppressed by dynamic-extent enforcement
     * (Stage 3): its call stack passed through a blocked sensitive area.
     *
     * Must be called before tracing starts so no suppression event can fire unobserved.
     */
    fun ensureHitSuppressedCallbackInstalled() {
        if (!hitSuppressedCallbackInstalled.compareAndSet(false, true)) return

        BreakpointStorage.setOnHitSuppressed { _, userData, blockedFrameClass, reason ->
            onHitSuppressed(userData as SnapshotBreakpoint, blockedFrameClass, reason as String)
        }
        Logger.debug { "Hit suppressed callback installed" }
    }

    /**
     * Emits a [LiveDebuggerNotification.BreakpointHitSuppressed] to the connected client,
     * de-duplicated to once per (breakpoint uuid, blocked frame class). Runs on the hitting
     * application thread up to the dedup check; everything heavier is offloaded.
     */
    private fun onHitSuppressed(breakpoint: SnapshotBreakpoint, blockedFrameClass: String, reason: String) {
        if (!hitSuppressedNotified.add("${breakpoint.uuid}|$blockedFrameClass")) return

        Logger.info {
            with(breakpoint) {
                "Hit of breakpoint in $className at $fileName:$lineNumber suppressed: " +
                    "call stack passes through '$blockedFrameClass' ($reason)"
            }
        }
        val timestamp = System.currentTimeMillis()
        notificationsExecutor.submit {
            val notification = LiveDebuggerNotification.BreakpointHitSuppressed(
                timestamp = timestamp,
                breakpointData = LiveDebuggerNotification.BreakpointData(
                    breakpointUuid = breakpoint.uuid,
                    className = breakpoint.className,
                    fileName = breakpoint.fileName,
                    lineNumber = breakpoint.lineNumber,
                ),
                blockedFrameClass = blockedFrameClass,
                reason = reason,
            )
            notificationListener.get()?.invoke(notification)
        }
    }

    /**
     * Emits a [LiveDebuggerNotification.BreakpointBlocked] to the connected client, de-duplicated to
     * once per (breakpoint, class). The user always learns a breakpoint was blocked — never a silently
     * dead breakpoint.
     */
    private fun notifyBreakpointBlocked(breakpoint: SnapshotBreakpoint, reason: String) {
        if (!blockedNotified.add("${breakpoint.uuid}|${breakpoint.className}")) return

        val timestamp = System.currentTimeMillis()
        notificationsExecutor.submit {
            val notification = LiveDebuggerNotification.BreakpointBlocked(
                timestamp = timestamp,
                breakpointData = LiveDebuggerNotification.BreakpointData(
                    breakpointUuid = breakpoint.uuid,
                    className = breakpoint.className,
                    fileName = breakpoint.fileName,
                    lineNumber = breakpoint.lineNumber,
                ),
                reason = reason,
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
