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

import org.jetbrains.kotlinx.lincheck.distributed.NodeEnvironment
import org.jetbrains.kotlinx.lincheck.distributed.NodeWithStorage
import java.lang.Integer.min
import kotlin.random.Random

enum class Status {
    LEADER,
    CANDIDATE,
    FOLLOWER
}

class RaftServer(env: NodeEnvironment<RaftMessage>) : NodeWithStorage<RaftMessage, PersistentStorage>(env) {
    companion object {
        const val HEARTBEAT_RATE = 50
        const val MIN_ELECTION_TIMEOUT: Int = HEARTBEAT_RATE * 10
        const val MAX_ELECTION_TIMEOUT: Int = HEARTBEAT_RATE * 20
    }

    private val nodeCount = env.getIds<RaftServer>().count()

    private var status = Status.FOLLOWER
    private var commitIndex = -1
    private var lastApplied = -1
    private val nextIndices = Array(env.nodes) {
        0 //TODO: cannot use storage in initialization
    }
    private var receivedHeartbeatCount = 0L
    private val random = Random(env.id)
    private val majority = nodeCount / 2 + 1
    private var receivedOks = Array(env.nodes) { false }
    private var leaderId: Int? = null
    private val matchIndices = Array(env.nodes) { 0 }
    private val stateMachine = StateMachine()

    override fun onStart() {
        setCheckLeaderTimer()
    }

    override fun recover() {
        nextIndices.fill(storage.logSize)
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
        storage.currentTerm++
        status = Status.CANDIDATE
        storage.votedFor = env.id
        receivedOks.fill(false)
        receivedOks[env.id] = true
        env.recordInternalEvent("Start election")
        env.broadcastToGroup<RaftServer>(
            RequestVote(
                storage.currentTerm,
                env.id,
                lastLogIndex = storage.lastLogIndex,
                lastLogTerm = storage.lastLogTerm
            )
        )
    }

    /**
     * 1. Reply false if [RequestVote.term] < [PersistentStorage.currentTerm]
     * 2. If [PersistentStorage.votedFor] is null or [sender] and candidate’s log is at
     * least as up-to-date as this log, grant vote
     */
    private fun onRequestVote(message: RequestVote, sender: Int) {
        if (storage.currentTerm > message.term
            || storage.votedFor != null && storage.votedFor != sender
            || storage.isMoreUpToDate(message.lastLogIndex, message.lastLogTerm)
        ) {
            env.send(RequestVoteResponse(storage.currentTerm, false), sender)
            return
        }
        storage.votedFor = sender
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
        leaderId = env.id
        env.recordInternalEvent("Election success")
        onNewEntry(null)
        env.setTimer("HEARTBEAT", HEARTBEAT_RATE) {
            if (status == Status.LEADER) broadcastEntries()
            else env.cancelTimer("HEARTBEAT")
        }
    }

    private fun broadcastEntries() {
        if (status != Status.LEADER) return
        for (i in env.getIds<RaftServer>()) {
            if (i == env.id) continue
            env.send(
                AppendEntries(
                    term = storage.currentTerm,
                    leaderCommit = commitIndex,
                    prevLogIndex = nextIndices[i] - 1,
                    prevLogTerm = storage.prevTermForIndex(nextIndices[i]),
                    entries = storage.getEntriesFromIndex(nextIndices[i])
                ), i
            )
        }
    }

    private fun isLastLogReplicated(): Boolean {
        return status == Status.LEADER && matchIndices.count { it == storage.lastLogIndex } >= majority
    }

    private fun onNewEntry(command: Command?) {
        if (status != Status.LEADER) return
        storage.appendEntry(command)
        matchIndices[env.id] = storage.lastLogIndex
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
        if (storage.currentTerm > message.term) {
            env.send(OutdatedTermResponse(storage.currentTerm), sender)
            return
        }
        receivedHeartbeatCount++
        leaderId = sender
        if (!storage.containsEntry(message.prevLogIndex, message.prevLogTerm)) {
            env.send(
                AppendEntriesResponse(message.term, false, storage.lastLogIndex, message.prevLogIndex),
                sender
            )
            return
        }
        message.entries.forEachIndexed { index, entry ->
            storage.appendEntry(
                command = entry.command,
                index = message.prevLogIndex + index + 1,
                term = entry.term
            )
        }
        if (message.leaderCommit > commitIndex) {
            commitIndex = min(message.leaderCommit, storage.lastLogIndex)
        }
        applyEntries()
        env.send(AppendEntriesResponse(message.term, true, storage.lastLogIndex, message.prevLogIndex), sender)
    }

    private fun onAppendEntriesResponse(message: AppendEntriesResponse, sender: Int) {
        if (message.term < storage.currentTerm || status != Status.LEADER) return
        if (message.success) {
            matchIndices[sender] = message.lastLogIndex
            nextIndices[sender] = message.lastLogIndex + 1
            if (isLastLogReplicated()) {
                commitIndex = storage.lastLogIndex
                applyEntries()
            }
            return
        }
        nextIndices[sender] = min(nextIndices[sender], message.prevLogLeaderIndex)
        if (nextIndices[sender] < 0) {
            env.recordInternalEvent("Next index less than 0, ${nextIndices[sender]}")
        }
        env.send(
            AppendEntries(
                term = storage.currentTerm,
                prevLogIndex = nextIndices[sender] - 1,
                prevLogTerm = storage.prevTermForIndex(nextIndices[sender]),
                entries = storage.getEntriesFromIndex(nextIndices[sender]),
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
            val applied = stateMachine.applyEntries(storage.getEntriesSlice(lastApplied + 1, commitIndex))
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
        if (!isLastLogReplicated() || storage.containsCommandId(message.command.id)) {
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
        if (storage.currentTerm < message.term) {
            storage.currentTerm = message.term
            storage.votedFor = null
            if (status == Status.LEADER) {
                env.cancelTimer("HEARTBEAT")
            }
            status = Status.FOLLOWER
        }
        when (message) {
            is RequestVoteResponse -> {
                if (storage.currentTerm > message.term) return
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
            is OutdatedTermResponse -> return
            else -> throw IllegalStateException("Unexpected message $message to server ${env.id}")
        }
    }

    override fun stateRepresentation(): String {
        return "$status, term=${storage.currentTerm}, leaderId=$leaderId, receivedOksCount=${receivedOks.count { it }}, heartbeats=$receivedHeartbeatCount, nextIndices=${nextIndices.toList()}"
    }

    override fun createStorage(): PersistentStorage = PersistentStorage()
}

