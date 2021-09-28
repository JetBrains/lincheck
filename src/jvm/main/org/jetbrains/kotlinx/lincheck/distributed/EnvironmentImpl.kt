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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.intrinsics.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.IllegalArgumentException

internal object TimeoutExceedException : Exception()

internal class EnvironmentImpl<Message, Log>(
    override val nodeId: Int,
    override val numberOfNodes: Int,
    override val log: MutableList<Log>,
    private val eventFactory: EventFactory<Message, Log>,
    private val strategy: DistributedStrategy<Message, Log>,
    private val taskManager: TaskManager
) : Environment<Message, Log> {
    private val timers = mutableSetOf<String>()

    override fun getAddressesForClass(cls: Class<out Node<Message, Log>>): List<Int>? =
        strategy.testCfg.addressResolver[cls]

    override fun send(message: Message, receiver: Int) {
        val e = eventFactory.createMessageEvent(message, nodeId, receiver)
        val rate = strategy.crashOrReturnRate(nodeId, e)
        repeat(rate) {
            taskManager.addTask(MessageReceiveTask(iNode = receiver, from = nodeId) {
                eventFactory.createMessageReceiveEvent(e)
                //TODO: better way to access node instances
                eventFactory.nodeInstances[receiver].onMessage(message, nodeId)
            })
        }
        strategy.onMessageSent(nodeId, e)
    }

    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    override suspend fun withTimeout(ticks: Int, block: suspend () -> Unit): Boolean = try {
        suspendCancellableCoroutine<Unit> { cont ->
            taskManager.addTimeTask(Timeout(ticks, nodeId) {
                //recordInternalEvent("Before canceling")
                if (cont.isActive) cont.cancel(TimeoutExceedException)
            })
            block.startCoroutineUnintercepted(cont)
        }
        //recordInternalEvent("After suspending block")
        true
    } catch (e: TimeoutExceedException) {
        false
    }

    override suspend fun sleep(ticks: Int) {
        suspendCancellableCoroutine<Unit> { cont ->
            taskManager.addTimeTask(Timeout(ticks, nodeId) {
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            })
        }
    }

    private suspend fun timerTick(name: String, ticks: Int, f: suspend () -> Unit) {
        if (name !in timers) return
        eventFactory.createTimerTickEvent(name, nodeId)
        f()
        taskManager.addTimeTask(Timer(ticks, nodeId) {
            timerTick(name, ticks, f)
        })
    }

    override fun setTimer(name: String, ticks: Int, f: suspend () -> Unit) {
        if (name in timers) {
            throw IllegalArgumentException("Timer with name $name already exists")
        }
        timers.add(name)
        taskManager.addTimeTask(Timer(ticks, nodeId) {
            timerTick(name, ticks, f)
        })
    }

    override fun cancelTimer(name: String) {
        if (name !in timers) {
            throw IllegalArgumentException("Timer with name $name does not exist")
        }
        timers.remove(name)
    }

    override fun recordInternalEvent(attachment: Any) {
        eventFactory.createInternalEvent(attachment, nodeId)
    }
}