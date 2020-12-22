/*
* #%L
* Lincheck
* %%
* Copyright (C) 2015 - 2018 Devexperts, LLC
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
package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.CTestConfiguration
import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.CTestStructure.OperationGroup
import kotlin.random.Random

class RandomExecutionGenerator(testConfiguration: CTestConfiguration, testStructure: CTestStructure) : ExecutionGenerator(testConfiguration, testStructure) {
    private val random = Random(0)
    override fun nextExecution(): ExecutionScenario {
        // Create init execution part
        val validActorGeneratorsForInit = testStructure.actorGenerators.filter { ag: ActorGenerator -> !ag.useOnce && !ag.isSuspendable }
        val initExecution: MutableList<Actor> = ArrayList()
        run {
            var i = 0
            while (i < testConfiguration.actorsBefore && validActorGeneratorsForInit.isNotEmpty()) {
                val ag = validActorGeneratorsForInit[random.nextInt(validActorGeneratorsForInit.size)]
                initExecution.add(ag.generate(0))
                i++
            }
        }
        // Create parallel execution part
        // Construct non-parallel groups and parallel one
        val nonParallelGroups = testStructure.operationGroups.filter { g: OperationGroup -> g.nonParallel }.shuffled()
        val parallelGroup: MutableList<ActorGenerator> = ArrayList(testStructure.actorGenerators)
        nonParallelGroups.forEach { g: OperationGroup -> parallelGroup.removeAll(g.actors) }
        val parallelExecution: MutableList<MutableList<Actor>> = ArrayList()
        val threadGens: MutableList<ThreadGen> = ArrayList()
        for (t in 0 until testConfiguration.threads) {
            parallelExecution.add(ArrayList())
            threadGens.add(ThreadGen(t, testConfiguration.actorsPerThread))
        }
        for (i in nonParallelGroups.indices) {
            threadGens[i % threadGens.size].nonParallelActorGenerators
                    .addAll(nonParallelGroups[i]!!.actors)
        }
        val tgs2: List<ThreadGen> = ArrayList(threadGens)
        while (threadGens.isNotEmpty()) {
            val it = threadGens.iterator()
            while (it.hasNext()) {
                val threadGen = it.next()
                val aGenIndexBound = threadGen.nonParallelActorGenerators.size + parallelGroup.size
                if (aGenIndexBound == 0) {
                    it.remove()
                    continue
                }
                val aGenIndex = random.nextInt(aGenIndexBound)
                val agen: ActorGenerator = if (aGenIndex < threadGen.nonParallelActorGenerators.size) {
                    getActorGenFromGroup(threadGen.nonParallelActorGenerators, aGenIndex)
                } else {
                    getActorGenFromGroup(parallelGroup,
                            aGenIndex - threadGen.nonParallelActorGenerators.size)
                }
                parallelExecution[threadGen.iThread].add(agen.generate(threadGen.iThread + 1))
                if (--threadGen.left == 0) it.remove()
            }
        }
        parallelExecution.retainAll { actors: List<Actor> -> actors.isNotEmpty() }
        // Create post execution part if the parallel part does not have suspendable actors
        val postExecution: MutableList<Actor>
        if (parallelExecution.none { actors: List<Actor> -> actors.any(Actor::isSuspendable) }) {
            postExecution = ArrayList()
            val leftActorGenerators: MutableList<ActorGenerator> = ArrayList(parallelGroup)
            for (threadGen in tgs2) leftActorGenerators.addAll(threadGen.nonParallelActorGenerators)
            var i = 0
            while (i < testConfiguration.actorsAfter && leftActorGenerators.isNotEmpty()) {
                val agen = getActorGenFromGroup(leftActorGenerators, random.nextInt(leftActorGenerators.size))
                postExecution.add(agen.generate(testConfiguration.threads + 1))
                i++
            }
        } else {
            postExecution = arrayListOf()
        }
        return ExecutionScenario(initExecution, parallelExecution, postExecution)
    }

    private fun getActorGenFromGroup(aGens: List<ActorGenerator>, index: Int): ActorGenerator {
        val aGen = aGens[index]
        if (aGen.useOnce) (aGens as MutableList<ActorGenerator>).removeAt(index)
        return aGen
    }

    private class ThreadGen(var iThread: Int, var left: Int) {
        val nonParallelActorGenerators: MutableList<ActorGenerator> = ArrayList()
    }
}