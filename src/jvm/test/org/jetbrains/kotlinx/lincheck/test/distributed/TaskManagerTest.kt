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
    private fun addMessageTask(manager: TaskManager, from: Int, to: Int) =
        MessageReceiveTask(to, from) {}.also { manager.addTask(it) }

    private fun addOperationTask(manager: TaskManager, iNode: Int) =
        OperationTask(iNode) {}.also { manager.addTask(it) }

    private fun addTimeTask(manager: TaskManager, ticks: Int, iNode: Int) =
        Timer(ticks, iNode) {}.also { manager.addTimeTask(it) }

    @Test
    fun testFifoTaskManager() {
        val manager = FifoTaskManager()
        val operation = addOperationTask(manager, 0)
        val message1 = addMessageTask(manager, 0, 1)
        val message2 = addMessageTask(manager, 0, 0)
        val message3 = addMessageTask(manager, 0, 1)
        check(manager.getAvailableTasks() == mapOf(0 to operation, 1 to message1, 2 to message2))
        check(manager.getTaskById(1) == message1)
        check(manager.getAvailableTasks() == mapOf(0 to operation, 2 to message2, 3 to message3))
    }

    @Test
    fun testNoFifo() {
        val manager = NoFifoTaskManager()
        val operation = addOperationTask(manager, 0)
        val message1 = addMessageTask(manager, 0, 1)
        val message2 = addMessageTask(manager, 0, 0)
        val message3 = addMessageTask(manager, 0, 1)
        check(manager.getAvailableTasks() == mapOf(0 to operation, 1 to message1, 2 to message2, 3 to message3))
    }

    private fun testTimerWithoutOtherTasks(manager: TaskManager) {
        val task = addTimeTask(manager, 10, 0)
        check(manager.getAvailableTasks() == mapOf(10 to task))
    }

    @Test
    fun testFifoManagerTimerWithoutOtherTasks() = testTimerWithoutOtherTasks(FifoTaskManager())

    @Test
    fun testNoFifoManagerTimerWithoutOtherTasks() = testTimerWithoutOtherTasks(NoFifoTaskManager())

    @Test
    fun testFifoWithTimers() {
        val manager = FifoTaskManager()
        val operation1 = addOperationTask(manager, 0)
        println(manager.counter)
        val operation2 = addOperationTask(manager, 1)
        println(manager.counter)
        val operation3 = addOperationTask(manager, 2)
        println(manager.counter)
        check(manager.getTaskById(0) == operation1)
        val timer = addTimeTask(manager, 10, 1)
        check(manager.getAvailableTasks() == mapOf(1 to operation2, 2 to operation3))
        check(manager.getTaskById(1) == operation2)
        check(manager.getTaskById(2) == operation3)
        println(manager.getAvailableTasks())
    }
}