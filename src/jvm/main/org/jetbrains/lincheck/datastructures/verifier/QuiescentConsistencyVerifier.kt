/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.datastructures.verifier

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import java.util.*
import kotlin.collections.ArrayList

/**
 * This verifier tests for quiescent consistency.
 * Note that it does not support quiescent points.
 * Thus, it potentially does not find some bugs.
 * However, we believe that quiescent points do not occur
 * in practice while supporting them complicates the implementation.
 */
class QuiescentConsistencyVerifier(sequentialSpecification: Class<*>) : Verifier {
    private val linearizabilityVerifier = LinearizabilityVerifier(sequentialSpecification)
    private val scenarioMapping: MutableMap<ExecutionScenario, ExecutionScenario> = WeakHashMap()

    override fun verifyResults(scenario: ExecutionScenario, results: ExecutionResult): Boolean {
        val convertedScenario = scenario.converted
        val convertedResults = results.convert(scenario, convertedScenario.nThreads)
        checkScenarioAndResultsAreSimilarlyConverted(convertedScenario, convertedResults)
        return linearizabilityVerifier.verifyResults(convertedScenario, convertedResults)
    }

    private val ExecutionScenario.converted: ExecutionScenario get() = scenarioMapping.computeIfAbsent(this) {
        val parallelExecutionConverted = ArrayList<MutableList<Actor>>()
        repeat(nThreads) {
            parallelExecutionConverted.add(ArrayList())
        }
        parallelExecution.forEachIndexed { t, threadActors ->
            for (a in threadActors) {
                if (a.isQuiescentConsistent) {
                    parallelExecutionConverted.add(mutableListOf(a))
                } else {
                    parallelExecutionConverted[t].add(a)
                }
            }
        }
        ExecutionScenario(initExecution, parallelExecutionConverted, postExecution, validationFunction)
    }

    private fun ExecutionResult.convert(originalScenario: ExecutionScenario, newThreads: Int): ExecutionResult {
        val parallelResults = ArrayList<MutableList<ResultWithClock>>()
        repeat(originalScenario.nThreads) {
            parallelResults.add(ArrayList())
        }
        val clocks = Array(originalScenario.nThreads) { ArrayList<IntArray>() }
        val clockMapping = Array(originalScenario.nThreads) { ArrayList<Int>() }
        clockMapping.forEach { it.add(-1) }
        originalScenario.parallelExecution.forEachIndexed { t, threadActors ->
            threadActors.forEachIndexed { i, a ->
                val r = parallelResultsWithClock[t][i]
                if (a.isQuiescentConsistent) {
                    clockMapping[t].add(clockMapping[t][i])
                    // null result is not impossible here as if the execution has hung, we won't check its result
                    parallelResults.add(mutableListOf(r.result!!.withEmptyClock(newThreads)))
                } else {
                    clockMapping[t].add(clockMapping[t][i] + 1)
                    val c = IntArray(newThreads) { 0 }
                    clocks[t].add(c)
                    parallelResults[t].add(ResultWithClock(r.result, HBClock(c)))
                }
            }
        }
        clocks.forEachIndexed { t, threadClocks ->
            threadClocks.forEachIndexed { i, c ->
                for (j in 0 until originalScenario.nThreads) {
                    val old = parallelResultsWithClock[t][i].clockOnStart[j]
                    c[j] = if (old == -1) -1 else clockMapping[j][old]
                }
            }
        }
        return ExecutionResult(initResults, parallelResults, postResults)
    }

    private fun checkScenarioAndResultsAreSimilarlyConverted(scenario: ExecutionScenario, results: ExecutionResult) {
        check(scenario.initExecution.size == results.initResults.size) {
            "Transformed scenario and results have different number of operations in init parts"
        }
        check(scenario.postExecution.size == results.postResults.size) {
            "Transformed scenario and results have different number of operations in post parts"
        }
        check(scenario.parallelExecution.size == results.parallelResultsWithClock.size) {
            "Transformed scenario and results have different number of parallel threads"
        }
        for (t in 0 until scenario.nThreads) {
            check(scenario.parallelExecution[t].size == results.parallelResultsWithClock[t].size) {
                "Transformed scenario and resutls have different number of operations in thread $t"
            }
        }
    }
 }

private val Actor.isQuiescentConsistent: Boolean get() = method.isAnnotationPresent(QuiescentConsistent::class.java)

/**
 * This annotation indicates that the method it is presented on
 * is quiescent consistent.
 *
 * @see QuiescentConsistencyVerifier
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class QuiescentConsistent
