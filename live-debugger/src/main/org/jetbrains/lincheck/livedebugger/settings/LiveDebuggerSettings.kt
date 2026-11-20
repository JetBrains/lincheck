/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.livedebugger.settings

import org.jetbrains.lincheck.settings.*
import org.jetbrains.lincheck.settings.privacy.BlockMatch
import org.jetbrains.lincheck.settings.privacy.BlocklistEngine
import org.jetbrains.lincheck.settings.privacy.DynamicExtentChecker
import org.jetbrains.lincheck.settings.privacy.RedactionPolicy
import org.jetbrains.lincheck.settings.privacy.RedactionTemplateRegistry
import org.jetbrains.lincheck.settings.privacy.SensitiveAreaBlocklistRegistry
import org.jetbrains.lincheck.util.Logger
import sun.nio.ch.lincheck.BreakpointStorage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A breakpoint rejected at registration time by a sensitive-area blocklist, with the matching rule. */
class RejectedBreakpoint(val breakpoint: SnapshotBreakpoint, val match: BlockMatch)

/** Outcome of [LiveDebuggerSettings.addBreakpoints]: the breakpoints registered and the ones rejected. */
class AddBreakpointsResult(
    val added: List<SnapshotBreakpoint>,
    val rejected: List<RejectedBreakpoint>,
)

/**
 * Outcome of [LiveDebuggerSettings.removeBreakpoints] / [LiveDebuggerSettings.removeAllBreakpoints]:
 * the breakpoints removed and the requested UUIDs that matched no registered breakpoint.
 */
class RemoveBreakpointsResult(
    val removed: List<SnapshotBreakpoint>,
    val notFound: List<UUID>,
)

/** Shared live debugger settings used by instrumentation and snapshot capture. */
val liveDebuggerSettings = LiveDebuggerSettings()

class LiveDebuggerSettings(lineBreakpoints: List<SnapshotBreakpoint> = emptyList()) : BreakpointsConfiguration {

    override fun firstBlockedFrame(stackTrace: List<StackTraceElement>) =
        dynamicExtentChecker.firstBlockedFrame(stackTrace)

    override val redactionPolicy: RedactionPolicy
        get() = redactionRegistry.snapshot()

    /**
     * Active sensitive-area blocklists. Registration (Stage 1) rejects breakpoints that fall into a
     * statically-decidable blocked area; the instrumentation stage remains authoritative.
     */
    val blocklistRegistry = SensitiveAreaBlocklistRegistry()

    /** Authoritative sensitive-area blocklist matcher, backed by [blocklistRegistry]. */
    override val blocklistEngine = BlocklistEngine(blocklistRegistry)

    /** Dynamic-extent checker over [blocklistEngine]; consulted on the capture hot path. */
    val dynamicExtentChecker = DynamicExtentChecker(blocklistEngine)

    /** Active capture-time data-redaction templates, partitioned by policy owner. */
    val redactionRegistry = RedactionTemplateRegistry()

    /**
     * Hot-path fail-closed guard for required policy loading.
     *
     * This becomes false before an invalid or pending required source is processed, so already
     * injected hooks cannot capture while their registered breakpoints are being removed.
     */
    @Volatile
    override var requiredRedactionPolicyValid: Boolean = true

    /**
     * Source of unique [BreakpointId] handles assigned at registration time.
     */
    private val nextBreakpointId = AtomicInteger(0)

    /**
     * A concurrent map that stores the currently registered breakpoints in the debugger settings.
     */
    private val _lineBreakpoints: ConcurrentHashMap<BreakpointId, SnapshotBreakpoint> =
        ConcurrentHashMap<BreakpointId, SnapshotBreakpoint>().apply {
            for (breakpoint in lineBreakpoints) {
                put(nextBreakpointId.getAndIncrement(), breakpoint)
            }
        }

    /**
     * A read-only map that stores the currently registered breakpoints in the debugger settings.
     */
    override val lineBreakpoints: Map<BreakpointId, SnapshotBreakpoint>
        get() = _lineBreakpoints

    /**
     * Registers each breakpoint, skipping any whose [SnapshotBreakpoint.uuid] is already present.
     * Same-line breakpoints with different UUIDs are deliberately treated as distinct.
     *
     * **Stage 1 blocklist rejection.** A breakpoint whose class falls into a statically-decidable
     * blocked area (package rules, class-name / generated-descendant rules) is *not* registered and is
     * returned in [AddBreakpointsResult.rejected]. Per-method rules are not decidable here
     * (the enclosing method is unknown until instrumentation),
     * so passing Stage 1 is not approval — the instrumentation stage is authoritative.
     *
     * @return the breakpoints that were actually added and the ones rejected (both in input order).
     */
    fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>): AddBreakpointsResult {
        val addedBreakpoints = mutableListOf<SnapshotBreakpoint>()
        val rejectedBreakpoints = mutableListOf<RejectedBreakpoint>()
        for (breakpoint in breakpoints) {
            val staticMatch = blocklistRegistry.staticBlockMatch(breakpoint.className)
            if (staticMatch != null) {
                Logger.warn { "Breakpoint rejected at registration (${staticMatch.reason}): $breakpoint" }
                rejectedBreakpoints.add(RejectedBreakpoint(breakpoint, staticMatch))
                continue
            }
            // Synchronize the entire check-then-act to prevent two concurrent callers from
            // both passing the duplicate check and registering the same breakpoint twice.
            synchronized(_lineBreakpoints) {
                val duplicate = _lineBreakpoints.values.firstOrNull { it.uuid == breakpoint.uuid }
                if (duplicate != null) {
                    Logger.warn {
                        """
                        Breakpoint with duplicate UUID is detected, skipping duplicate registration:
                            skipped    : $breakpoint
                            registered : $duplicate
                        """
                        .trimIndent()
                    }
                    return@synchronized
                }

                val id = nextBreakpointId.getAndIncrement()
                BreakpointStorage.registerBreakpoint(id, breakpoint.hitLimit, breakpoint)
                _lineBreakpoints[id] = breakpoint
                addedBreakpoints.add(breakpoint)
            }
        }
        return AddBreakpointsResult(addedBreakpoints, rejectedBreakpoints)
    }

    /**
     * Removes the breakpoints with the given UUIDs.
     *
     * @return the breakpoints that were actually removed and the UUIDs that matched nothing
     *   (both in input order).
     */
    fun removeBreakpoints(uuids: List<UUID>): RemoveBreakpointsResult {
        val removedBreakpoints = mutableListOf<SnapshotBreakpoint>()
        val notFoundUuids = mutableListOf<UUID>()
        for (uuid in uuids) {
            // Synchronize the entire find-then-remove to prevent two concurrent callers from
            // both finding the entry and attempting a double removal.
            synchronized(_lineBreakpoints) {
                val entry = _lineBreakpoints.entries.firstOrNull { it.value.uuid == uuid }
                if (entry == null) {
                    notFoundUuids.add(uuid)
                    return@synchronized
                }
                _lineBreakpoints.remove(entry.key)
                BreakpointStorage.removeBreakpoint(entry.key)
                removedBreakpoints.add(entry.value)
            }
        }
        return RemoveBreakpointsResult(removedBreakpoints, notFoundUuids)
    }

    /**
     * Removes all registered breakpoints.
     *
     * @return the breakpoints that were removed.
     */
    fun removeAllBreakpoints(): RemoveBreakpointsResult {
        synchronized(_lineBreakpoints) {
            val removed = _lineBreakpoints.values.toList()
            for (id in _lineBreakpoints.keys) {
                BreakpointStorage.removeBreakpoint(id)
            }
            _lineBreakpoints.clear()
            return RemoveBreakpointsResult(removed, notFound = emptyList())
        }
    }

    /**
     * Removes the breakpoint with the given internal [id] handle.
     *
     * Unlike [removeBreakpoints] (which matches by [SnapshotBreakpoint.uuid]),
     * this matches by the internal [BreakpointId].
     *
     * Important for the hit-limit path (and other re-enable after notification cases):
     * if the user re-adds a breakpoint after the hit-limit fires, it will receive a new internal id;
     * so this method will correctly find *nothing* (the old id is no longer in the map)
     * rather than accidentally removing the freshly re-added breakpoint.
     *
     * @return the removed [SnapshotBreakpoint], or `null` if no breakpoint with that id exists.
     */
    fun removeBreakpoint(id: BreakpointId): SnapshotBreakpoint? {
        synchronized(_lineBreakpoints) {
            val breakpoint = _lineBreakpoints.remove(id)
            BreakpointStorage.removeBreakpoint(id)
            return breakpoint
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiveDebuggerSettings) return false

        return (lineBreakpoints == other.lineBreakpoints)
    }

    override fun hashCode(): Int {
        return lineBreakpoints.hashCode()
    }

}
