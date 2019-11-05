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
import java.util.concurrent.locks.*
import kotlin.coroutines.*

open class SequentialIntChannel(private val capacity: Int) : VerifierState() {
    private val lock = ReentrantLock()
    private val senders   = ArrayList<Pair<CancellableContinuation<Unit>, Int>>()
    private val receivers = ArrayList<CancellableContinuation<Int>>()
    private val buffer = ArrayList<Int>(capacity)
    private var closed = false

    @InternalCoroutinesApi
    suspend fun send(x: Int) {
        lock.lock()
        try {
            if (offer(x)) return
            suspendAtomicCancellableCoroutine<Unit> { cont ->
                senders.add(cont to x)
            }
        } finally {
            lock.unlock()
        }
    }

    @InternalCoroutinesApi
    fun offer(x: Int): Boolean {
        lock.lock()
        try {
            while (true) {
                if (closed) throw ClosedSendChannelException("")
                if (receivers.isEmpty() && buffer.size == capacity) return false
                if (receivers.isNotEmpty()) {
                    val r = receivers.removeAt(0)
                    if (r.tryResume0(x)) return true
                } else {
                    buffer.add(x)
                    return true
                }
            }
        } finally {
            lock.unlock()
        }
    }

    @InternalCoroutinesApi
    suspend fun receive(): Int {
        lock.lock()
        try {
            val pollResult = poll()
            if (pollResult !== null) return pollResult
            return suspendAtomicCancellableCoroutine { cont ->
                receivers.add(cont)
            }
        } finally {
            lock.unlock()
        }
    }

    @InternalCoroutinesApi
    suspend fun receiveOrNull(): Int? {
        lock.lock()
        try {
            if (senders.isEmpty() && closed) return null
            val pollResult = poll()
            if (pollResult !== null) return pollResult
            return suspendAtomicCancellableCoroutine { cont ->
                receivers.add(cont)
            }
        } finally {
            lock.unlock()
        }
    }

    @InternalCoroutinesApi
    fun poll(): Int? {
        lock.lock()
        try {
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
                if (senders.isEmpty() && closed) throw ClosedReceiveChannelException("")
                if (senders.isEmpty()) return null
                val (s, x) = senders.removeAt(0)
                if (s.tryResume0(Unit)) return x
            }
        } finally {
            lock.unlock()
        }
    }

    fun close(): Boolean {
        if (closed) return false
        closed = true
        receivers.forEach {
            it.resumeWithException(ClosedReceiveChannelException(""))
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
