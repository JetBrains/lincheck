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

package org.jetbrains.kotlinx.lincheck.distributed.modelchecking



/*
class TaskManager(val context: ModelCheckingContext<*, *>) {
    val tasks = mutableMapOf<Int, Task>()
    var currentId = 0
    var currentTask: Pair<Int, Task>? = null
    val path = mutableListOf<Pair<Int, Task>>()

    fun next() = tasks.minByOrNull { it.key }?.toPair()

    suspend fun getNextTaskAndExecute(f: suspend (Task) -> Unit): Boolean {
        val nextSwitch = context.interleaving?.nextSwitch()
        val task = if (nextSwitch != null && nextSwitch.after == currentTask?.first) {
            context.interleaving!!.currentSwitch++ //TODO
            context.currentTreeNode!!.isFinished = true
            context.currentTreeNode = context.currentTreeNode!![nextSwitch]
            if (context.currentTreeNode != null) context.path.add(context.currentTreeNode!!)
            nextSwitch.taskId to tasks[nextSwitch.taskId]!!
        } else next() ?: return false
        path.add(task)
        currentTask = task
        tasks.remove(task.first)
        f(task.second)
        storeSwitchPoints(context.currentTreeNode!!)
        return true
    }

    private fun storeSwitchPoints(curTreeNode: InterleavingTreeNode) {
        if (curTreeNode.isFinished) return
        val id = currentTask!!.first
        val next = next() //TODO if next > id
        val tasksToCheck = if (currentTask!!.second !is NodeCrashTask) tasks.filter {
            (it.key > id || it.value.iNode == currentTask!!.second.iNode) && it.key != next?.first
        } else {
            tasks.filter { it.key != next?.first }
        }
        curTreeNode.unexploredSwitchPoints.addAll(if (context.testCfg.messageOrder == MessageOrder.FIFO) tasksToCheck.filter { t ->
            t.value is MessageReceiveTask && !tasks.any {
                it.value is MessageReceiveTask && it != t && it.value.iNode == t.value.iNode && it.value.clock.happensBefore(
                    t.value.clock
                )
            }
        }.map { Inversion(id, it.key) } else tasksToCheck.map { Inversion(id, it.key) })

    }

    fun getCrashSwitch(msgId: Int, iNode: Int): NodeCrash {
        val taskId = currentId++
        return NodeCrash(after = currentTask!!.first, taskId = taskId, msgId = msgId, iNode = iNode)
    }

    fun getMessageLoseSwitch(msgId: Int): MessageLose {
        val taskId = currentId++
        return MessageLose(after = currentTask!!.first, taskId = taskId, msgId = msgId)
    }

    fun getMessageDuplicationSwitch(msgId: Int): MessageDuplication {
        val taskId = currentId++
        return MessageDuplication(after = currentTask!!.first, taskId = taskId, msgId = msgId)
    }

    fun addTask(task: Task, id: Int? = null) {
        val taskId = id ?: currentId++
        check(!tasks.containsKey(taskId))
        tasks[taskId] = task
        // if (task)
    }

    fun removeTaskForNode(iNode: Int, crashTaskId: Int) {
        val tasksToRemove = tasks.filter { it.value.iNode == iNode && it.key != crashTaskId }.map { it.key }
        tasksToRemove.forEach { tasks.remove(it) }
    }

    fun clear() {
        check(tasks.isEmpty())
       // tasks.clear()
        currentTask = null
        path.clear()
        currentId = 0
    }
}*/