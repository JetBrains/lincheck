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


data class LogNumber(val term: Int, val index: Int)
data class KVEntry(val key: String?, val value: String?)
data class ReqId(val client: Int, val opId: Int)

sealed class RKVData

data class RKVLog(
    val entry: KVEntry,
    val number: LogNumber,
    val reqId: ReqId,
    var committed: Boolean = false
) : RKVData()

data class RKVVote(var votedFor: Int?) : RKVData()
data class RKVTerm(var term: Int) : RKVData()
data class RKVOpId(var id: Int) : RKVData()

sealed class RKVMessage {
    abstract val term: Int
}

data class RKVVoteRequest(override val term: Int, val number: LogNumber?) : RKVMessage()

data class RKVVoteResponse(override val term: Int, val res: Boolean) : RKVMessage()

data class RKVPing(override val term: Int, val opId: Int) : RKVMessage()

data class RKVPong(override val term: Int, val opId: Int) : RKVMessage()

data class RKVHeartbeat(override val term: Int, val lastCommitted: LogNumber?) : RKVMessage()

data class RKVPutRequest(override val term: Int, val opId: Int, val kv: KVEntry) : RKVMessage()

data class RKVPutResponse(override val term: Int, val opId: Int) : RKVMessage()

data class RKVApplyEntryRequest(
    val logNumber: LogNumber,
    val entry: KVEntry,
    val requestId: ReqId,
    val prevEntry: LogNumber?,
    override val term: Int,
    val lastCommitted: LogNumber?
) : RKVMessage()

data class RKVApplyEntryResponse(val logNumber: LogNumber, override val term: Int) : RKVMessage()
data class RKVMissingEntryResponse(val logNumber: LogNumber, override val term: Int) : RKVMessage()
