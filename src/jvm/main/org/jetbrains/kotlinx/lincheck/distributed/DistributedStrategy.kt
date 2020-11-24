package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.CompletedInvocationResult
import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner
import org.jetbrains.kotlinx.lincheck.runner.Runner
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.strategy.toLincheckFailure
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.locks.ReentrantLock

class DistributedStrategy(val testCfg: DistributedCTestConfiguration,
                          testClass: Class<*>,
                          scenario: ExecutionScenario,
                          validationFunctions: List<Method>,
                          stateRepresentationFunction: Method?,
                          private val verifier: Verifier
) : Strategy(scenario) {
    private val invocations = testCfg.invocationsPerIteration
    private val runner: Runner

    init {
        // Create runner
        runner = DistributedRunner(this, testCfg, testClass, validationFunctions, stateRepresentationFunction)
        try {
            runner.initialize()
        } catch (t: Throwable) {
            runner.close()
            throw t
        }
    }

    override fun run(): LincheckFailure? {
        try {
            // Run invocations
            for (invocation in 0 until invocations) {
                println("INVOCATION $invocation")
                val ir = runner.run()
                when (ir) {
                    is CompletedInvocationResult -> {
                        if (!verifier.verifyResults(scenario, ir.results)) {
                            return IncorrectResultsFailure(scenario, ir.results)
                        }

                    }
                    else ->
                    {
                        return ir.toLincheckFailure(scenario)
                    }
                }
            }
            return null
        } finally {
            runner.close()
        }
    }
}
