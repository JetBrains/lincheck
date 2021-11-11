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

sealed class PersistentObject

data class LogEntry(val key: String, val value: String, val term: Int) : PersistentObject()
data class Term(var currentTerm: Int) : PersistentObject()
data class VotedFor(var votedFor: Int?) : PersistentObject()
data class KeyValue(val key: String, var value: String) : PersistentObject()

class PersistentStorage(private val storage: MutableList<PersistentObject>) {
    init {
        if (storage.none { it is Term }) {
            check(storage.isEmpty())
            storage.add(Term(0))
        }
        if (storage.none { it is VotedFor }) {
            check(storage.size == 1)
            storage.add(VotedFor(null))
        }
    }

    var term: Int
        get() = (storage[0] as Term).currentTerm
        set(value) {
            (storage[0] as Term).currentTerm = value
        }

    var votedFor: Int?
        get() = (storage[1] as VotedFor).votedFor
        set(value) {
            (storage[1] as VotedFor).votedFor = value
        }

    val lastLogIndex: Int
        get() = storage.filterIsInstance<LogEntry>().size - 1

    /*val lastLogTerm : Int
        get() = storage.*/

    fun applyToStateMachine(lastApplied: Int) {
        val entry = storage.filterIsInstance<LogEntry>()[lastApplied]
        val kv = storage.find { it is KeyValue && it.key == entry.key }
        if (kv == null) {
            storage.add(KeyValue(entry.key, entry.value))
            return
        }
        (kv as KeyValue).value = entry.value
    }
}