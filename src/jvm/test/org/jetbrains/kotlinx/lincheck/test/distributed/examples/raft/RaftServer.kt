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
/*
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import kotlin.random.Random

enum class Status {
    LEADER,
    CANDIDATE,
    FOLLOWER
}

class RaftServer(private val env: Environment<RaftMessage, PersistentObject>) : Node<RaftMessage, PersistentObject> {
    companion object {
        const val HEARTBEAT_RATE = 5
        const val MIN_ELECTION_TIMEOUT : Int = HEARTBEAT_RATE * 4
        const val MAX_ELECTION_TIMEOUT : Int = HEARTBEAT_RATE * 10
    }
    private val storage = PersistentStorage(env.log)
    private var status = Status.FOLLOWER
    private var committedIndex = 0
    private var lastApplied = 0
    private val nextIndices = Array(env.numberOfNodes) {
        storage.lastLogIndex + 1
    }
    private val matchIndices = Array(env.numberOfNodes) {
        0
    }
    private var receivedHeartbeatCount = 0
    private val random = Random(env.nodeId)

    //TODO better names
    private fun setCheckLeaderTimer() {
        env.setTimer("CheckLeader", random.nextInt(from = MIN_ELECTION_TIMEOUT, until = MAX_ELECTION_TIMEOUT)) {
            if (receivedHeartbeatCount == 0) {
                startElection()
            }
            receivedHeartbeatCount = 0
        }
    }

    private fun startElection() {
        if (status == Status.LEADER) return
        storage.term++
        status = Status.CANDIDATE
        storage.votedFor = env.nodeId
        env.broadcast(RequestVote(storage.term, env.nodeId, lastLogIndex = storage.lastLogIndex, ))
    }

    init {

    }

    override fun onMessage(message: RaftMessage, sender: Int) {
        TODO("Not yet implemented")
    }
}

 */
