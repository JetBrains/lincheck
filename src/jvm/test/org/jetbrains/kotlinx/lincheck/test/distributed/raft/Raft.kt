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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import kotlin.random.Random

enum class NodeStatus { LEADER, CANDIDATE, FOLLOWER }

sealed class VoteReason
object Ok : VoteReason()
class Refuse(val leader: Int)

data class LogEntryNumber(val term: Int, val index: Int)

enum class Status { APPLY, REJECT, MISSING_ENTRIES }
sealed class Message(val term: Int)
class RequestVote(term: Int, val count: Int, val candidate: Int) : Message(term)
class Heartbeat(term: Int, val lastCommittedEntry: LogEntryNumber?) : Message(term)
class VoteResponse(term: Int, val res: Boolean) : Message(term)
class PutRequest(term: Int, val key: Int, val value: Int, val hash: String) : Message(term)
class PutResponse(term: Int, val hash: String) : Message(term)
class GetRequest(term: Int, val key: Int, val hash: String) : Message(term)
class GetResponse(term: Int, val hash: String, val value: Int?) : Message(term)
class ApplyEntryRequest(term: Int, val logEntry: Log, val prevLogNumber: LogEntryNumber?) : Message(term)
class MissingEntryResponse(term: Int, val prevLogNumber: LogEntryNumber?) : Message(term)
class ApplyEntryResponse(term: Int, val logNumber: LogEntryNumber) : Message(term)

data class Log(
    val key: Int,
    val value: Int,
    val term: Int,
    val sender: Int,
    val hash: String,
    var committed: Boolean = false
)

data class OperationPromise(val signal : Signal, var result: Message? = null)

class Storage(val log: MutableList<Log>) {
    fun add(entry: Log): Boolean {
        if (log.any { it.hash == entry.hash }) return false
        log.add(entry)
        return true
    }

    fun get(key: Int): Int? {
        return log.filter { it.committed }.lastOrNull { it.key == key }?.value
    }

    fun commit(entryNumber: LogEntryNumber): Boolean {
        if (entryNumber.index >= log.size || log[entryNumber.index].term != entryNumber.term) {
            return false
        }
        for (i in 0..entryNumber.index) {
            log[i].committed = true
        }
        return true
    }

    fun getLastCommittedEntry() =
        log.filter { it.committed }.mapIndexed { i, l -> LogEntryNumber(term = l.term, index = i) }.lastOrNull()

    fun getLastEntry() = log.mapIndexed { i, l -> LogEntryNumber(term = l.term, index = i) }.lastOrNull()

    fun prevEntry(logEntry: LogEntryNumber): LogEntryNumber? {
        check(log[logEntry.index].term == logEntry.term)
        return if (logEntry.index == 0) {
            null
        } else {
            LogEntryNumber(log[logEntry.index - 1].term, logEntry.index - 1)
        }
    }

    fun checkEntry(entryNumber: LogEntryNumber?): Boolean {
        if (entryNumber == null) return true
        return entryNumber.index < log.size && log[entryNumber.index].term == entryNumber.term
    }

    fun applyEntries(entries: List<Log>, lastIndex: Int) {
        log.dropLast(log.size - (lastIndex + 1))
        log.addAll(entries)
    }

    fun size() = log.size

    operator fun get(entryNumber: LogEntryNumber): Log {
        check(log[entryNumber.index].term == entryNumber.term)
        return log[entryNumber.index]
    }

    fun getLastTerm(): Int {
        return log.lastOrNull()?.term ?: 0
    }
}

class RaftServer(val env: Environment<Message, Log>) : Node<Message> {
    companion object {
        const val HEARTBEAT_RATE = 2
        const val MISSED_HEARTBEATS_LIMIT = 3
    }
    private var currentTerm = 0
    private var currentLeader: Int? = 0
    private var votedFor: Int? = null
    private var status: NodeStatus = NodeStatus.FOLLOWER
    private val electionSemaphore = Signal()
    private var receivedOks = 0
    private val quorum = env.numberOfNodes / 2 + 1
    private var missedHeartbeatCnt = 0
    private val logEntriesCommitResponses = mutableMapOf<LogEntryNumber, Int>()
    private val storage = Storage(env.log)
    private val recoveredEntries = mutableListOf<Log>()
    private var opId = 0
    private val operations = mutableMapOf<String, OperationPromise>()

    override suspend fun onStart() {
        super.onStart()
        env.setTimer("CHECK", HEARTBEAT_RATE) {
            if (status != NodeStatus.LEADER) {
                if (missedHeartbeatCnt < MISSED_HEARTBEATS_LIMIT) missedHeartbeatCnt++
                if (missedHeartbeatCnt == MISSED_HEARTBEATS_LIMIT && currentLeader != null) {
                    startElection()
                }
            }
        }
    }

    private suspend fun replicateLog(log: Log) {
        val lastEntry = storage.getLastEntry()
        storage.add(log)
        env.broadcast(ApplyEntryRequest(currentTerm, log, lastEntry))
    }

    override suspend fun onMessage(message: Message, sender: Int) {
        if (message is GetRequest && status == NodeStatus.LEADER) {
            val value = env.log.filter { it.committed }.lastOrNull { it.key == message.key }?.value
            env.send(GetResponse(hash = message.hash, value = value, term = currentTerm), sender)
            return
        }
        if (message is PutRequest && status == NodeStatus.LEADER) {
            if (env.log.any { it.hash == message.hash }) {
                if (env.log.any { it.hash == message.hash && it.committed }) {
                    env.send(PutResponse(currentTerm, message.hash), sender)
                }
                return
            }
            val log = Log(
                key = message.key,
                value = message.value,
                term = currentTerm,
                sender = sender,
                hash = message.hash
            )
            replicateLog(log)
            return
        }
        if (currentTerm > message.term) return
        updateTerm(message, sender)
        when (message) {
            is RequestVote -> {
                if (votedFor == null &&
                    message.count >= storage.size()
                ) {
                    env.send(VoteResponse(currentTerm, true), sender)
                    votedFor = sender
                } else {
                    env.send(VoteResponse(currentTerm, false), sender)
                }
            }
            is VoteResponse -> {
                if (message.res) receivedOks++
                if (receivedOks >= quorum) {
                    currentLeader = env.nodeId
                    status = NodeStatus.LEADER
                    electionSemaphore.signal()
                }
            }
            is Heartbeat -> {
                missedHeartbeatCnt = 0
                status = NodeStatus.FOLLOWER
                currentLeader = sender
                updateCommittedEntries(message.lastCommittedEntry)
                electionSemaphore.signal()
            }
            is ApplyEntryResponse -> processApplyEntryResponse(message, sender)
            is ApplyEntryRequest -> processApplyEntryRequest(message, sender)
            is MissingEntryResponse -> processMissingEntriesResponse(message, sender)
            is GetRequest -> return // If we're the leader, the request was already processed
            is PutRequest -> return // If we're the leader, the request was already processed
            is PutResponse -> {
                operations[message.hash]?.result = message
                operations[message.hash]?.signal?.signal()
            }
            is GetResponse -> {
                operations[message.hash]?.result = message
                operations[message.hash]?.signal?.signal()
            }
        }
    }

    private suspend fun processApplyEntryRequest(message: ApplyEntryRequest, sender: Int) {
        if (currentLeader != sender) {
            return
        }
        recoveredEntries.add(message.logEntry)
        if (storage.checkEntry(message.prevLogNumber)) {
            storage.applyEntries(recoveredEntries.asReversed(), message.prevLogNumber?.index ?: -1)
            val entry = storage.getLastEntry()
            env.send(ApplyEntryResponse(currentTerm, entry!!), sender)
        } else {
            env.send(MissingEntryResponse(currentTerm, message.prevLogNumber), sender)
        }
    }

    private suspend fun processApplyEntryResponse(message: ApplyEntryResponse, sender: Int) {
        if (status != NodeStatus.LEADER) {
            return
        }
        logEntriesCommitResponses[message.logNumber] = logEntriesCommitResponses[message.logNumber]!! + 1
        if (logEntriesCommitResponses[message.logNumber]!! < quorum) {
            return
        }
        if (storage.commit(message.logNumber)) {
            env.send(
                PutResponse(
                    currentTerm,
                    storage[message.logNumber].hash
                ), storage[message.logNumber].sender
            )
        }
    }

    private suspend fun processMissingEntriesResponse(message: MissingEntryResponse, sender: Int) {
        val prevEntry = storage.prevEntry(message.prevLogNumber!!)
        env.send(ApplyEntryRequest(currentTerm, storage[message.prevLogNumber], prevEntry), sender)
    }

    private suspend fun updateCommittedEntries(lastCommittedEntry: LogEntryNumber?) {
        if (lastCommittedEntry == null) return
        if (!storage.commit(lastCommittedEntry)) {
            env.send(
                MissingEntryResponse(currentTerm, lastCommittedEntry),
                currentLeader!!
            )
        }
    }

    private fun onElectionSuccess() {
        env.setTimer("Heartbeat", HEARTBEAT_RATE) {
            env.broadcast(Heartbeat(currentTerm, storage.getLastCommittedEntry()))
        }
    }

    private fun updateTerm(message: Message, sender: Int) {
        if (message.term > currentTerm) {
            if (status == NodeStatus.LEADER) {
                env.cancelTimer("Heartbeat")
            }
            currentTerm = message.term
            status = NodeStatus.FOLLOWER
            electionSemaphore.signal()
        }
    }

    override suspend fun recover() {
        currentTerm = storage.getLastTerm()
    }

    private suspend fun startElection() {
        currentLeader = null
        recoveredEntries.clear()
        while (true) {
            val timeToSleep = Random.nextInt(env.numberOfNodes)
            env.sleep(timeToSleep)
            currentTerm++
            status = NodeStatus.CANDIDATE
            votedFor = env.nodeId
            receivedOks = 1
            val index = storage.size()
            env.broadcast(RequestVote(currentTerm, index, env.nodeId))
            env.withTimeout(3) {
                electionSemaphore.await()
            }
            if (status == NodeStatus.LEADER) {
                onElectionSuccess()
            }
            if (status != NodeStatus.CANDIDATE) {
                return
            }
        }
    }

    private fun constructHash() : String = "${hashCode()}#${env.nodeId}#$opId"

    @Operation
    suspend fun get(key : Int): Int? {
        opId++
        val hash = constructHash()
        val promise = OperationPromise(Signal())
        operations[hash] = promise
        while (true) {
            env.broadcast(GetRequest(currentTerm, key, hash), skipItself = false)
            env.withTimeout(5) {
                promise.signal.await()
            }
            if (promise.result != null) {
                return (promise.result as GetResponse).value
            }
        }
    }

    @Operation
    suspend fun put(key : Int, value: Int) {
        opId++
        val hash = constructHash()
        val promise = OperationPromise(Signal())
        operations[hash] = promise
        while (true) {
            env.broadcast(PutRequest(currentTerm, key, value, hash), false)
            env.withTimeout(15) {
                promise.signal.await()
            }
            if (promise.result != null) {
                return
            }
        }
    }
}
