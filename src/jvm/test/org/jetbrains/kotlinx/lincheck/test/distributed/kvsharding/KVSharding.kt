/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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

/*
package org.jetbrains.kotlinx.lincheck.test.distributed.kvsharding

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import java.lang.RuntimeException

class Member(private val env: Environment) : Node {
    val T_FAIL = 5
    val T_SEND = 2
    private val availableNodes = Array(env.numberOfNodes) { -1 }

    private fun constructMessage(body : String) : Message = Message(body, headers = hashMapOf("from" to env.nodeId.toString()))
    private fun sendToAvailableNodes(message: Message) = availableNodes.withIndex().filter { it.value != -1 && it.index != env.nodeId }.forEach { env.send(message, it.index) }

    override fun onMessage(message: Message) {
        val node = message.headers["from"]!!.toInt()
        when (message.body) {
            "LEAVE" -> {
                availableNodes[node] = -1
                sendToAvailableNodes(message)
            }
            "JOIN" -> {
                sendToAvailableNodes(message)
                availableNodes[node] = 0
                env.send(constructMessage("HEARTBEAT"), node)
            }
            "HEARTBEAT" -> {
                availableNodes[node] = 0
            }
            else -> throw RuntimeException("Unknown message type")
        }
    }

    override fun onTimer(timer: String) {
        if (timer == "HEARTBEAT") {
            val msg = constructMessage("HEARTBEAT")
            sendToAvailableNodes(msg)
            for (node in 0 until env.numberOfNodes) {
                if (node != env.nodeId && availableNodes[node] > T_FAIL) {
                    availableNodes[node] = -1
                }
            }
            env.setTimer("HEARTBEAT", T_SEND)
        }
    }

    @Operation
    fun join(id: Int) {
        availableNodes[env.nodeId] = 0
        if (id != env.nodeId) {
            env.send(constructMessage("JOIN"), id)
        }
        env.setTimer("HEARTBEAT", T_SEND)
    }

    @Operation
    fun leave() {
        sendToAvailableNodes(constructMessage("LEAVE"))
        availableNodes.fill(-1)
        env.cancelTimer("HEARTBEAT")
    }

    private fun getMembersOnce(): List<Int> {
        return availableNodes.withIndex().filter { it.value != -1 }.map { it.index }
    }

    @Operation
    fun getMembers(): List<Int> {
        return getMembersOnce()
    }
}
 */