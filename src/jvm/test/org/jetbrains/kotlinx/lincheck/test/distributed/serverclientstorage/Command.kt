/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.distributed.serverclientstorage

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState

sealed class Command {
    abstract val id: Int
}

data class GetCommand(val key: Int, override val id: Int) : Command()
data class PutCommand(val key: Int, val value: Int, override val id: Int) : Command()
data class AddCommand(val key: Int, val value: Int, override val id: Int) : Command()
data class ContainsCommand(val key: Int, override val id: Int) : Command()
data class RemoveCommand(val key: Int, override val id: Int) : Command()
data class GetResult(val res: Int?, override val id: Int) : Command()
data class PutResult(val res: Int?, override val id: Int) : Command()
data class AddResult(val res: Int?, override val id: Int) : Command()
data class ContainsResult(val res: Boolean, override val id: Int) : Command()
data class RemoveResult(val res: Int?, override val id: Int) : Command()
data class ErrorResult(val error: Throwable, override val id: Int) : Command()


class SingleNode : VerifierState() {
    private val storage = mutableMapOf<Int, Int>()

    @Operation
    suspend fun contains(key: Int) = storage.contains(key)

    @Operation
    suspend fun put(key: Int, value: Int) = storage.put(key, value)

    @Operation
    suspend fun get(key: Int) = storage[key]

    @Operation
    suspend fun remove(key: Int) = storage.remove(key)

    @Operation
    suspend fun add(key: Int, value: Int) = storage.put(key, storage.getOrDefault(key, 0) + value)
    override fun extractState(): Any = storage
}