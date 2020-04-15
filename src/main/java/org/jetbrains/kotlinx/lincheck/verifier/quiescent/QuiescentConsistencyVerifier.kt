/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.verifier.quiescent

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*

/**
 * This verifier tests for quiescent consistency.
 * Note that it does not support quiescent points.
 * Thus, it potentially does not find some bugs.
 * However, we believe that quiescent points do not occur
 * in practice while supporting them complicates the implementation.
 */
class QuiescentConsistencyVerifier(sequentialSpecification: Class<*>) : CachedVerifier() {
    private val linearizabilityVerifier = LinearizabilityVerifier(sequentialSpecification)
    override fun checkStateEquivalenceImplementation() = linearizabilityVerifier.checkStateEquivalenceImplementation()

    override fun verifyResultsImpl(scenario: ExecutionScenario, results: ExecutionResult): Boolean {
        val newThreads = scenario.threads + scenario.parallelExecution.flatten().count { it.isQuiescentConsistent }
        val parallelExecution = ArrayList<MutableList<Actor>>()
        val parallelResults = ArrayList<MutableList<ResultWithClock>>()
        repeat(scenario.threads) {
            parallelExecution.add(ArrayList())
            parallelResults.add(ArrayList())
        }
        val clocks = Array(scenario.threads) { ArrayList<IntArray>() }
        val clockMapping = Array(scenario.threads) { ArrayList<Int>() }
        clockMapping.forEach { it.add(-1) }
        scenario.parallelExecution.forEachIndexed { t, threadActors ->
            threadActors.forEachIndexed { i, a ->
                val r = results.parallelResultsWithClock[t][i]
                if (a.isQuiescentConsistent) {
                    clockMapping[t].add(clockMapping[t][i])
                    parallelExecution.add(mutableListOf(a))
                    parallelResults.add(mutableListOf(r.result.withEmptyClock(newThreads)))
                } else {
                    clockMapping[t].add(clockMapping[t][i] + 1)
                    parallelExecution[t].add(a)
                    val c = IntArray(newThreads) { 0 }
                    clocks[t].add(c)
                    parallelResults[t].add(ResultWithClock(r.result, HBClock(c)))
                }
            }
        }
        clocks.forEachIndexed { t, threadClocks ->
            threadClocks.forEachIndexed { i, c ->
                for (j in 0 until scenario.threads) {
                    val old = results.parallelResultsWithClock[t][i].clockOnStart[j]
                    c[j] = clockMapping[j][old]
                }
            }
        }
        val convertedScenario = ExecutionScenario(scenario.initExecution, parallelExecution, scenario.postExecution)
        val convertedResults = ExecutionResult(results.initResults, parallelResults, results.postResults)
        return linearizabilityVerifier.verifyResultsImpl(convertedScenario, convertedResults)
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
