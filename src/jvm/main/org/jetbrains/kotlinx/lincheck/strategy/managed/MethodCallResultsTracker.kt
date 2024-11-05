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

interface MethodCallResultsTracker {
    fun storeNewResult(result: Result<Any?>, id: Long, threadId: Long)
    fun getNextResult(threadId: Long): Result<Any?>
    fun startReplaying()
    fun currentId(threadId: Long): Long
    fun nextId(threadId: Long): Long
    fun resetStateToId(id: Long = 0)
}

class ThreadLocalMethodCallResultsTracker : MethodCallResultsTracker {
    private val threadLocalMethodResultsTracker = ConcurrentHashMap<Long, SingleThreadMethodCallResultsTracker>()
    private fun getCurrentThreadTracker(threadId: Long): SingleThreadMethodCallResultsTracker {
        return threadLocalMethodResultsTracker.computeIfAbsent(threadId) { SingleThreadMethodCallResultsTracker() }
    }

    override fun storeNewResult(result: Result<Any?>, id: Long, threadId: Long) = getCurrentThreadTracker(threadId).storeNewResult(result, id)

    override fun getNextResult(threadId: Long): Result<Any?> = getCurrentThreadTracker(threadId).getNextResult()
    override fun currentId(threadId: Long): Long = getCurrentThreadTracker(threadId).currentId()
    override fun nextId(threadId: Long): Long = getCurrentThreadTracker(threadId).nextId()
    override fun startReplaying() {
        threadLocalMethodResultsTracker.values.forEach { it.startReplaying() }
    }

    override fun resetStateToId(id: Long) {
        threadLocalMethodResultsTracker.values.forEach { it.resetStateToId(id) }
    }
}

private class SingleThreadMethodCallResultsTracker {
    private var currentId = 0L
    private val results = HashMap<Long, Pair<Long, Result<Any?>>>()
    
    fun storeNewResult(result: Result<Any?>, id: Long) {
        val newId = currentId++
        val previous = results.put(id, newId to result)
        require(previous == null) {
            "Duplicate call result: $result for id=${id..newId}. The previous result was $previous"
        }
    }
    
    fun getNextResult(): Result<Any?> {
        val index = currentId
        val (newId, result) = results[index] ?: error("No result for id=$currentId")
        currentId = newId + 1
        return result 
    }
    
    fun startReplaying() {
        currentId = 0
    }

    fun nextId(): Long = currentId++
    fun currentId(): Long = currentId

    fun resetStateToId(id: Long) {
        currentId = id
        results.entries.filter { it.key >= id || it.value.first >= id }.forEach { results.remove(it.key) }
    }
}


private const val log = false
class ConcurrentMethodCallResultsTracker : MethodCallResultsTracker {
    private var currentId = AtomicLong(0)
    private val results = ConcurrentHashMap<Long, Pair<Long, Result<Any?>>>()

    override fun storeNewResult(result: Result<Any?>, id: Long, threadId: Long) {
        if (log) println("set $threadId:$id..$currentId $result")
        val newId = currentId.getAndIncrement()
        val previous = results.put(id, newId to result)
        require(previous == null) {
            "Duplicate call result: $result for id=${id..newId}. The previous result was $previous"
        }
    }

    override fun getNextResult(threadId: Long): Result<Any?> {
        fun hang(): Nothing {
            if (log) println("get $threadId:$currentId..+∞: hang")
            while (true) {}
        }
        
        val index = currentId.get()
        val (newId, result) = results[index] ?: hang() // error("No result for id=$currentId")
        if (log) println("get $threadId:$currentId..$newId: $result")
        currentId.set(newId + 1)
        return result 
    }

    override fun startReplaying() { // get ready to replaying
        currentId.set(0)
    }

    override fun nextId(threadId: Long): Long = currentId.getAndIncrement()
    override fun currentId(threadId: Long): Long = currentId.get()

    override fun resetStateToId(id: Long) { // get ready to rewriting
        currentId.set(id)
        results.entries.filter { it.key >= id || it.value.first >= id }.forEach { results.remove(it.key) }
    }
}
