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

package org.jetbrains.kotlinx.lincheck.test.distributed.broadcast

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.Environment


class PeerIncorrect(env: Environment<Message, MutableList<Message>>) : AbstractPeer(env) {
    private val receivedMessages = Array<HashSet<Int>>(env.numberOfNodes) { HashSet() }
    private var messageId = 0

    override fun onMessage(message: Message, sender: Int) {
        val msgId = message.id
        if (!receivedMessages[message.from].contains(msgId)) {
            receivedMessages[message.from].add(msgId)
            env.database.add(message)
            env.broadcast(message)
        }
    }

    @Operation
    fun send(msg: String) {
        val message = Message(body = msg, id = messageId++, from = env.nodeId)
        env.broadcast(message, skipItself = false)
    }

    override fun stateRepresentation(): String {
        return env.database.toString()
    }
}
