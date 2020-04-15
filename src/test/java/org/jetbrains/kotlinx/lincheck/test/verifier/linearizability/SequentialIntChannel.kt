/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.jetbrains.kotlinx.lincheck.verifier.*

@InternalCoroutinesApi
open class SequentialIntChannel(private val capacity: Int) : VerifierState() {
    private val senders   = ArrayList<Pair<CancellableContinuation<Unit>, Int>>()
    private val receivers = ArrayList<CancellableContinuation<Any>>()
    private val buffer = ArrayList<Int>()
    private var closed = false

    suspend fun send(x: Int) {
        if (offer(x)) return
        suspendAtomicCancellableCoroutine<Unit> { cont ->
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
        return suspendAtomicCancellableCoroutine { cont ->
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

    override fun extractState() = buffer to closed
}

@InternalCoroutinesApi
private fun <T> CancellableContinuation<T>.tryResume0(res: T): Boolean {
    val token = tryResume(res) ?: return false
    completeResume(token)
    return true
}

private val CLOSED = Any()