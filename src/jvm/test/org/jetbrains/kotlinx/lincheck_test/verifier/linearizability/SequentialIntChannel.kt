/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.verifier.linearizability

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

@InternalCoroutinesApi
open class SequentialIntChannel(private val capacity: Int) {
    private val senders   = ArrayList<Pair<CancellableContinuation<Unit>, Int>>()
    private val receivers = ArrayList<CancellableContinuation<Any>>()
    private val buffer = ArrayList<Int>()
    private var closed = false

    suspend fun send(x: Int) {
        if (offer(x)) return
        suspendCancellableCoroutine<Unit> { cont ->
            senders.add(cont to x)
        }
    }

    fun offer(x: Int): Boolean {
        while (true) {
            if (closed) throw ClosedSendChannelException("")
            if (receivers.isNotEmpty()) {
                val r = receivers.removeAt(0)
                if (r.tryResume0(x)) return true
            } else {
                if (buffer.size == capacity) return false
                buffer.add(x)
                return true
            }
        }
    }

    suspend fun receive(): Int {
        val res = receiveImpl()
        if (res === CLOSED) throw ClosedReceiveChannelException("")
        return res as Int
    }

    @InternalCoroutinesApi
    suspend fun receiveOrNull(): Int? {
        val res = receiveImpl()
        return if (res === CLOSED) null
               else res as Int
    }

    suspend fun receiveImpl(): Any {
        if (buffer.isEmpty() && senders.isEmpty() && closed) return CLOSED
        val pollResult = poll()
        if (pollResult !== null) return pollResult
        return suspendCancellableCoroutine { cont ->
            receivers.add(cont)
        }
    }

    fun poll(): Int? {
        if (buffer.size > 0) {
            val res = buffer.removeAt(0)
            while (true) {
                if (senders.isEmpty()) break
                val (s, x) = senders.removeAt(0)
                if (s.tryResume0(Unit)) {
                    buffer.add(x)
                    break
                }
            }
            return res
        }
        while (true) {
            if (senders.isEmpty()) return null
            val (s, x) = senders.removeAt(0)
            if (s.tryResume0(Unit)) return x
        }
    }

    fun close(): Boolean {
        if (closed) return false
        closed = true
        receivers.forEach {
            it.tryResume0(CLOSED)
        }
        receivers.clear()
        return true
    }
}

@InternalCoroutinesApi
private fun <T> CancellableContinuation<T>.tryResume0(res: T): Boolean {
    val token = tryResume(res) ?: return false
    completeResume(token)
    return true
}

private val CLOSED = Any()