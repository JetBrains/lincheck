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

data class CommandId(val client: Int, val opId: Int)
sealed class Command {
    abstract val id: CommandId
}

data class PutCommand(override val id: CommandId, val key: String, val value: String) : Command()
data class GetCommand(override val id: CommandId, val key: String) : Command()

data class LogEntry(val command: Command?, val term: Int)

/**
 * [currentTerm] latest term server has seen (initialized to 0 on first boot, increases monotonically)
 * [votedFor] candidateId that received vote in current term (or null if none)
 * [log] log entries; each entry contains command for state machine, and term when entry was received by leader (first index is 1)
 */
class PersistentStorage {
    var currentTerm = 0
    var votedFor: Int? = null
    private val log = mutableListOf<LogEntry>()
    private val keyValueStorage = mutableMapOf<String, String>()
    val logSize: Int
        get() = log.size

    val lastLogIndex: Int
        get() = log.size - 1

    val lastLogTerm: Int
        get() = if (log.isEmpty()) 0
        else log.last().term

    fun isMoreUpToDate(lastLogIndex: Int, lastLogTerm: Int): Boolean {
        return this.lastLogIndex > lastLogIndex || this.lastLogTerm > lastLogTerm
    }

    fun containsEntry(index: Int, term: Int): Boolean {
        return index == -1 || index < log.size && log[index].term == term
    }

    /**
     * 1. If the entry is in the log (same index and term), do nothing.
     * 2. If an existing entry conflicts with a new one
     * (same index but different terms),
     * delete the existing entry and all that follow it.
     * 3. Add new entry.
     */
    fun appendEntry(command: Command?, index: Int, term: Int) {
        if (index < log.size && log[index].term == term) return
        for (i in index until logSize) {
            log.removeLast()
        }
        check(log.size == index)
        log.add(LogEntry(command, term))
    }

    fun prevTermForIndex(index: Int) = if (index == 0) 0
    else log[index - 1].term

    fun appendEntry(command: Command?) {
        log.add(LogEntry(command, currentTerm))
    }

    fun getEntriesFromIndex(index: Int): List<LogEntry> {
        return log.drop(index)
    }

    fun getEntriesSlice(from: Int, to: Int): List<LogEntry> = log.slice(from..to)

    fun containsCommandId(commandId: CommandId) = log.any { it.command?.id == commandId }
}

class StateMachine {
    private val storage = mutableMapOf<String, String>()
    private val results = mutableMapOf<CommandId, String?>()

    fun applyEntries(entries: List<LogEntry>): List<Pair<CommandId, String?>> =
        entries.mapNotNull { it.command }.map { c ->
            val res = when (c) {
                is GetCommand -> storage[c.key]
                is PutCommand -> storage.put(c.key, c.value)
            }
            results[c.id] = res
            c.id to res
        }


    fun isPresent(commandId: CommandId) = results.contains(commandId)

    fun getResult(commandId: CommandId) = results[commandId]
}