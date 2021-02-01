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

package org.jetbrains.kotlinx.lincheck.test.distributed.kvstorage

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import java.lang.RuntimeException
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.HashMap
import kotlin.concurrent.withLock


class KVStorageIncorrect(private val environment: Environment<Command>) : Node<Command> {
    private val lock = ReentrantLock()
    private var commandId = 0
    private val commandResults = Array(environment.numberOfNodes) { HashMap<Int, Command>()}
    private val storage = Collections.synchronizedMap(
        HashMap<Int, Int>())

    private val queue = LinkedBlockingQueue<Command>()

    @StateRepresentation
    fun stateRepresentation(): String {
        return commandResults.toString()
    }

    override fun onMessage(message: Command, sender : Int) {
        if (environment.nodeId != 0) {
            queue.add(message)
            return
        }
       // println("Received $message")
        val id = message.id
        if (commandResults[sender].containsKey(id)) {
            environment.send(
                commandResults[sender][id]!!,
                sender)
            return
        }
        val result : Command = lock.withLock {
            try {
                when (message) {
                    is ContainsCommand -> ContainsResult(storage.containsKey(message.key), id)
                    is GetCommand -> GetResult(storage[message.key], id)
                    is PutCommand -> PutResult(storage.put(message.key, message.value), id)
                    is RemoveCommand -> RemoveResult(storage.remove(message.key), id)
                    else -> throw RuntimeException("Unexpected command")
                }
            } catch(e : Throwable) {
                ErrorResult(e, id)
            }
        }
        environment.send(result, receiver = sender)
    }

    fun sendOnce(command : Command): Command {
        environment.send(command, 0)
        while (true) {
            val response = queue.poll(1, TimeUnit.MILLISECONDS)
            if (response != null) {
                commandResults[0][response.id] = response
            }
            if (commandResults[0].containsKey(command.id)) {
                val res = commandResults[0][command.id]!!
                if (res is ErrorResult) {
                    throw res.error
                }
                return res
            }
        }
    }

    @Operation
    fun contains(key: Int): Boolean {
        if (environment.nodeId == 0) {
            lock.withLock {
                //println("[${environment.nodeId}]: Contains $key")
                return storage.containsKey(key)
            }
        }
        val response = sendOnce(ContainsCommand(key, commandId++)) as ContainsResult
        //println("[${environment.nodeId}]: Contains $key")
        return response.res
    }

    @Operation
    fun put(key: Int, value: Int): Int? {
        if (environment.nodeId == 0) {
            lock.withLock {
                //println("[${environment.nodeId}]: Put $key $value")
                return storage.put(key, value)
            }
        }
        val response = sendOnce(PutCommand(key, value, commandId++)) as PutResult
        //println("[${environment.nodeId}]: Put $key $value")
        return response.res
    }

    @Operation
    fun remove(key: Int): Int? {
        if (environment.nodeId == 0) {
            lock.withLock {
              //  println("[${environment.nodeId}]: Remove $key")
                return storage.remove(key)
            }
        }
        val response = sendOnce(RemoveCommand(key, commandId++)) as RemoveResult
       // println("[${environment.nodeId}]: Remove $key")
        return response.res
    }


    @Operation
    fun get(key: Int): Int? {
        if (environment.nodeId == 0) {
            lock.withLock {
               // println("[${environment.nodeId}]: Get $key")
                return storage[key]
            }
        }
        val response = sendOnce(GetCommand(key, commandId++)) as GetResult
        //println("[${environment.nodeId}]: Get $key")
        return response.res
    }
}
