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

package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.distributed.event.MessageSentEvent
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method
import kotlin.random.Random

internal abstract class DistributedStrategy<Message, DB>(
    val testCfg: DistributedCTestConfiguration<Message, DB>,
    protected val testClass: Class<*>,
    scenario: ExecutionScenario,
    protected val validationFunctions: List<Method>,
    protected val stateRepresentationFunction: Method?,
    protected val verifier: Verifier
) : Strategy(scenario) {
    protected lateinit var crashInfo: CrashInfo<Message, DB>

    fun initialize() {
        crashInfo = CrashInfo.createCrashInfo(testCfg.addressResolver, this)
    }

    fun crashOrReturnRate(event: MessageSentEvent<Message>): Int {
        val iNode = event.iNode
        if (!crashInfo.canSend(iNode, event.receiver)) return 0
        tryAddCrashBeforeSend(iNode, event)
        if (tryAddPartitionBeforeSend(iNode, event)) return 0
        return getMessageRate(iNode, event)
    }

    abstract fun onMessageSent(iNode: Int, event: MessageSentEvent<Message>)

    abstract fun beforeLogModify(iNode: Int)

    abstract fun next(taskManager: TaskManager): Task?

    fun onNodeRecover(iNode: Int) {
        crashInfo.recoverNode(iNode)
    }

    protected abstract fun tryAddCrashBeforeSend(iNode: Int, event: MessageSentEvent<Message>)

    protected abstract fun tryAddPartitionBeforeSend(iNode: Int, event: MessageSentEvent<Message>): Boolean

    protected abstract fun getMessageRate(iNode: Int, event: MessageSentEvent<Message>): Int

    abstract fun reset()

    abstract fun choosePartitionComponent(nodes: List<Int>, limit: Int): Set<Int>
}