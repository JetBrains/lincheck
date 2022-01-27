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

package org.jetbrains.kotlinx.lincheck.test.distributed.totalorder

import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.event.Event
import org.jetbrains.kotlinx.lincheck.distributed.event.MessageSentEvent

abstract class OrderCheckNode(val env: Environment<Message, MutableList<Message>>) :
    Node<Message, MutableList<Message>> {
    override fun validate(events: List<Event>, databases: List<MutableList<Message>>) {
        // Check total order
        for (storedMessages in databases) {
            for (i in storedMessages.indices) {
                for (j in i + 1 until storedMessages.size) {
                    check(databases.none {
                        val first = it.lastIndexOf(storedMessages[i])
                        val second = it.lastIndexOf(storedMessages[j])
                        first != -1 && second != -1 && first >= second
                    })
                }
            }
        }
        val sent = events.filterIsInstance<MessageSentEvent<Message>>().map { it.message }
            .filterIsInstance<RequestMessage>()
        sent.forEach { m ->
            check(databases.filterIndexed { index, _ -> index != m.from }.all { it.contains(m) })
        }
    }
}