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

import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.jvm.agent.SourceFileClassIndex
import org.jetbrains.lincheck.jvm.agent.analysis.SafetyViolation
import org.jetbrains.lincheck.jvm.agent.expressions.ExpressionCompiler
import org.jetbrains.lincheck.settings.BlocklistFileParser
import org.jetbrains.lincheck.settings.BreakpointExpressionSlot
import org.jetbrains.lincheck.settings.BreakpointId
import org.jetbrains.lincheck.settings.BreakpointsFileParser
import org.jetbrains.lincheck.settings.RedactionFileParser
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.settings.isApplicableTo
import org.jetbrains.lincheck.settings.liveDebuggerSettings
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
    private val compilationFailedNotified = ConcurrentHashMap.newKeySet<String>()

    /**
     * De-duplicates dynamic-extent hit-suppression notifications to once per
     * (breakpoint uuid, blocked frame class) — sensitive paths are hit repeatedly, the user needs to
     * learn about each distinct blocked area once, not per request. Keyed by `"<uuid>|<frameClass>"`;
     * cleared on policy change and when all breakpoints are removed.
     */
    private val hitSuppressedNotified = ConcurrentHashMap.newKeySet<String>()

    /** Whether the required policy is in force; `false` while it is pending, failed to load, or was revoked. */
    @Volatile
    private var policyInForce = true

    /** Set once for a configuration no policy delivery can fix; keeps debugging closed for good. */
    @Volatile
    private var policyMisconfigured = false

    val isDebuggingAllowed: Boolean
        get() = policyInForce && !policyMisconfigured

    /** The control-plane policy version in force, reported on every heartbeat; `null` until one is applied. */
    @Volatile
    var controlPlanePolicyVersion: String? = null
        private set

    /** Startup breakpoints parked until a pending control-plane policy arrives. */
    private val pendingStartupBreakpoints = mutableListOf<SnapshotBreakpoint>()

    private fun setPolicyInForce(inForce: Boolean) {
        policyInForce = inForce
        liveDebuggerSettings.requiredRedactionPolicyValid = isDebuggingAllowed
        if (!inForce) removeAllBreakpoints()
    }

    fun loadBreakpointsFromFile(breakpointsFilePath: String?) {
        if (breakpointsFilePath == null) {
            Logger.warn { "Breakpoints file path is not set, skipping breakpoints loading" }
            return
        }
        try {
            Logger.info { "Loading breakpoints from file: $breakpointsFilePath" }

            val breakpoints = BreakpointsFileParser.parseBreakpointsFile(breakpointsFilePath)
            if (!isDebuggingAllowed) {
                synchronized(pendingStartupBreakpoints) { pendingStartupBreakpoints.addAll(breakpoints) }
                Logger.warn { "Queued ${breakpoints.size} startup breakpoint(s) until the required policy is valid" }
                return
            }
            val settings = liveDebuggerSettings
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
        if (!isDebuggingAllowed) {
            Logger.warn { "Rejected ${breakpoints.size} breakpoint(s): required redaction policy is unavailable" }
            return
        }
        Logger.info { "Adding breakpoints: $breakpoints" }

        val result = liveDebuggerSettings.addBreakpoints(breakpoints)
        removeStaleCompiledExpressions()
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
            liveDebuggerSettings.blocklistRegistry.add(blocklists)
            liveDebuggerSettings.dynamicExtentChecker.invalidate()
            Logger.info { "Loaded ${blocklists.size} blocklist(s) from $blocklistFilePath" }
        } catch (e: Exception) {
            Logger.error(e) { "Failed to load blocklists from file: $blocklistFilePath" }
        }
    }

    /**
     * Loads startup redaction policy before any breakpoint source. A configured-invalid file closes
     * debugging without preventing the application from starting.
     */
    fun loadRedactionTemplatesFromFile(redactionFilePath: String?) {
        if (redactionFilePath == null) {
            setPolicyInForce(true)
            return
        }
        setPolicyInForce(false)
        try {
            val templates = RedactionFileParser.parseTemplatesFile(redactionFilePath)
            liveDebuggerSettings.redactionRegistry.replace(templates)
            setPolicyInForce(true)
            Logger.info { "Loaded ${templates.size} redaction template(s) from $redactionFilePath" }
        } catch (e: Exception) {
            Logger.error(e) {
                "Failed to load required redaction policy from $redactionFilePath; debugging is disabled"
            }
        }
    }

    /**
     * Keeps debugging closed until the control plane's policy arrives over the heartbeat (`policySource=controlPlane`).
     * Deployment policy files conflict with that source and, like a missing heartbeat, keep debugging closed for good.
     */
    fun awaitControlPlanePolicy(deploymentFilesConfigured: Boolean, heartbeatEnabled: Boolean) {
        policyMisconfigured = deploymentFilesConfigured
        setPolicyInForce(false)
        if (deploymentFilesConfigured) {
            Logger.error {
                "blocklistFile= and redactionFile= need policySource=deployment; " +
                    "the control-plane policy source ignores them and debugging is disabled"
            }
        }
        if (!heartbeatEnabled) {
            Logger.error { "policySource=controlPlane needs liveDebuggerHeartbeat=on; debugging is disabled" }
        }
    }

    /**
     * Installs the control plane's [policy] in place of the previous one, opening debugging if nothing else keeps
     * it closed. Unlike other blocklist sources this one may loosen: the control plane owns it outright.
     * A policy that does not parse keeps debugging closed until a valid one arrives.
     */
    fun applyControlPlanePolicy(policy: ControlPlanePolicy) {
        val settings = liveDebuggerSettings
        try {
            val templates = RedactionFileParser.parseTemplates(policy.redactionIni)
            val blocklists = BlocklistFileParser.parseBlocklists(policy.blocklistsIni)
            settings.redactionRegistry.replace(templates)
            settings.blocklistRegistry.replacePolicy(blocklists)
            Logger.info {
                "Applied control-plane policy ${policy.version}: " +
                    "${templates.size} redaction template(s), ${blocklists.size} blocklist(s)"
            }
        } catch (e: Exception) {
            Logger.error(e) { "Rejected control-plane policy ${policy.version}; debugging is disabled" }
            controlPlanePolicyVersion = null
            setPolicyInForce(false)
            return
        }
        settings.dynamicExtentChecker.invalidate()
        hitSuppressedNotified.clear()
        controlPlanePolicyVersion = policy.version
        setPolicyInForce(true)
        settings.lineBreakpoints.values.takeIf { it.isNotEmpty() }?.let(::retransformBreakpointClasses)
        releasePendingStartupBreakpoints()
    }

    /** Closes debugging after the control plane stopped answering: its policy may have changed meanwhile. */
    fun revokeControlPlanePolicy() {
        Logger.warn { "The control plane stopped answering heartbeats; debugging is disabled until it answers again" }
        controlPlanePolicyVersion = null
        setPolicyInForce(false)
    }

    private fun releasePendingStartupBreakpoints() {
        if (!isDebuggingAllowed) return
        val pending = synchronized(pendingStartupBreakpoints) {
            pendingStartupBreakpoints.toList().also { pendingStartupBreakpoints.clear() }
        }
        if (pending.isNotEmpty()) addBreakpoints(pending)
    }

    /**
     * Adds the given blocklists to the active policy (add-only: rules can only tighten at runtime)
     * and retransforms the breakpoint classes so hooks in newly-blocked areas are removed.
     * There is a natural lag until retransformation completes; already-injected hooks keep firing
     * during that window (as they do during the network delay of the policy push itself).
     */
    fun addSensitiveAreaBlocklists(blocklists: List<SensitiveAreaBlocklist>) {
        Logger.info { "Adding ${blocklists.size} blocklist(s)" }
        liveDebuggerSettings.blocklistRegistry.add(blocklists)
        liveDebuggerSettings.dynamicExtentChecker.invalidate()
        hitSuppressedNotified.clear()
        retransformBreakpointClasses(liveDebuggerSettings.lineBreakpoints.values)
    }

    fun removeBreakpoints(uuids: List<UUID>) {
        Logger.info { "Removing breakpoints: $uuids" }

        val result = liveDebuggerSettings.removeBreakpoints(uuids)
        removeStaleCompiledExpressions()
        if (result.notFound.isNotEmpty()) {
            Logger.warn { "No registered breakpoints found for UUIDs: ${result.notFound}" }
        }
        retransformBreakpointClasses(result.removed)
    }

    fun removeAllBreakpoints() {
        Logger.info { "Removing all breakpoints" }

        val result = liveDebuggerSettings.removeAllBreakpoints()
        removeStaleCompiledExpressions()
        blockedNotified.clear()
        compilationFailedNotified.clear()
        hitSuppressedNotified.clear()
        if (result.removed.isEmpty()) return
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
        val removedBreakpoint = liveDebuggerSettings.removeBreakpoint(id)
        removeStaleCompiledExpressions()
        if (removedBreakpoint != null) {
            retransformBreakpointClasses(listOf(removedBreakpoint))
        }
    }

    private fun removeStaleCompiledExpressions() {
        ExpressionCompiler.retainCompiledExpressions(
            liveDebuggerSettings.lineBreakpoints.keys,
        )
    }

    /**
     * Retransforms the loaded classes compiled from the given breakpoints' source files.
     *
     * The classes are looked up in the agent-side [SourceFileClassIndex] by the breakpoint's
     * file name — the class name the IDE resolved is not consulted here. The retransformation
     * pipeline then does the per-breakpoint narrowing in `buildClassInformation`.
     */
    private fun retransformBreakpointClasses(breakpoints: Collection<SnapshotBreakpoint>) {
        val classesToRetransform = LincheckInstrumentation.instrumentation.allLoadedClasses
            .filter { loadedClass ->
                breakpoints.any { it.isApplicableTo(loadedClass) }
            }
        LincheckInstrumentation.retransformClasses(classesToRetransform)
    }

    private fun SnapshotBreakpoint.isApplicableTo(clazz: Class<*>): Boolean {
        // Optimization for the case when `className` was passed along with other breakpoint info ---
        // in this case we can avoid the source file index lookup and just use the provided class name.
        if (className.isNotEmpty()) {
            return this.isApplicableTo(clazz.name)
        }
        return SourceFileClassIndex.sourceFileContains(fileName, clazz)
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

    /** Guard ensuring the breakpoint-expression-failure callback is registered exactly once. */
    private val breakpointExpressionFailureCallbackInstalled = AtomicBoolean(false)

    /**
     * Registers the callback that fires when a breakpoint's condition or watch throws on a hit.
     * [BreakpointStorage] already de-duplicates the events per (breakpoint, slot, failure kind).
     *
     * Must be called before tracing starts so no failure event can fire unobserved.
     */
    fun ensureBreakpointExpressionFailureCallbackInstalled() {
        if (!breakpointExpressionFailureCallbackInstalled.compareAndSet(false, true)) return

        BreakpointStorage.setOnBreakpointExpressionEvaluationFailed { id, userData, slot, failureKind, disabled ->
            val expressionSlot =
                if (slot == BreakpointStorage.EXPRESSION_SLOT_CONDITION) BreakpointExpressionSlot.Condition
                else BreakpointExpressionSlot.Watch
            onBreakpointExpressionEvaluationFailed(id, userData as SnapshotBreakpoint, expressionSlot, failureKind, disabled)
        }
        Logger.debug { "Breakpoint expression failure callback installed" }
    }

    /**
     * Called on the hitting thread when a breakpoint's condition or watch threw.
     * Sends a notification; when [disabled], first removes the breakpoint and retransforms the class.
     */
    private fun onBreakpointExpressionEvaluationFailed(
        id: BreakpointId,
        breakpoint: SnapshotBreakpoint,
        slot: BreakpointExpressionSlot,
        failureKind: String,
        disabled: Boolean,
    ) {
        val timestamp = System.currentTimeMillis()
        Logger.info {
            with (breakpoint) {
                "$slot expression of breakpoint in $className at $fileName:$lineNumber threw $failureKind" +
                    if (disabled) "; disabling the breakpoint" else ""
            }
        }

        notificationsExecutor.submit {
            if (disabled) disableBreakpoint(id)

            val notification = LiveDebuggerNotification.BreakpointExpressionEvaluationFailed(
                timestamp = timestamp,
                breakpointData = LiveDebuggerNotification.BreakpointData(
                    breakpointUuid = breakpoint.uuid,
                    className = breakpoint.className,
                    fileName = breakpoint.fileName,
                    lineNumber = breakpoint.lineNumber,
                ),
                slot = slot,
                failureKind = failureKind,
                // Never rendered from the thrown object: its getMessage() and toString() are application code.
                message = "",
                disabled = disabled,
            )
            notificationListener.get()?.invoke(notification)
        }
    }

    /** Guard ensuring the breakpoint failure callbacks are registered exactly once. */
    private val breakpointFailureCallbacksInstalled = AtomicBoolean(false)

    /** Registers policy-block and expression-compilation callbacks before class transformation begins. */
    fun ensureBreakpointFailureCallbacksInstalled() {
        if (!breakpointFailureCallbacksInstalled.compareAndSet(false, true)) return

        BreakpointStorage.setOnBreakpointBlocked { _, userData, reason ->
            onBreakpointBlocked(userData as SnapshotBreakpoint, reason as String)
        }
        BreakpointStorage.setOnBreakpointExpressionCompilationFailed { _, userData, message ->
            onBreakpointExpressionCompilationFailed(userData as SnapshotBreakpoint, message as String)
        }
        Logger.debug { "Breakpoint failure callbacks installed" }
    }

    private fun onBreakpointBlocked(breakpoint: SnapshotBreakpoint, reason: String) {
        Logger.info {
            with(breakpoint) { "Breakpoint in $className at $fileName:$lineNumber blocked: $reason" }
        }
        notifyBreakpointBlocked(breakpoint, reason)
    }

    private fun onBreakpointExpressionCompilationFailed(breakpoint: SnapshotBreakpoint, message: String) {
        if (!compilationFailedNotified.add("${breakpoint.uuid}|${breakpoint.className}")) return

        val timestamp = System.currentTimeMillis()
        notificationsExecutor.submit {
            val notification = LiveDebuggerNotification.BreakpointExpressionCompilationFailed(
                timestamp = timestamp,
                breakpointData = LiveDebuggerNotification.BreakpointData(
                    breakpointUuid = breakpoint.uuid,
                    className = breakpoint.className,
                    fileName = breakpoint.fileName,
                    lineNumber = breakpoint.lineNumber,
                ),
                compilationFailureMessage = message,
            )
            notificationListener.get()?.invoke(notification)
        }
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
