/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.isValid
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.execution.tryMinimize
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck.util.LincheckResult
import org.jetbrains.kotlinx.lincheck.util.ValueResult
import org.jetbrains.lincheck.datastructures.actor
import org.jetbrains.lincheck.datastructures.scenario
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.indices
import kotlin.concurrent.thread
import kotlin.random.Random

// Test which compare SC-checking of the SC JAMRC11 memory models.
// If all events are volatile, then we should get identical results for the two memory models
class SCTests {

    internal inline fun<reified Outcome> compareScenarios (crossinline scenario: () -> Outcome) {
        var expectedOutcomes = mutableSetOf<Outcome>()
        // TODO: we proably need to abort the test if we hit too many invocations
        litmusTest({ r -> expectedOutcomes.addAll(r) }, MemoryModel.JAM21, 10_000) {
            scenario()
        }

        litmusTest(assertSame(expectedOutcomes), MemoryModel.SequentialConsistency, 10_000) {
            scenario()
        }
    }

    fun compareScenariosAux (scenario: ExecutionScenario): AssertionError? {
        val expectedOutcomes = mutableSetOf<List<Int?>>()
        litmusTest(State::class.java, scenario, { expectedOutcomes.addAll(it) }, MemoryModel.JAM21, 100_000) { results ->
            results.parallelResults.flatMap { r -> r.mapNotNull { mapResult(it) } }
        }
        try {
            litmusTest(State::class.java, scenario, assertSame(expectedOutcomes, UNKNOWN), MemoryModel.SequentialConsistency, 100_000) { results ->
                results.parallelResults.flatMap { r -> r.mapNotNull { mapResult(it) } }
            }
        } catch (e: AssertionError) {
            return e
        }
        return null
    }

    fun compareScenarios (scenario: ExecutionScenario, minimize: Boolean = false) {
        val exception = compareScenariosAux(scenario)
//        if (exception != null) throw exception
        if(exception != null) {
            if (!minimize) throw exception
            val (sc, error) = minimizeError(scenario, exception) { compareScenariosAux(it) }
            println("Minimized scenario:\n$sc")
            throw error
        }
    }

    @Test
    fun testIriw() {
        compareScenarios {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val results = IntArray(4)
            val t1 = thread {
                x.set(1)
            }
            val t2 = thread {
                y.set(1)
            }
            val t3 = thread {
                results[0] = x.get()
                results[1] = y.get()
            }
            val t4 = thread {
                results[2] = y.get()
                results[3] = x.get()
            }

            t1.join()
            t2.join()
            t3.join()
            t4.join()

            results.toList()
        }
    }

    @Test
    fun testIriw2() {
        val forbidden = setOf(listOf(1,0,1,0,0))
        litmusTest(assertNever(forbidden), MemoryModel.SequentialConsistency) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val results = IntArray(5)
            val t1 = thread {
                x.set(1)
            }
            val t2 = thread {
                results[0] = x.get()
                results[1] = y.get()
            }
            val t3 = thread {
                results[2] = y.get()
                results[3] = x.get()
            }
            val t4 = thread {
                results[4] = x.get()
                y.set(1)
            }

            t1.join()
            t2.join()
            t3.join()
            t4.join()

            results.toList()
        }
    }

    @Test
    fun test2Plus2W() {
        compareScenarios {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val r = IntArray(2)
            val t1 = thread {
                x.set(1)
                y.set(2)
                r[0] = y.get()
            }
            val t2 = thread {
                y.set(1)
                x.set(2)
                r[1] = x.get()
            }

            t1.join()
            t2.join()

            r.toList()
        }
    }

    @Test
    fun testManyGet() {
        val sc = scenario {
            parallel {
                thread {
                    actor(State::set, "y", 2)
                }
                thread{
                    actor(State::set, "z", 2)
                    actor(State::set, "y", 11)
                }
                thread {
                    actor(State::get, "y")
                    actor(State::get, "y")
                    actor(State::get, "y")
                }
                thread {
                    actor(State::get, "z")
                    actor(State::set, "y", 10)
                }
            }
        }
        val expectedOutcomes = setOf(listOf(11, 2, 10, 0))
        litmusTest(State::class.java, sc, assertSometimes(expectedOutcomes), MemoryModel.JAM21, 1000) { results ->
            results.parallelResults.flatMap { r -> r.mapNotNull { mapResult(it) } }
        }
    }

    @Test
    fun testExampleSc() {
//        | ---------------------------------------------- |
//        | Thread 1  |  Thread 2  | Thread 3 |  Thread 4  |
//        | ---------------------------------------------- |
//        | set(y, 2) | set(z, 2)  | get(y)   | get(z)     |
//        |           | get(z)     | get(y)   | set(y, 10) |
//        |           | set(y, 11) | get(y)   | get(y)     |
//        |           |            |          | set(y, 13) |
//        | ---------------------------------------------- |
        val sc = scenario {
            parallel {
                thread {
                    actor(State::set, "y", 2)
                }
                thread{
                    actor(State::set, "z", 2)
                    actor(State::set, "y", 11)
                }
                thread {
                    actor(State::get, "y")
                    actor(State::get, "y")
                    actor(State::get, "y")
                }
                thread {
                    actor(State::get, "z")
                    actor(State::set, "y", 10)
                }
            }
        }
        compareScenarios(sc, false)
    }

    @Test
    fun testCompletenessIssue() {
//        | ---------------------------------------------- |
//        | Thread 1  |  Thread 2  | Thread 3 |  Thread 4  |
//        | ---------------------------------------------- |
//        | set(y, 2) | set(z, 2)  | get(y)   | get(z)     |
//        |           | get(z)     | get(y)   | set(y, 10) |
//        |           | set(y, 11) | get(y)   | get(y)     |
//        |           |            |          | set(y, 13) |
//        | ---------------------------------------------- |
        compareScenarios {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            val r = IntArray(6)
            val t1 = thread {
                y.set(1)
            }
            val t2 = thread {
                z.set(2)
                r[0] = z.get()
                y.set(3)
            }
            val t3 = thread {
                r[1] = y.get()
                r[2] = y.get()
                r[3] = y.get()
            }
            val t4 = thread {
                r[4] = z.get()
                y.set(4)
                r[5] = y.get()
                y.set(5)
            }
            t1.join()
            t2.join()
            t3.join()
            t4.join()
            r.toList()
        }
    }



    class State {
        val x = AtomicInteger(0)
        val y = AtomicInteger(0)
        val z = AtomicInteger(0)
        fun set(loc: String, value: Int)  {
            when (loc) {
                "x" -> x.set(value)
                "y" -> y.set(value)
                "z" -> z.set(value)
                else -> throw IllegalArgumentException("Unknown location: $loc")
            }
        }

        fun get(loc: String): Int =  when (loc) {
            "x" -> x.get()
            "y" -> y.get()
            "z" -> z.get()
            else -> throw IllegalArgumentException("Unknown location: $loc")
        }
    }

    fun mapResult(res: LincheckResult?): Int? {
        if (res == null) return null
        return when (res) {
            is ValueResult -> res.value as Int?
            else -> null
        }
    }


    fun sampleActor(rand: Random): Actor {
        val op = rand.nextInt(2)
        val locs = listOf("x", "y", "z")
        val loc = locs[rand.nextInt(locs.size)]
        if (op == 0) {
            val v = rand.nextInt(0,20)
            return actor (State::set, loc, v)
        } else {
            return actor (State::get, loc)
        }
    }

    fun sampleScenario(rand: Random): ExecutionScenario {
        val nThreads = 4
        val threads = (0 until nThreads).map {
            val nActors = rand.nextInt(1, 5)
            val actors = (0 until nActors).map { sampleActor(rand) }
            actors
        }
        return ExecutionScenario(listOf(), threads, listOf(), null)
    }

    @Test
    fun randomTest() {
        // Property-based testing at home
        val rand = Random(67)
        val n = 1000
        for (i in 0 until n) {
            val scenario = sampleScenario(rand)
            println("Compapring scenario #$i / #$n :\n$scenario")
            compareScenarios(scenario, true)
        }
    }
}

private fun<F> minimizeError(scenario: ExecutionScenario, err: F, checkScenario: (ExecutionScenario) -> F?): Pair<ExecutionScenario, F> {
    var currentScenario = scenario
    var currentErr = err
    while (true) {
        val res = currentScenario.tryMinimize(checkScenario)
            ?: break
        currentScenario = res.first
        currentErr = res.second
        print("Minimized scenario:\n$currentScenario\n")
    }
    return currentScenario to currentErr
}

private fun<F> ExecutionScenario.tryMinimize(checkScenario: (ExecutionScenario) -> F?): Pair<ExecutionScenario, F>? {
    // Try to remove only one operation.
    for (threadId in threads.indices.reversed()) {
        for (actorId in threads[threadId].indices.reversed()) {
            val sc = tryMinimize(threadId, actorId) ?: continue
            sc.run(checkScenario)?.let { return sc to it }
        }
    }
    // Try to remove two operations. For some data structure, such as a queue,
    // you need to remove pairwise operations, such as enqueue(e) and dequeue(),
    // to minimize the scenario and keep the error.
    for (threadId1 in threads.indices.reversed()) {
        for (actorId1 in threads[threadId1].indices.reversed()) {
            // Try to remove two operations at once.
            val minimizedScenario = tryMinimize(threadId1, actorId1) ?: continue
            for (threadId2 in minimizedScenario.threads.indices.reversed()) {
                for (actorId2 in minimizedScenario.threads[threadId2].indices.reversed()) {
                    val sc = minimizedScenario.tryMinimize(threadId2, actorId2) ?: continue
                    sc.run(checkScenario)?.let { return sc to it }
                }
            }
        }
    }
    // Try to move one of the first operations to the initial (pre-parallel) part.
    parallelExecution.forEachIndexed { threadId: Int, actors: List<Actor> ->
        if (actors.isNotEmpty()) {
            val newInitExecution = initExecution + actors.first()
            val newParallelExecution = parallelExecution.mapIndexed { t: Int, it: List<Actor> ->
                if (t == threadId) {
                    it.drop(1)
                } else {
                    ArrayList(it)
                }
            }.filter { it.isNotEmpty() }
            val newPostExecution = ArrayList(postExecution)
            val optimizedScenario = ExecutionScenario(
                initExecution = newInitExecution,
                parallelExecution = newParallelExecution,
                postExecution = newPostExecution,
                validationFunction = validationFunction
            )
            if (optimizedScenario.isValid) {
                optimizedScenario.run(checkScenario)?.let { return optimizedScenario to it }
            }
        }
    }
    // Try to move one of the last operations to the post (post-parallel) part.
    parallelExecution.forEachIndexed { threadId: Int, actors: List<Actor> ->
        if (actors.isNotEmpty()) {
            val newInitExecution = ArrayList(initExecution)
            val newParallelExecution = parallelExecution.mapIndexed { t: Int, it: List<Actor> ->
                if (t == threadId) {
                    it.dropLast(1)
                } else {
                    ArrayList(it)
                }
            }.filter { it.isNotEmpty() }
            val newPostExecution = listOf(actors.last()) + postExecution
            val optimizedScenario = ExecutionScenario(
                initExecution = newInitExecution,
                parallelExecution = newParallelExecution,
                postExecution = newPostExecution,
                validationFunction = validationFunction
            )
            if (optimizedScenario.isValid) {
                optimizedScenario.run(checkScenario)?.let { return optimizedScenario to it }
            }
        }
    }
    return null
}
