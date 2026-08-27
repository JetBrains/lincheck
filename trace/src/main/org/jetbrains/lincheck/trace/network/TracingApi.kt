/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.network

import org.jetbrains.lincheck.settings.BreakpointExpressionSlot
import org.jetbrains.lincheck.settings.SensitiveAreaBlocklist
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.trace.RUNTIME_JVM
import org.jetbrains.lincheck.trace.serialization.NetworkTraceReader
import org.jetbrains.lincheck.trace.serialization.TRACE_VERSION
import java.io.Closeable
import java.util.UUID

/**
 * Version of the agent-client wire protocol implemented by this module.
 *
 * Advertised in [AgentHelloMessage.protocolVersion] so the client can gate protocol-dependent behavior.
 * Bump it when the framing or the set of commands and notifications grows.
 *
 * A bump must stay additive. The hello travels agent to client only, so there is no channel on which to
 * negotiate a version down, and a receiver ignores frames it does not recognize: a participant meets a
 * higher-versioned peer by keeping to the semantics it knows, which only works while the newer version
 * still understands them.
 */
const val PROTOCOL_VERSION: Int = 1

/**
 * Agent self-identification, sent by the agent to the client as the first frame after a
 * connection is established (see [ConnectedAware.onConnectionReady]).
 *
 * Lets the client learn who it is talking to before any command is exchanged — the wire-protocol
 * version, the runtime and its version, and the agent build — which downstream drives
 * version- and bytecode-compatibility checks on connection.
 *
 * The message is intentionally **extensible**: on the wire it is an order-independent set of
 * `key=value` [attributes], so new information can be added without breaking peers built against
 * older versions. A receiver simply ignores keys it does not recognize, and any attribute this
 * class has no typed field for is preserved verbatim in [attributes]. Keys and values must not
 * contain the `;` or `=` delimiters.
 *
 * @property protocolVersion wire-protocol version the agent implements; see [PROTOCOL_VERSION].
 * @property runtime runtime the agent runs in, e.g. [RUNTIME_JVM].
 * @property runtimeVersion version of that runtime (e.g. the JRE version or others); free-form.
 * @property agentVersion version of the agent build; free-form.
 * @property timestamp agent-side wall-clock time (`System.currentTimeMillis()`) at which the hello was sent;
 *   travels in the frame's timestamp slot like every other notification, not in [encodeToPayload].
 * @property traceVersion binary trace-format version the agent streams,
 *   or `null` for a runtime that does not emit this trace format.
 * @property attributes additional, forward-compatible attributes, including unrecognized wire keys.
 */
data class AgentHelloMessage(
    val protocolVersion: Int,
    val runtime: String,
    val runtimeVersion: String,
    val agentVersion: String,
    val timestamp: Long,
    val traceVersion: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    /**
     * Encodes this hello as the wire payload — a `;`-separated list of `key=value` pairs, with the
     * well-known keys first (in declaration order) followed by [attributes] sorted by key.
     * Inverse of [decodeFromPayload]; [timestamp] is carried by the frame header instead.
     */
    fun encodeToPayload(): String {
        val pairs = LinkedHashMap<String, String>()
        pairs[KEY_PROTOCOL] = protocolVersion.toString()
        pairs[KEY_RUNTIME] = runtime
        pairs[KEY_RUNTIME_VERSION] = runtimeVersion
        pairs[KEY_AGENT_VERSION] = agentVersion
        traceVersion?.let { pairs[KEY_TRACE_VERSION] = it.toString() }
        // Never let an extra attribute shadow a well-known key.
        attributes.toSortedMap().forEach { (key, value) -> if (key !in RESERVED_KEYS) pairs[key] = value }
        return pairs.entries.joinToString(";") { (key, value) -> "$key=$value" }
    }

    /** Optional protocol capabilities advertised via the [KEY_CAPABILITIES] attribute (comma-separated). */
    val capabilities: Set<String>
        get() = attributes[KEY_CAPABILITIES]?.split(',')?.filterTo(mutableSetOf()) { it.isNotEmpty() } ?: emptySet()

    /**
     * Whether the agent's framing covers this build's, which holds from [PROTOCOL_VERSION] upwards
     * because a bump is additive: a newer agent still answers the commands this build sends,
     * and the notifications it adds are ignored as unrecognized.
     */
    val speaksCompatibleProtocol: Boolean
        get() = protocolVersion >= PROTOCOL_VERSION

    /** Whether the agent's binary trace reads against this build; `false` when it streams none. */
    val streamsCompatibleTraceFormat: Boolean
        get() = traceVersion == TRACE_VERSION

    /** Whether the agent redacts captured values before they leave the target process. */
    val supportsRedactionV1: Boolean
        get() = CAPABILITY_REDACTION_V1 in capabilities

    companion object {
        const val KEY_PROTOCOL: String = "protocol"
        const val KEY_RUNTIME: String = "runtime"
        const val KEY_RUNTIME_VERSION: String = "runtimeVersion"
        const val KEY_AGENT_VERSION: String = "agentVersion"
        const val KEY_TRACE_VERSION: String = "traceVersion"

        /** Attribute key naming optional protocol features the sender implements, comma-separated. */
        const val KEY_CAPABILITIES: String = "capabilities"

        /** Capture-time data redaction: the agent understands and enforces redaction policies. */
        const val CAPABILITY_REDACTION_V1: String = "REDACTION_V1"

        private val RESERVED_KEYS =
            setOf(KEY_PROTOCOL, KEY_RUNTIME, KEY_RUNTIME_VERSION, KEY_AGENT_VERSION, KEY_TRACE_VERSION)

        /**
         * Parses a wire payload produced by [encodeToPayload], pairing it with the [timestamp] read off the frame.
         *
         * Returns `null` when a required well-known key is missing or [KEY_PROTOCOL] is not an integer;
         * unrecognized keys land in [AgentHelloMessage.attributes], so a payload from a newer agent still parses.
         * The optional [KEY_TRACE_VERSION] decodes to `null` when absent or not a long.
         */
        fun decodeFromPayload(payload: String, timestamp: Long): AgentHelloMessage? {
            val pairs = HashMap<String, String>()
            for (token in payload.split(";")) {
                if (token.isEmpty()) continue
                val eq = token.indexOf('=')
                if (eq < 0) continue
                pairs[token.substring(0, eq)] = token.substring(eq + 1)
            }
            val protocolVersion = pairs[KEY_PROTOCOL]?.toIntOrNull() ?: return null
            val runtime = pairs[KEY_RUNTIME] ?: return null
            val runtimeVersion = pairs[KEY_RUNTIME_VERSION] ?: return null
            val agentVersion = pairs[KEY_AGENT_VERSION] ?: return null
            val traceVersion = pairs[KEY_TRACE_VERSION]?.toLongOrNull()
            val attributes = pairs.filterKeys { it !in RESERVED_KEYS }
            return AgentHelloMessage(
                protocolVersion, runtime, runtimeVersion, agentVersion, timestamp, traceVersion, attributes
            )
        }
    }
}

/**
 * Interface for receiving notifications from the tracing server.
 * This is implemented by the client (controller).
 */
// Agent -> Control plane
interface TracingCallbacks : Closeable {

    /**
     * Agent handshake, sent once as the first frame after the connection opens; see [AgentHelloMessage].
     * Default no-op so peers that neither send nor consume it keep working unchanged.
     */
    fun hello(hello: AgentHelloMessage) {}

    fun hitLimitReached(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        timestamp: Long
    )

    fun breakpointExpressionUnsafe(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        slot: BreakpointExpressionSlot,
        safetyViolationMessage: String,
        timestamp: Long
    )

    fun breakpointBlocked(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        reason: String,
        timestamp: Long
    )

    /**
     * A hit was suppressed by dynamic-extent enforcement: the call stack passed through the blocked
     * [blockedFrameClass]. The breakpoint itself stays valid — it still fires on clean call paths.
     */
    fun breakpointHitSuppressed(
        breakpointData: LiveDebuggerNotification.BreakpointData,
        blockedFrameClass: String,
        reason: String,
        timestamp: Long
    )

    fun binaryTraceData(data: ByteArray)

    companion object {
        internal const val HELLO = "hello"
        internal const val HIT_LIMIT_REACHED = "hitLimitReached"
        internal const val BREAKPOINT_EXPRESSION_UNSAFE = "breakpointExpressionUnsafe"
        internal const val BREAKPOINT_BLOCKED = "breakpointBlocked"
        internal const val BREAKPOINT_HIT_SUPPRESSED = "breakpointHitSuppressed"
    }
}

/**
 * Interface for sending commands to the tracing server.
 * This is implemented by the server (agent) or a client-side proxy.
 */
// Control Plane -> Agent
interface TracingCommands {
    fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean)
    fun startNetworkTracing()
    fun stopTracing()

    fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>)
    fun removeBreakpoints(uuids: List<UUID>)

    /**
     * Adds the given blocklists to the active policy. Add-only: this can only ever *add*
     * restrictions; idempotent per content-identity uuid, so a re-push after reconnect is a no-op.
     */
    fun addSensitiveAreaBlocklists(blocklists: List<SensitiveAreaBlocklist>)

    companion object {
        internal const val START_FILE_TRACING = "startFileTracing"
        internal const val START_NETWORK_TRACING = "startNetworkTracing"
        internal const val STOP_TRACING = "stopTracing"
        internal const val ADD_BREAKPOINTS = "addBreakpoints"
        internal const val REMOVE_BREAKPOINTS = "removeBreakpoints"
        internal const val ADD_SENSITIVE_AREA_BLOCKLISTS = "addSensitiveAreaBlocklists"
    }
}


/**
 * Interface for components that need to be notified when a connection is opened.
 */
interface ConnectedAware {
    fun onConnectionReady() {}
    fun onDisconnected() {}
}

/**
 * A tracing client that can send commands to the server, receive notifications, and read binary trace data.
 */
// Agent -> Control plane
interface TracingClient: TracingCallbacks, ConnectedAware {
    val connection: TracingCommands
    val networkTraceReader: NetworkTraceReader
}

/**
 * A tracing server that accepts commands and sends notifications to the connected client.
 */
// Control Plane -> Agent
interface TracingServer: TracingCommands, ConnectedAware, Closeable {
    val connection: TracingCallbacks
}