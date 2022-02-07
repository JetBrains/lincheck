/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.distributed

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.distributed.event.EventFactory
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Implementation of the environment.
 */
class Environment<Message> internal constructor(
    val id: Int,
    val nodes: Int,
    private val eventFactory: EventFactory<Message>,
    private val strategy: DistributedStrategy<Message>,
    private val taskManager: TaskManager,
) {
    internal fun beforeDatabaseAccess() = strategy.beforeDatabaseAccess(id)

    private val timers = mutableSetOf<String>()

    inline fun <reified NodeType : Node<Message>> getAddresses(): List<Int> {
        return getAddressesForClass(NodeType::class.java)
    }

    //TODO: make private
    fun getAddressesForClass(cls: Class<out Node<Message>>): List<Int> =
        strategy.testCfg.addressResolver[cls]

    fun send(message: Message, receiver: Int) {
        val e = eventFactory.createMessageEvent(message, id, receiver)
        val rate = strategy.crashOrReturnRate(id, receiver, e.id)
        repeat(rate) {
            taskManager.addMessageReceiveTask(
                to = receiver,
                from = id
            ) {
                eventFactory.createMessageReceiveEvent(e)
                //TODO: better way to access node instances
                eventFactory.nodeInstances[receiver].onMessage(message, id)
            }
        }
        if (rate > 0) strategy.onMessageSent(id, receiver, e.id)
    }

    /**
     * Sends the specified [message] to all processes (from 0 to [nodes]).
     * If [skipItself] is true, doesn't send the message to itself.
     */
    fun broadcast(message: Message, skipItself: Boolean = true) {
        for (i in 0 until nodes) {
            if (i == id && skipItself) {
                continue
            }
            send(message, i)
        }
    }

    inline fun <reified NodeType : Node<Message>> broadcastToGroup(message: Message, skipItself: Boolean = true) {
        broadcastToGroup(message, NodeType::class.java, skipItself)
    }

    fun broadcastToGroup(message: Message, cls: Class<out Node<Message>>, skipItself: Boolean) {
        for (i in getAddressesForClass(cls)) {
            if (i == id && skipItself) continue
            send(message, i)
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    suspend fun <T> withTimeout(ticks: Int, block: suspend CoroutineScope.() -> T): T? {
        if (ticks <= 0) return null
        var coroutine: TimeoutCoroutine<T?, T?>? = null
        try {
            return suspendCoroutineUninterceptedOrReturn { uCont ->
                val timeoutCoroutine = TimeoutCoroutine(ticks, uCont)
                coroutine = timeoutCoroutine
                val task = taskManager.addTimeout(id, ticks) {
                    timeoutCoroutine.run()
                }
                val handle = DisposableHandle {
                    taskManager.removeTask(task)
                }
                setupTimeout<T?, T?>(timeoutCoroutine, block, handle)
            }
        } catch (e: TimeoutCancellationException) {
            // Return null if it's our exception, otherwise propagate it upstream (e.g. in case of nested withTimeouts)
            if (e.coroutine === coroutine) {
                return null
            }
            throw e
        }
    }

    suspend fun sleep(ticks: Int) {
        suspendCancellableCoroutine<Unit> { cont ->
            taskManager.addTimeout(
                ticks = ticks,
                iNode = id
            ) {
                cont.resumeWith(Result.success(Unit))
            }
        }
    }

    private fun timerTick(name: String, ticks: Int, f: () -> Unit) {
        if (name !in timers) return
        eventFactory.createTimerTickEvent(name, id)
        f()
        taskManager.addTimer(
            ticks = ticks,
            iNode = id
        ) {
            timerTick(name, ticks, f)
        }
    }

    fun setTimer(name: String, ticks: Int, f: () -> Unit) {
        if (name in timers) {
            throw IllegalArgumentException("Timer with name $name already exists")
        }
        eventFactory.createSetTimerEvent(id, name)
        timers.add(name)
        taskManager.addTimer(
            ticks = ticks,
            iNode = id
        ) {
            timerTick(name, ticks, f)
        }
    }

    fun cancelTimer(name: String) {
        if (name !in timers) {
            throw IllegalArgumentException("Timer with name $name does not exist")
        }
        eventFactory.createCancelTimerEvent(id, name)
        timers.remove(name)
    }

    /**
     * Records an internal event [InternalEvent][org.jetbrains.kotlinx.lincheck.distributed.event.InternalEvent]. Can be used for debugging purposes.
     * Can store any object as [attachment].
     */
    fun recordInternalEvent(attachment: Any) {
        eventFactory.createInternalEvent(attachment, id)
    }
}