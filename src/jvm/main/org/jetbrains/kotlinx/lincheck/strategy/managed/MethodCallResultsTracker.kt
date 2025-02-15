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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private typealias DeterministicCallData = Any

internal class NativeMethodCallStatesTracker : AbstractTraceDebuggerEventTracker {
    private var currentId = AtomicLong(0)
    private val callData = ConcurrentHashMap<Id, DeterministicCallData>()
    private val idAdvances = ConcurrentHashMap<Id, Id>()
    private val logging = false

    fun getStateOrNull(id: Id): DeterministicCallData? {
        val retrievedData = callData[id]
        if (logging) println("getStateOrNull: $id -> $retrievedData")
        return retrievedData
    }
    
    fun setState(id: Id, state: Any) {
        val oldState = callData.put(id, state)
        if (logging) println("setState: $id -> $state")
        if (oldState != null) error("Duplicate call id $id: $oldState -> $state")
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
