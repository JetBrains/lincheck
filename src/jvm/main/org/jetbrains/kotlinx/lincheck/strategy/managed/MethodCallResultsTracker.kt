/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.util.Logger
import org.jetbrains.kotlinx.lincheck.strategy.nativecalls.MethodCallInfo
import org.jetbrains.kotlinx.lincheck.strategy.nativecalls.ReplayableMutableInstance
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private typealias DeterministicCallState = Any?

private data class DeterministicMethodCallInstantiationData(
    val methodCallInfo: MethodCallInfo,
    val invocationData: DeterministicCallState
)

/**
 * Tracks and manages states of native method calls during trace debugging.
 * This class ensures consistent association of method call states with unique identifiers,
 * providing functionalities to retrieve and add these states.
 *
 * Implements `AbstractTraceDebuggerEventTracker` to handle trace debugger event IDs, ensuring correct
 * mapping and progression of IDs across multiple runs.
 */
internal class NativeMethodCallStatesTracker : AbstractTraceDebuggerEventTracker {
    private var currentId = AtomicLong(0)
    private val callData = ConcurrentHashMap<TraceDebuggerEventId, DeterministicMethodCallInstantiationData>()
    private val idAdvances = ConcurrentHashMap<TraceDebuggerEventId, TraceDebuggerEventId>()
    private var isReplaying = false

    /**
     * Retrieves the state associated with the given identifier or throws an error if no state is found.
     *
     * @param id The identifier for which the state should be retrieved.
     * @return The state associated with the given identifier, or error if no state exists.
     */
    fun getState(id: TraceDebuggerEventId, methodCallInfo: MethodCallInfo): DeterministicCallState {
        val methodCallAndRetrievedData = callData[id]
        Logger.debug { "Getting deterministic method call state: $id -> $methodCallAndRetrievedData" }
        if (methodCallAndRetrievedData == null) error("No state for id $id and method call $methodCallInfo")
        val (oldMethodCallInfo, retrievedData) = methodCallAndRetrievedData
        require(oldMethodCallInfo == methodCallInfo) { 
            "Wrong method call info: $oldMethodCallInfo != $methodCallInfo"
        }
        return retrievedData
    }
    
    /**
     * Updates the state associated with the given identifier. If a state already exists for the identifier,
     * an error is raised. Optionally logs the state update if logging is enabled.
     *
     * @param id The identifier for which the state should be updated.
     * @param state The new state to associate with the provided identifier.
     * @throws IllegalStateException if a state is already associated with the given identifier.
     */
    fun setState(id: TraceDebuggerEventId, methodCallInfo: MethodCallInfo, state: DeterministicCallState) {
        val methodCallAndState = DeterministicMethodCallInstantiationData(methodCallInfo, state)
        val oldState = callData.put(id, methodCallAndState)
        Logger.debug { "Saving deterministic method call state: $id -> $methodCallAndState" }
        if (oldState != null) error("Duplicate call id $id: $oldState -> $methodCallAndState")
    }

    override fun resetIds() {
        currentId.set(0)
        if (!isReplaying) {
            for (callData in callData.values) {
                when (val state = callData.invocationData) {
                    is ReplayableMutableInstance -> state.setToReplayMode()
                    is Result<*> -> (state.getOrNull() as? ReplayableMutableInstance)?.setToReplayMode()
                }
            }
            isReplaying = true
        }
    }

    override fun getNextId(): TraceDebuggerEventId {
        val result = currentId.getAndIncrement()
        Logger.debug { "Getting and incrementing deterministic method id: $result" }
        return result
    }

    override fun advanceCurrentId(oldId: TraceDebuggerEventId) {
        val newId = currentId.get()
        val existingAdvance = idAdvances.putIfAbsent(oldId, newId)
        if (existingAdvance != null) {
            currentId.set(existingAdvance)
        }
    }
    
    override fun close() {
        callData.clear()
        idAdvances.clear()
    }
}
