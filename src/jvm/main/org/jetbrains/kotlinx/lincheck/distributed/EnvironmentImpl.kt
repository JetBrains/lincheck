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

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.intrinsics.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.kotlinx.lincheck.distributed.event.EventFactory
import java.lang.IllegalArgumentException
import kotlin.coroutines.Continuation

internal object TimeoutExceedException : Exception()

internal class EnvironmentImpl<Message, DB>(
    override val nodeId: Int,
    override val numberOfNodes: Int,
    private val _database: DB,
    private val eventFactory: EventFactory<Message, DB>,
    private val strategy: DistributedStrategy<Message, DB>,
    private val taskManager: TaskManager
) : Environment<Message, DB> {
    override val database: DB
        get() {
            strategy.beforeLogModify(nodeId)
            return _database
        }

    private val timers = mutableSetOf<String>()

    override fun getAddressesForClass(cls: Class<out Node<Message, DB>>): List<Int>? =
        strategy.testCfg.addressResolver[cls]

    override fun send(message: Message, receiver: Int) {
        val e = eventFactory.createMessageEvent(message, nodeId, receiver)
        val rate = strategy.crashOrReturnRate(e)
        repeat(rate) {
            taskManager.addMessageReceiveTask(to = receiver, from = nodeId) {
                eventFactory.createMessageReceiveEvent(e)
                //TODO: better way to access node instances
                eventFactory.nodeInstances[receiver].onMessage(message, nodeId)
            }
        }
        strategy.onMessageSent(nodeId, e)
    }

    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    override suspend fun withTimeout(ticks: Int, block: suspend () -> Unit): Boolean = try {
        suspendCancellableCoroutine<Unit> { cont ->
            taskManager.addTimeout(ticks = ticks, iNode = nodeId) {
                if (cont.isActive) cont.cancel(TimeoutExceedException)
            }
            block.startCoroutineUnintercepted(cont)
        }
        true
    } catch (e: TimeoutExceedException) {
        false
    }

    override suspend fun sleep(ticks: Int) {
        suspendCancellableCoroutine<Unit> { cont ->
            taskManager.addTimeout(ticks = ticks, iNode = nodeId) {
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
        }
    }

    private fun timerTick(name: String, ticks: Int, f: () -> Unit) {
        if (name !in timers) return
        eventFactory.createTimerTickEvent(name, nodeId)
        f()
        taskManager.addTimer(ticks = ticks, iNode = nodeId) {
            timerTick(name, ticks, f)
        }
    }

    override fun setTimer(name: String, ticks: Int, f: () -> Unit) {
        if (name in timers) {
            throw IllegalArgumentException("Timer with name $name already exists")
        }
        eventFactory.createSetTimerEvent(nodeId, name)
        timers.add(name)
        taskManager.addTimer(ticks = ticks, iNode = nodeId) {
            timerTick(name, ticks, f)
        }
    }

    override fun cancelTimer(name: String) {
        if (name !in timers) {
            throw IllegalArgumentException("Timer with name $name does not exist")
        }
        eventFactory.createCancelTimerEvent(nodeId, name)
        timers.remove(name)
    }

    override fun recordInternalEvent(attachment: Any) {
        eventFactory.createInternalEvent(attachment, nodeId)
    }
}