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

package org.jetbrains.kotlinx.lincheck.test.distributed.raft

import com.sun.org.apache.xpath.internal.operations.Bool
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.RecoverableNode

enum class NodeStatus { LEADER, CANDIDATE, FOLLOWER }

sealed class Message(val term : Int)
class RequestVote(term: Int, val candidate : Int): Message(term)
class AppendEntries(term : Int, val leader : Int, val entries : List<Command>) : Message(term)
class VoteResponse(term : Int, val res : Boolean): Message(term)
class RedirectRequest(term : Int, val command: Command, val sender : Int) : Message(term)
class AppendEntriesResponse(term : Int, val res : Boolean) : Message(term)

sealed class Command
data class Log(val command : Int, val term : Int, val index : Int)

class RaftServer(val env : Environment<Message, Log>) : RecoverableNode<Message, Log> {
    private var currentTerm = 0
    private var currentLeader : Int? = null
    private var votedFor : Int? = null
    private var status : NodeStatus = NodeStatus.FOLLOWER
    private var commitIndex = 0
    private var lastApplied = 0

    override fun onMessage(message: Message, sender: Int) {

        when (message) {
            is RequestVote -> {
                if (votedFor == null && message.term >= currentTerm) {
                    env.send(VoteResponse(currentTerm, true), sender)
                    votedFor = sender
                }
            }
            is AppendEntries -> TODO()
            is VoteResponse -> TODO()
            is RedirectRequest -> TODO()
            is AppendEntriesResponse -> TODO()
        }
    }

    private fun updateTerm(term : Int) {
        if (term > currentTerm) {
            currentTerm = term
            status = NodeStatus.FOLLOWER
        }
    }

    override fun recover(logs: List<Log>) {
        TODO("Not yet implemented")
    }

    override fun onNodeUnavailable(nodeId: Int) {
        startElection()
    }

    private fun startElection() {
        currentTerm++
        status = NodeStatus.CANDIDATE
        votedFor = env.nodeId
        env.broadcast(RequestVote(currentTerm, env.nodeId), true)
    }
}