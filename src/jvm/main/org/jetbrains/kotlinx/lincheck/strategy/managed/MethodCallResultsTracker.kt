/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MethodCallResultsTracker {
    private val currentId = AtomicLong(0)
    private val results = ConcurrentHashMap<Long, Pair<Long, Result<Any?>>>()
    
    fun storeNewResult(result: Result<Any?>, id: Long) {
        val newId = currentId.incrementAndGet()
        val previous = results.put(id, newId to result)
        require(previous == null) {
            "Duplicate call result: $result for id=${id..newId}. The previous result was $previous"
        }
    }
    
    fun getNextResult(): Result<Any?> {
        val index = currentId.get()
        val (newId, result) = results[index] ?: error("No result for id=$currentId")
        currentId.set(newId)
        return result 
    }
    
    fun resetCounter() {
        currentId.set(0)
    }

    fun nextId(): Long {
        return currentId.getAndIncrement()
    }
}
