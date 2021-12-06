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

package org.jetbrains.kotlinx.lincheck.test.distributed

import org.jetbrains.kotlinx.lincheck.distributed.*
import org.junit.Test


class TaskManagerTest {
    private fun addMessageTask(manager: TaskManager, from: Int, to: Int, expectedId: Int) =
        manager.addMessageReceiveTask(from, to) {}.also { check(it.id == expectedId) }

    private fun addActionTask(manager: TaskManager, iNode: Int, expectedId: Int) =
        manager.addActionTask(iNode) {}.also { check(it.id == expectedId) }

    private fun addTimer(manager: TaskManager, ticks: Int, iNode: Int, expectedId: Int, expectedTime: Int) =
        manager.addTimer(iNode = iNode, ticks = ticks) {}.also { check(it.id == expectedId && it.time == expectedTime) }

    private fun addTimeout(manager: TaskManager, ticks: Int, iNode: Int, expectedId: Int, expectedTime: Int) =
        manager.addTimeout(iNode = iNode, ticks = ticks) {}.also { check(it.id == expectedId && it.time == expectedTime) }

    @Test
    fun testFifoMessageOrder() {
        val manager = TaskManager(MessageOrder.FIFO)
        val operation = addActionTask(manager, 0, 0)
        val message1 = addMessageTask(manager, 0, 1, 1)
        val message2 = addMessageTask(manager, 0, 1, 2)
        val message3 = addMessageTask(manager, 0, 0, 3)
        check(manager.tasks == listOf(operation, message1, message3))
        manager.removeTask(message1)
        check(manager.tasks == listOf(operation, message2, message3))
    }

    @Test
    fun testAsynchronousOrder() {
        val manager = TaskManager(MessageOrder.ASYNCHRONOUS)
        val operation = addActionTask(manager, 0, 0)
        val message1 = addMessageTask(manager, 0, 1, 1)
        val message2 = addMessageTask(manager, 0, 1, 2)
        val message3 = addMessageTask(manager, 0, 0, 3)
        check(manager.tasks == listOf(operation, message1, message2, message3))
    }

    @Test
    fun testTimeTasks() {
        val manager = TaskManager(MessageOrder.FIFO)
        val timer = addTimer(manager, ticks = 10, iNode = 1, expectedId = 0, expectedTime = 10)
        val operation = addActionTask(manager, iNode = 2, expectedId = 1)
        check(manager.timeTasks == listOf(timer))
        manager.removeTask(operation)
        val timeout = addTimeout(manager, ticks = 5, iNode = 0, expectedId = 2, expectedTime = 6)
        check(manager.timeTasks == listOf(timer, timeout))
        manager.removeTask(timeout)
        check(manager.timeTasks == listOf(timer))
    }

    @Test
    fun testTime() {
        val manager = TaskManager(MessageOrder.FIFO)
        val operation = addActionTask(manager, iNode = 1, expectedId = 0)
        val timer = addTimer(manager, iNode = 1, ticks = 10, expectedId = 1, expectedTime = 10)
        addTimer(manager, iNode = 0, ticks = 20, expectedId = 2, expectedTime = 20)
        val message = addMessageTask(manager, from = 2, to = 1, expectedId = 3)
        check(manager.time == 0)
        manager.removeTask(operation)
        check(manager.time == 1)
        manager.removeTask(message)
        check(manager.time == 10)
        manager.removeTask(timer)
        check(manager.time == 20)
    }
}