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
class NodeEnvironment<Message> internal constructor(
    val id: Int,
    val nodes: Int,
    private val eventFactory: EventFactory<Message>,
    private val strategy: DistributedStrategy<Message>,
    private val taskManager: TaskManager,
) {
    /**
     * Called before accessing storage and can cause node crash.
     */
    internal fun beforeStorageAccess() = strategy.beforeStorageAccess(id)

    /**
     * Stores timer names for this node.
     */
    private val timers = mutableSetOf<String>()

    /**
     * Returns the list of ids for the specified node type.
     */
    inline fun <reified NodeType : Node<Message>> getAddresses(): List<Int> {
        return getAddressesForClass(NodeType::class.java)
    }

    //TODO: make private
    fun getAddressesForClass(cls: Class<out Node<Message>>): List<Int> =
        strategy.testCfg.addressResolver[cls]

    /**
     * Sends [message] to [receiver].
     */
    fun send(message: Message, receiver: Int) {
        val e = eventFactory.createMessageEvent(message, id, receiver)
        // How many times the message will be delivered to receiver (including message loss, duplications or network partitions).
        // Can cause node crash.
        val rate = strategy.crashOrReturnRate(id, receiver, e.id)
        repeat(rate) {
            // Add MessageReceiveTask to TaskManager.
            taskManager.addMessageReceiveTask(
                to = receiver,
                from = id
            ) {
                eventFactory.createMessageReceiveEvent(e)
                //TODO: better way to access node instances
                eventFactory.nodeInstances[receiver].onMessage(message, id)
            }
        }
        // Can crash if message was sent.
        if (rate > 0) strategy.onMessageSent(id, receiver, e.id)
    }

    /**
     * Sends the specified [message] to all nodes (from 0 to [nodes]).
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

    /**
     * Sends the [message] to all nodes of the specified node type.
     * If [skipItself] is true, doesn't send the message to itself.
     */
    inline fun <reified NodeType : Node<Message>> broadcastToGroup(message: Message, skipItself: Boolean = true) {
        broadcastToGroup(message, NodeType::class.java, skipItself)
    }

    //TODO: make private
    fun broadcastToGroup(message: Message, cls: Class<out Node<Message>>, skipItself: Boolean) {
        for (i in getAddressesForClass(cls)) {
            if (i == id && skipItself) continue
            send(message, i)
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    /**
     * Replacement for [kotlinx.coroutines.withTimeout].
     * Returns the result of [block] or null if the coroutine was resumed because the timeout exceeded.
     */
    suspend fun <T> withTimeout(ticks: Int, block: suspend CoroutineScope.() -> T): T? {
        //The logic of implementation is the same as for kotlinx.coroutines.withTimeout
        if (ticks <= 0) return null
        var coroutine: TimeoutCoroutine<T?, T?>? = null
        try {
            return suspendCoroutineUninterceptedOrReturn { uCont ->
                val timeoutCoroutine = TimeoutCoroutine(ticks, uCont)
                coroutine = timeoutCoroutine
                // Add timeout task to resume the coroutine.
                val task = taskManager.addTimeout(id, ticks) {
                    timeoutCoroutine.run()
                }
                // Remove the task if the coroutine was already resumed.
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

    /**
     * Replacement of [kotlinx.coroutines.delay].
     */
    suspend fun delay(ticks: Int) {
        suspendCancellableCoroutine<Unit> { cont ->
            // Add timeout task to resume the coroutine.
            taskManager.addTimeout(
                ticks = ticks,
                iNode = id
            ) {
                cont.resumeWith(Result.success(Unit))
            }
        }
    }

    /**
     * Single timer tick.
     */
    private fun timerTick(name: String, ticks: Int, f: () -> Unit) {
        // Check timer is not removed (internal bug).
        check(name in timers)
        eventFactory.createTimerTickEvent(name, id)
        f()
        // Add next tick to task manager.
        taskManager.addTimer(
            ticks = ticks,
            iNode = id,
            name = name
        ) {
            timerTick(name, ticks, f)
        }
    }

    fun setTimer(name: String, ticks: Int, f: () -> Unit) {
        // Check timer with such name doesn't exist.
        if (name in timers) {
            throw IllegalArgumentException("Timer with name $name already exists")
        }
        eventFactory.createSetTimerEvent(id, name)
        timers.add(name)
        // Add task to TaskManager.
        taskManager.addTimer(
            ticks = ticks,
            iNode = id,
            name = name
        ) {
            timerTick(name, ticks, f)
        }
    }

    fun cancelTimer(name: String) {
        // Check timer with such name exists.
        if (name !in timers) {
            throw IllegalArgumentException("Timer with name $name does not exist")
        }
        eventFactory.createCancelTimerEvent(id, name)
        timers.remove(name)
        // Remove timer task from task manager.
        taskManager.removeTimer(name, id)
    }

    /**
     * Records an internal event [InternalEvent][org.jetbrains.kotlinx.lincheck.distributed.event.InternalEvent]. Can be used for debugging purposes.
     * Can store any object as [attachment].
     */
    fun recordInternalEvent(attachment: Any) {
        eventFactory.createInternalEvent(attachment, id)
    }
}