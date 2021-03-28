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
/*
package org.jetbrains.kotlinx.lincheck.test.distributed.raft

import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import java.lang.Exception

enum class NodeStatus { LEADER, CANDIDATE, FOLLOWER }

sealed class VoteReason
object Ok : VoteReason()
class Refuse(val leader: Int)

class LeaderNotChosenException : Exception()
data class LogEntryNumber(val term: Int, val index: Int)
sealed class Message(val term: Int)
class RequestVote(term: Int, val count: Int, val candidate: Int) : Message(term)
class Heartbeat(term: Int, val lastCommitedEntry: Int) : Message(term)
class VoteResponse(term: Int, val res: Boolean) : Message(term)
class VoteAck(term: Int) : Message(term)
class AppendEntries(term: Int, val leader: Int, val entries: List<Command>) : Message(term)

class RedirectRequest(term: Int, val command: Command, val sender: Int) : Message(term)
class AppendEntriesResponse(term: Int, val res: Boolean) : Message(term)

sealed class Command
data class Log(val command: Int, val term: Int, val index: Int, var commited: Boolean = false)

class RaftServer(val env: Environment<Message, Log>) : Node<Message> {
    private var currentTerm = 0
    private var currentLeader: Int? = 0
    private var votedFor: Int? = null
    private var status: NodeStatus = NodeStatus.FOLLOWER
    private var commitIndex = 0
    private var lastApplied = 0
    private val electionSemaphore = Signal()
    private var receivedOks = 0
    private val quorum = env.numberOfNodes / 2 + 1

    override suspend fun onMessage(message: Message, sender: Int) {
        if (currentTerm > message.term) return

        when (message) {
            is RequestVote -> {
                if (votedFor == null
                    && message.term >= currentTerm
                    && message.count >= env.log.size
                ) {
                    env.send(VoteResponse(currentTerm, true), sender)
                    votedFor = sender
                }
            }
            is AppendEntries -> TODO()
            is VoteResponse -> {
                if (message.res) receivedOks++
                if (receivedOks >= quorum) {
                    currentLeader = env.nodeId
                    status = NodeStatus.LEADER
                    electionSemaphore.signal()
                }
            }
            is RedirectRequest -> TODO()
            is AppendEntriesResponse -> TODO()
            is VoteAck -> {
                status = NodeStatus.FOLLOWER
                currentLeader = sender
                electionSemaphore.signal()
            }
        }
    }

    private fun updateTerm(message: Message, sender: Int) {
        if (message.term > currentTerm) {
            currentTerm = message.term
            status = NodeStatus.FOLLOWER
            if (message is Heartbeat) {
                currentLeader = sender
                repeat(message.lastCommitedEntry + 1) { i ->
                    env.log.find { it.index == i }?.commited = true
                }
            } else {
                if (message !is AppendEntries) {
                    currentLeader = null
                }
            }
            electionSemaphore.signal()
        }
    }

    override suspend fun recover() {
        TODO("Not yet implemented")
    }

    override suspend fun onNodeUnavailable(nodeId: Int) {
        while (true) {
            if (currentLeader != nodeId) return
            currentLeader = null
            env.delay(env.nodeId)
            if (currentLeader == null) {
                startElection()
            }
            if (status != NodeStatus.CANDIDATE) return
        }
    }

    private suspend fun startElection() {
        currentTerm++
        status = NodeStatus.CANDIDATE
        votedFor = env.nodeId
        receivedOks = 1
        env.broadcast(RequestVote(currentTerm, env.nodeId))
        env.withTimeout(3) {
            electionSemaphore.await()
        }
        if (status == NodeStatus.LEADER) {
            env.broadcast(VoteAck(currentTerm))
        }
    }

    suspend fun get(): String? {
        if (currentLeader == null) {
            throw LeaderNotChosenException()
        }
    }
}
 */