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

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import kotlin.random.Random

sealed class RKVMessage {
    abstract val term: Int
}

sealed class RKVData

data class LogNumber(val term: Int, val index: Int)
data class KVEntry(val key: String, val value: String)

data class RKVLog(val entry: KVEntry, val number: LogNumber, var committed: Boolean = false) : RKVData()

data class RKVVote(var votedFor: Int?) : RKVData()
data class RKVTerm(var term: Int) : RKVData()
data class RKVOpId(var id: Int) : RKVData()

data class RKVVoteRequest(override val term: Int) : RKVMessage()

data class RKVVoteResponse(override val term: Int, val res: Boolean) : RKVMessage()

data class RKVPing(override val term: Int) : RKVMessage()

data class RKVPong(override val term: Int) : RKVMessage()

data class RKVHeartbeat(override val term: Int) : RKVMessage()

class LogStorage(val log: MutableList<RKVData>) {
    var opIdIndex = log.indexOfLast { it is RKVOpId }
    var votedForIndex = log.indexOfLast { it is RKVVote }
    var termIndex = log.indexOfLast { it is RKVTerm }

    fun getOpId() = if (opIdIndex == -1) {
        -1
    } else {
        (log[opIdIndex] as RKVOpId).id
    }

    fun incOpId() = if (opIdIndex == -1) {
        opIdIndex = log.size
        log.add(RKVOpId(0))
        0
    } else {
        ++(log[opIdIndex] as RKVOpId).id
    }

    fun getVotedFor() = if (votedForIndex == -1) {
        null
    } else {
        (log[votedForIndex] as RKVVote).votedFor
    }

    fun updateVotedFor(vote: Int?) {
        if (votedForIndex == -1) {
            votedForIndex = log.size
            log.add(RKVVote(vote))
        } else {
            (log[votedForIndex] as RKVVote).votedFor = vote
        }
    }

    fun getTerm() = if (termIndex == -1) {
        0
    } else {
        (log[termIndex] as RKVTerm).term
    }

    fun updateTerm(term: Int) {
        if (termIndex == -1) {
            termIndex = log.size
            log.add(RKVTerm(term))
        } else {
            (log[termIndex] as RKVTerm).term = term
        }
    }
}


class Raft(val env: Environment<RMessage, RKVData>) : Node<RMessage> {
    companion object {
        const val HEARTBEAT_RATE = 50
        const val MISSED_HEARTBEAT_LIMIT = 5
    }
    private val storage = LogStorage(env.log)

    private var term = 0
    private var missedHeartbeat = 0
    private var leader: Int? = null
    private var status = NodeStatus.FOLLOWER
    private val random = Random(env.nodeId)
    private var votedFor: Int? = null
    private val electionSignal = Signal()
    private var receivedOks = 0
    private val quorum = (env.numberOfNodes + 1) / 2
    private var operationSignal = Signal()
    private var hasResponse = false
    private var sleepSignal = Signal()

    private suspend fun checkTimer() {
        if (leader != env.nodeId) {
            missedHeartbeat++
            if (missedHeartbeat == MISSED_HEARTBEAT_LIMIT) {
                startElection()
            }
        }
    }

    override suspend fun onMessage(message: RMessage, sender: Int) {
        if (term > message.term) {
            env.recordInternalEvent("Ignore message with less term $message")
            return
        }
        when (status) {
            NodeStatus.LEADER -> {
                when (message) {
                    is RPing -> {
                        env.send(RPong(term), sender)
                    }
                    is RHeartbeat -> {
                        check(term < message.term)
                        term = message.term
                        storage.updateTerm(term)
                        leader = sender
                        onAnotherLeaderElected()
                    }
                    is RVoteRequest -> {
                        if (message.term == term) {
                            env.send(RHeartbeat(term), sender)
                        } else {
                            leader = null
                            term = message.term
                            storage.updateTerm(term)
                            onAnotherLeaderElected()
                            votedFor = sender
                            storage.updateVotedFor(votedFor)
                            env.send(RVoteResponse(term, true), sender)
                        }
                    }
                    else -> env.recordInternalEvent("Ignore message $message")
                }
            }
            NodeStatus.CANDIDATE -> {
                when (message) {
                    is RHeartbeat -> {
                        term = message.term
                        leader = sender
                        storage.updateTerm(term)
                        onElectionFail()
                    }
                    is RVoteRequest -> {
                        if ((votedFor != null || votedFor != sender) && term == message.term) {
                            env.send(RVoteResponse(term, false), sender)
                        } else {
                            term = message.term
                            storage.updateTerm(term)
                            votedFor = sender
                            storage.updateVotedFor(votedFor)
                            env.send(RVoteResponse(term, true), sender)
                            onElectionFail()
                        }
                    }
                    is RVoteResponse -> {
                        if (message.term > term) {
                            term = message.term
                            storage.updateTerm(term)
                            onElectionFail()
                            return
                        }
                        if (!message.res || message.term != term) return
                        receivedOks++
                        if (receivedOks >= quorum) onElectionSuccess()
                    }
                    else -> env.recordInternalEvent("Ignore message $message")
                }
            }
            NodeStatus.FOLLOWER -> {
                when (message) {
                    is RHeartbeat -> {
                        term = message.term
                        storage.updateTerm(term)
                        leader = sender
                        missedHeartbeat = 0
                        operationSignal.signal()
                        sleepSignal.signal()
                    }
                    is RVoteRequest -> {
                        if (votedFor != null && term == message.term) {
                            env.send(RVoteResponse(term, false), sender)
                            return
                        }
                        votedFor = sender
                        storage.updateVotedFor(votedFor)
                        term = message.term
                        storage.updateTerm(term)
                        missedHeartbeat = 0
                        leader = null
                        env.send(RVoteResponse(term, true), sender)
                        sleepSignal.signal()
                    }
                    is RPong -> {
                        if (sender == leader && message.term == term) {
                            hasResponse = true
                            operationSignal.signal()
                        }
                    }
                    else -> env.recordInternalEvent("Ignore message $message")
                }
            }
        }
    }

    override suspend fun onStart() {
        env.recordInternalEvent("Set check on start")
        env.setTimer("CHECK", HEARTBEAT_RATE) {
            checkTimer()
        }
    }

    private fun onElectionFail() {
        if (status != NodeStatus.CANDIDATE) return
        status = NodeStatus.FOLLOWER
        env.recordInternalEvent("Set check in election fail")
        env.setTimer("CHECK", HEARTBEAT_RATE) {
            checkTimer()
        }
        electionSignal.signal()
        receivedOks = 0
        sleepSignal.signal()
    }

    private fun onAnotherLeaderElected() {
        if (status != NodeStatus.LEADER) return
        status = NodeStatus.FOLLOWER
        env.cancelTimer("HEARTBEAT")
        env.recordInternalEvent("Set check in another leader elected")
        env.setTimer("CHECK", HEARTBEAT_RATE) {
            checkTimer()
        }
    }

    private fun onElectionSuccess() {
        env.recordInternalEvent("Election success")
        env.setTimer("HEARTBEAT", HEARTBEAT_RATE) {
            env.broadcast(RHeartbeat(term))
        }
        status = NodeStatus.LEADER
        leader = env.nodeId
        electionSignal.signal()
        receivedOks = 0
        operationSignal.signal()
        sleepSignal.signal()
    }

    override fun stateRepresentation(): String {
        val sb = StringBuilder()
        sb.append("{currentTerm=$term, ")
        sb.append("currentLeader=$leader, ")
        sb.append("status=$status, ")
        sb.append("missedHeartbeat=$missedHeartbeat, ")
        sb.append("receivedOks=$receivedOks, ")
        sb.append("votedFor=$votedFor")
        sb.append("}")
        return sb.toString()
    }

    private suspend fun startElection() {
        env.cancelTimer("CHECK")
        leader = null
        missedHeartbeat = 0
        env.recordInternalEvent("Start election")
        while (true) {
            val currentTerm = term
            val timeToSleep = random.nextInt(env.numberOfNodes * 100)
            env.recordInternalEvent("Time to sleep is $timeToSleep, saved term is $currentTerm")
            sleepSignal = Signal()
            val shouldQuit = env.withTimeout(timeToSleep) {
                sleepSignal.await()
            }
            if (shouldQuit) check(currentTerm != term || leader != null)
            if (currentTerm != term || leader != null) {
                if (leader != env.nodeId) {
                    env.recordInternalEvent("Set check after sleep prevTerm=$currentTerm")
                    try {
                        env.setTimer("CHECK", HEARTBEAT_RATE) {
                            checkTimer()
                        }
                    } catch(_: IllegalArgumentException) {}
                }
                env.recordInternalEvent("Exit election")
                return
            }
            env.recordInternalEvent("Ready to send requests $currentTerm")
            // votedFor = null
            status = NodeStatus.CANDIDATE
            term++
            storage.updateTerm(term)
            receivedOks = 1
            votedFor = env.nodeId
            storage.updateVotedFor(votedFor)
            env.broadcast(RVoteRequest(term))
            if (env.numberOfNodes != 1) {
                env.withTimeout(40) {
                    electionSignal.await()
                }
            } else {
                onElectionSuccess()
            }
            if (status != NodeStatus.CANDIDATE) {
                env.recordInternalEvent("Exit election from end")
                //operationSignal.signal()
                return
            }
        }
    }

    override suspend fun recover() {
        term = storage.getTerm()
        votedFor = storage.getVotedFor()
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun ping() {
        operationSignal = Signal()
        hasResponse = false
        while (true) {
            while (leader == null) operationSignal.await()
            if (leader == null) {
                operationSignal = Signal()
                continue
            }
            if (leader == env.nodeId) return
            env.send(RPing(term), leader!!)
            operationSignal = Signal()
            operationSignal.await()
            if (hasResponse) return
        }
    }

    override suspend fun onScenarioFinish() {
        env.recordInternalEvent("Operations over")
    }

    @Validate
    fun validate() {
        val t = env.events()[env.nodeId].filterIsInstance<InternalEvent>().filter { it.message == "Operations over" }.size
        check(t <= 1)
    }
}

class RaftTest {
    private fun createOptions() = DistributedOptions<RMessage, RKVLog>()
        .requireStateEquivalenceImplCheck(false)
        .threads(5)
        .actorsPerThread(5)
        .invocationTimeout(10_000)
        .invocationsPerIteration(200)
        .iterations(10)
        .invocationTimeout(10_000)
        .verifier(EpsilonVerifier::class.java)
        .storeLogsForFailedScenario("raft_simple.txt")

    @Test
    fun testNoFailures() {
        LinChecker.check(
            Raft::class.java,
            createOptions()
        )
    }

    @Test
    fun testNoRecoveries() {
        LinChecker.check(
            Raft::class.java,
            createOptions().setMaxNumberOfFailedNodes { (it - 1) / 2 }
        )
    }

    @Test
    fun testAllRecover() {
        LinChecker.check(
            Raft::class.java,
            createOptions().setMaxNumberOfFailedNodes { (it - 1) / 2 }
                .supportRecovery(RecoveryMode.ALL_NODES_RECOVER)
        )
    }

    @Test
    fun testMessageLost() {
        LinChecker.check(
            Raft::class.java,
            createOptions().networkReliable(false)
        )
    }

    @Test
    fun testMixed() {
        LinChecker.check(
            Raft::class.java,
            createOptions()
                .networkReliable(false)
                .setMaxNumberOfFailedNodes { (it - 1) / 2 }
                .supportRecovery(RecoveryMode.MIXED)
        )
    }

    @Test
    fun testNetworkPartitions() {
        LinChecker.check(
            Raft::class.java,
            createOptions()
                .networkPartitions(true)
                .setMaxNumberOfFailedNodes { (it - 1) / 2 }
                .supportRecovery(RecoveryMode.MIXED)
        )
    }

    @Test
    fun testNetworkPartitionsOnly() {
        LinChecker.check(
            Raft::class.java,
            createOptions()
                .networkPartitions(true)
                .setMaxNumberOfFailedNodes { (it - 1) / 2 }
                .supportRecovery(RecoveryMode.NO_CRASHES)
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testLargeNumberOfUnavailableNodes() {
        LinChecker.check(
            Raft::class.java,
            createOptions()
                .threads(4)
                .networkPartitions(true)
                .setMaxNumberOfFailedNodes { it / 2 }
                .supportRecovery(RecoveryMode.MIXED)
        )
    }
}
