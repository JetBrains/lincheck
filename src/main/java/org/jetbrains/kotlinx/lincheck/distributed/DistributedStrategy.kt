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

class DistributedStrategy(val testCfg: DistributedCTestConfiguration,
                          testClass: Class<*>,
                          scenario: ExecutionScenario,
                          private val verifier: Verifier,
                          val validationFunctions: List<Method>?
) : Strategy(scenario) {
    private val invocations = testCfg.invocationsPerIteration
    private val runner: Runner

    init {
        // Create runner
        runner = DistributedRunner(this, testClass, validationFunctions)
    }

    override fun run(): LincheckFailure? {
        try {
            // Run invocations
            for (invocation in 0 until invocations) {
                println("INVOCATION $invocation")
                val ir = runner.run()
                println("Ir ${ir}")
                when (ir) {
                    is CompletedInvocationResult -> {
                        println(verifier)
                        if (!verifier.verifyResults(scenario, ir.results)) {
                            println("Wrong results ${ir.results} ${scenario}")
                            return IncorrectResultsFailure(scenario, ir.results)
                        }

                    }
                    else ->
                    {
                        println("results ${ir.toLincheckFailure(scenario)}")
                        return ir.toLincheckFailure(scenario)
                    }
                }
            }
            println("Return null")
            return null
        } finally {
            println("All")
            runner.close()
        }
    }
}
