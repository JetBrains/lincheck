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

sealed class RaftMessage {
    abstract val term: Int
}

sealed interface ResponseToClient

/**
 * Send by leader to replicate log index. Also used as heartbeat.
 * [term] leader's term
 * [leaderId] leader's id so the follower can redirect requests
 * [prevLogIndex] index of log entry immediately preceding new ones
 * [prevLogTerm] term of prevLogIndex entry
 * [entries] log entries to store (empty for heartbeat; may send more than one for efficiency)
 * [leaderCommit] leader’s commitIndex
 */
data class AppendEntries(
    override val term: Int,
    val prevLogIndex: Int,
    val prevLogTerm: Int,
    val entries: List<LogEntry>,
    val leaderCommit: Int
) : RaftMessage()

/**
 * Response to the [AppendEntries].
 * [term] currentTerm, for leader to update itself
 * [success] true if follower contained entry matching prevLogIndex and prevLogTerm
 */
data class AppendEntriesResponse(override val term: Int, val success: Boolean, val lastLogIndex: Int) : RaftMessage()

/**
 * Send by candidates to gather votes.
 * [term] candidate’s term
 * [candidateId] candidate requesting vote
 * [lastLogIndex] index of candidate’s last log entry
 * [lastLogTerm] term of candidate’s last log entry
 */
data class RequestVote(override val term: Int, val candidateId: Int, val lastLogIndex: Int, val lastLogTerm: Int) :
    RaftMessage()

/**
 * Response to the [RequestVote].
 * [term] currentTerm, for candidate to update itself
 * [voteGranted] true means candidate received vote
 */
data class RequestVoteResponse(override val term: Int, val voteGranted: Boolean) : RaftMessage()

data class ClientRequest(val command: Command, override val term: Int = -1) : RaftMessage()
data class NotALeader(val leaderId: Int, override val term: Int = -1) : RaftMessage(), ResponseToClient
data class LeaderUnknown(override val term: Int = -1) : RaftMessage(), ResponseToClient
data class RejectOperation(override val term: Int = -1) : RaftMessage(), ResponseToClient
data class ClientResult(val res: String?, val commandId: CommandId, override val term: Int = -1) : RaftMessage(),
    ResponseToClient
