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

import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy.MethodCallInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private typealias DeterministicCallData = Any

internal class NativeMethodCallStatesTracker : AbstractTraceDebuggerEventTracker {
    private var currentId = AtomicLong(0)
    private val callData = ConcurrentHashMap<Id, Pair<MethodCallInfo, DeterministicCallData>>()
    private val idAdvances = ConcurrentHashMap<Id, Id>()
    private val logging = false

    /**
     * Retrieves the state associated with the given identifier or returns null if no state is found.
     *
     * @param id The identifier for which the state should be retrieved.
     * @return The state associated with the given identifier, or null if no state exists.
     */
    fun getStateOrNull(id: Id, methodCallInfo: MethodCallInfo): DeterministicCallData? {
        val methodCallAndRetrievedData = callData[id]
        if (logging) println("getStateOrNull: $id -> $methodCallAndRetrievedData")
        if (methodCallAndRetrievedData == null) return null
        val (oldMethodCallInfo, retrievedData) = methodCallAndRetrievedData
        require(oldMethodCallInfo == methodCallInfo) { "Wrong method call info: $oldMethodCallInfo != $methodCallInfo" }
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
    fun setState(id: Id, state: DeterministicCallData, methodCallInfo: MethodCallInfo) {
        val methodCallAndState = methodCallInfo to state
        val oldState = callData.put(id, methodCallAndState)
        if (logging) println("setState: $id -> $methodCallAndState")
        if (oldState != null) error("Duplicate call id $id: $oldState -> $methodCallAndState")
    }

    override fun resetIds() {
        currentId.set(0)
    }

    override fun getNextId(): Id = currentId.getAndIncrement()
    override fun advanceCurrentId(oldId: Id) {
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
