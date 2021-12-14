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

package org.jetbrains.kotlinx.lincheck.test.distributed.examples.raft

import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import java.lang.Integer.min
import kotlin.random.Random

enum class Status {
    LEADER,
    CANDIDATE,
    FOLLOWER
}

class RaftServer(private val env: Environment<RaftMessage, PersistentStorage>) : Node<RaftMessage, PersistentStorage> {
    companion object {
        const val HEARTBEAT_RATE = 5
        const val MIN_ELECTION_TIMEOUT: Int = HEARTBEAT_RATE * 20
        const val MAX_ELECTION_TIMEOUT: Int = HEARTBEAT_RATE * 40
    }

    private val nodeCount = env.getAddressesForClass(RaftServer::class.java)!!.count()

    private var status = Status.FOLLOWER
    private var commitIndex = -1
    private var lastApplied = -1
    private val nextIndices = Array(env.numberOfNodes) {
        env.database.logSize
    }
    private var receivedHeartbeatCount = 0L
    private val random = Random(env.nodeId)
    private val majority = nodeCount / 2 + 1
    private var receivedOks = Array(env.numberOfNodes) { false }
    private var leaderId: Int? = null
    private val matchIndices = Array(env.numberOfNodes) { 0 }
    private val stateMachine = StateMachine()

    override fun onStart() {
        setCheckLeaderTimer()
    }

    override fun recover() {
        setCheckLeaderTimer()
    }

    //TODO better names
    private fun setCheckLeaderTimer() {
        env.setTimer("CheckLeader", random.nextInt(from = MIN_ELECTION_TIMEOUT, until = MAX_ELECTION_TIMEOUT)) {
            if (status != Status.LEADER && receivedHeartbeatCount == 0L) {
                startElection()
            }
            if (status != Status.LEADER) receivedHeartbeatCount = 0
            else receivedHeartbeatCount++
        }
    }

    /**
     * 1. Increment current term ([PersistentStorage.currentTerm])
     * 2. Transit to [Status.CANDIDATE]
     * 3. Vote for itself
     * 4. Send [RequestVote] to other nodes.
     */
    private fun startElection() {
        leaderId = null
        env.database.currentTerm++
        status = Status.CANDIDATE
        env.database.votedFor = env.nodeId
        receivedOks.fill(false)
        receivedOks[env.nodeId] = true
        env.recordInternalEvent("Start election")
        env.broadcastToGroup(
            RequestVote(
                env.database.currentTerm,
                env.nodeId,
                lastLogIndex = env.database.lastLogIndex,
                lastLogTerm = env.database.lastLogTerm
            ), RaftServer::class.java
        )
    }

    /**
     * 1. Reply false if [RequestVote.term] < [PersistentStorage.currentTerm]
     * 2. If [PersistentStorage.votedFor] is null or [sender] and candidate’s log is at
     * least as up-to-date as this log, grant vote
     */
    private fun onRequestVote(message: RequestVote, sender: Int) {
        if (env.database.currentTerm > message.term
            || env.database.votedFor != null && env.database.votedFor != sender
            || env.database.isMoreUpToDate(message.lastLogIndex, message.lastLogTerm)
        ) {
            env.send(RequestVoteResponse(env.database.currentTerm, false), sender)
            return
        }
        env.database.votedFor = sender
        env.send(RequestVoteResponse(message.term, true), sender)
    }

    private fun onRequestVoteResponse(message: RequestVoteResponse, sender: Int) {
        if (status != Status.CANDIDATE) return
        if (message.voteGranted) {
            receivedOks[sender] = true
            val voteCount = receivedOks.count { it }
            if (voteCount >= majority) onElectionSuccess()
        }
    }

    private fun onElectionSuccess() {
        status = Status.LEADER
        leaderId = env.nodeId
        env.recordInternalEvent("Election success")
        onNewEntry(null)
        env.setTimer("HEARTBEAT", HEARTBEAT_RATE) {
            if (status == Status.LEADER) broadcastEntries()
            else env.cancelTimer("HEARTBEAT")
        }
    }

    private fun broadcastEntries() {
        if (status != Status.LEADER) return
        for (i in env.getAddressesForClass(RaftServer::class.java)!!) {
            if (i == env.nodeId) continue
            env.send(
                AppendEntries(
                    term = env.database.currentTerm,
                    leaderCommit = commitIndex,
                    prevLogIndex = nextIndices[i] - 1,
                    prevLogTerm = env.database.prevTermForIndex(nextIndices[i]),
                    entries = env.database.getEntriesFromIndex(nextIndices[i])
                ), i
            )
        }
    }

    private fun isLastLogReplicated(): Boolean {
        return status == Status.LEADER && matchIndices.count { it == env.database.lastLogIndex } >= majority
    }

    private fun onNewEntry(command: Command?) {
        if (status != Status.LEADER) return
        env.database.appendEntry(command)
        matchIndices[env.nodeId] = env.database.lastLogIndex
        broadcastEntries()
    }

    /**
     * 1. Reply false if [AppendEntries.term] < [PersistentStorage.currentTerm]
     * 2. Reply false if log doesn’t contain an entry at [AppendEntries.prevLogIndex] whose term matches [AppendEntries.prevLogTerm]
     * 3. Append new entries (see [PersistentStorage.appendEntry])
     * 4. If [AppendEntries.leaderCommit] > [commitIndex], set [commitIndex] = min(leaderCommit, index of last new entry)
     * 5. Apply committed entries to state machine.
     * 6. Reply true to sender.
     */
    private fun onAppendEntries(message: AppendEntries, sender: Int) {
        if (env.database.currentTerm > message.term) {
            env.send(AppendEntriesResponse(env.database.currentTerm, false, env.database.lastLogIndex), sender)
            return
        }
        receivedHeartbeatCount++
        leaderId = sender
        if (!env.database.containsEntry(message.prevLogIndex, message.prevLogTerm)) {
            env.send(AppendEntriesResponse(message.term, false, env.database.lastLogIndex), sender)
            return
        }
        message.entries.forEachIndexed { index, entry ->
            env.database.appendEntry(
                command = entry.command,
                index = message.prevLogIndex + index + 1,
                term = entry.term
            )
        }
        if (message.leaderCommit > commitIndex) {
            commitIndex = min(message.leaderCommit, env.database.lastLogIndex)
        }
        applyEntries()
        env.send(AppendEntriesResponse(message.term, true, env.database.lastLogIndex), sender)
    }

    private fun onAppendEntriesResponse(message: AppendEntriesResponse, sender: Int) {
        if (message.term < env.database.currentTerm || status != Status.LEADER) return
        if (message.success) {
            matchIndices[sender] = message.lastLogIndex
            nextIndices[sender] = message.lastLogIndex + 1
            if (isLastLogReplicated()) {
                commitIndex = env.database.lastLogIndex
                applyEntries()
            }
            return
        }
        nextIndices[sender]--
        env.send(
            AppendEntries(
                term = env.database.currentTerm,
                prevLogIndex = nextIndices[sender] - 1,
                prevLogTerm = env.database.prevTermForIndex(nextIndices[sender]),
                entries = env.database.getEntriesFromIndex(nextIndices[sender]),
                leaderCommit = commitIndex
            ),
            sender
        )
    }

    /**
     * If [commitIndex] > [lastApplied]: increment [lastApplied],
     * apply log\[lastApplied] to state machine
     */
    private fun applyEntries() {
        if (commitIndex > lastApplied) {
            val applied = stateMachine.applyEntries(env.database.getEntriesSlice(lastApplied + 1, commitIndex))
            lastApplied = commitIndex
            if (status == Status.LEADER) applied.forEach {
                env.send(
                    ClientResult(it.second, it.first),
                    it.first.client
                )
            }
        }
    }

    private fun onClientRequest(message: ClientRequest, sender: Int) {
        if (leaderId == null) {
            return
        }
        if (status != Status.LEADER) {
            env.send(NotALeader(leaderId!!), sender)
            return
        }
        if (stateMachine.isPresent(message.command.id)) {
            env.send(ClientResult(stateMachine.getResult(message.command.id), message.command.id), sender)
            return
        }
        if (!isLastLogReplicated() || env.database.containsCommandId(message.command.id)) {
            env.send(RejectOperation(), sender)
            return
        }
        onNewEntry(message.command)
    }

    override fun onMessage(message: RaftMessage, sender: Int) {
        if (message is ClientRequest) {
            onClientRequest(message, sender)
            return
        }
        if (env.database.currentTerm < message.term) {
            env.database.currentTerm = message.term
            env.database.votedFor = null
            status = Status.FOLLOWER
        }
        when (message) {
            is RequestVoteResponse -> {
                if (env.database.currentTerm > message.term) return
                onRequestVoteResponse(message, sender)
            }
            is RequestVote -> {
                onRequestVote(message, sender)
            }
            is AppendEntries -> {
                onAppendEntries(message, sender)
            }
            is AppendEntriesResponse -> {
                onAppendEntriesResponse(message, sender)
            }
            else -> throw IllegalStateException("Unexpected message $message to server ${env.nodeId}")
        }
    }

    override fun stateRepresentation(): String {
        return "$status, leaderId=$leaderId, receivedOksCount=${receivedOks.count { it }}, heartbeats=$receivedHeartbeatCount"
    }
}

