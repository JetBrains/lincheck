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

import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.random.newResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.CompletedInvocationResult
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.strategy.toLincheckFailure
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method

/*
internal class DistributedModelCheckingStrategy<Message, Log>(
    testCfg: DistributedCTestConfiguration<Message, Log>,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?,
    verifier: Verifier
) : DistributedStrategy<Message, Log>(
    testCfg,
    testClass,
    scenario,
    validationFunctions,
    stateRepresentationFunction,
    verifier
) {
    private val invocations = testCfg.invocationsPerIteration
    private val runner = DistributedRunner(this, testCfg, testClass, validationFunctions, stateRepresentationFunction)


    init {
        // Create runner
        try {
            runner.initialize()
        } catch (t: Throwable) {
            runner.close()
            throw t
        }
    }

    override fun run(): LincheckFailure? {
        println(scenario)
        runner.use { runner ->
            // Run invocations
            var invocation = 0
            var interrupted = 0
            while (invocation < invocations) {
                val ir = runner.run()
                when (ir) {
                    is CompletedInvocationResult -> {
                        if (!verifier.verifyResults(scenario, ir.results)) {
                            val stateRepresentation = runner.constructStateRepresentation()
                            return IncorrectResultsFailure(
                                scenario,
                                ir.results.newResult(stateRepresentation)
                            ).also {
                                runner.storeEventsToFile(it)
                                //println("Found error")
                                // debugLogs.forEach {println(it)}
                            }
                        }
                    }
                    else -> {
                        return ir.toLincheckFailure(scenario).also {
                            runner.storeEventsToFile(it)
                            println("Found error")
                            //debugLogs.forEach {println(it)}
                        }
                    }
                }
            }
            return null
        }
    }

    override fun onMessageSent(iNode: Int, event: MessageSentEvent<Message>) {
        TODO("Not yet implemented")
    }

    override fun beforeLogModify(iNode: Int) {
        TODO("Not yet implemented")
    }

    override fun next(taskManager: TaskManager): Task? {
        TODO("Not yet implemented")
    }

    override fun tryAddCrashBeforeSend(iNode: Int, event: MessageSentEvent<Message>) {
        TODO("Not yet implemented")
    }

    override fun tryAddPartitionBeforeSend(iNode: Int, event: MessageSentEvent<Message>): Boolean {
        TODO("Not yet implemented")
    }

    override fun getMessageRate(iNode: Int, event: MessageSentEvent<Message>): Int {
        TODO("Not yet implemented")
    }

    override fun reset() {
        TODO("Not yet implemented")
    }
}
*/