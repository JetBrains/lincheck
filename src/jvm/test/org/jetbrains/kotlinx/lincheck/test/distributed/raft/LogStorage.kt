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

    fun getLastNumber() = log.filterIsInstance<RKVLog>().lastOrNull()?.number

    fun getPrevEntry(logNumber: LogNumber): LogNumber? {
        val logs = log.filterIsInstance<RKVLog>()
        val index = logs.indexOfLast { it.number == logNumber }
        check(index >= 0)
        return if (index == 0) {
            null
        } else {
            logs[index - 1].number
        }
    }

    fun getLastCommittedEntry() = log.filterIsInstance<RKVLog>().lastOrNull { it.committed }?.number

    fun commit(logNumber: LogNumber): ReqId? {
        val logs = log.filterIsInstance<RKVLog>()
        val index = logs.indexOfLast { it.number == logNumber }
        if (index == -1) return null
        for (i in 0..index) {
            logs[i].committed = true
        }
        return logs[index].reqId
    }

    fun contains(logNumber: LogNumber) = log.any { it is RKVLog && it.number == logNumber }

    fun add(request: RKVApplyEntryRequest) {
        check(!contains(request.logNumber)) {
            request
        }
        log.add(RKVLog(request.entry, request.logNumber, request.requestId))
    }

    fun add(entry: KVEntry, reqId: ReqId, term: Int): Pair<LogNumber?, Boolean> {
        val logs = log.filterIsInstance<RKVLog>()
        val indexOfEntry = logs.indexOfLast { it.entry.key != null && it.reqId == reqId }
        return if (indexOfEntry == -1) {
            val index = logs.size
            val number = LogNumber(index = index, term = term)
            val newEntry = RKVLog(entry, number, reqId)
            log.add(newEntry)
            number to false
        } else {
            null to logs[indexOfEntry].committed
        }
    }

    fun getKV(logNumber: LogNumber): RKVLog {
        return log.last { it is RKVLog && it.number == logNumber } as RKVLog
    }

    fun clearBefore(logNumber: LogNumber?) {
        val logs = log.filterIsInstance<RKVLog>()
        val toRemove = logs.takeWhile { it.number != logNumber }
        log.removeIf { it in toRemove }
    }
}