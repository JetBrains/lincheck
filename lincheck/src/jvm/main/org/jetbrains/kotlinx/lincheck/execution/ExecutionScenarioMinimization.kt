/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure

/**
 * Greedily minimizes a failing scenario while preserving a failure.
 *
 * Tries removing one or two actors and moving parallel actors into the sequential parts,
 * repeating successful reductions until no candidate fails.
 *
 * @param checkScenario checks a candidate and returns its failure, or null if it succeeds.
 * @return the failure from the smallest scenario found.
 */
internal fun LincheckFailure.minimize(checkScenario: (ExecutionScenario) -> LincheckFailure?): LincheckFailure {
    var minimizedFailure = this
    while (true) {
        minimizedFailure = minimizedFailure.scenario.tryMinimize(checkScenario)
            ?: break
    }
    return minimizedFailure
}

private fun ExecutionScenario.tryMinimize(checkScenario: (ExecutionScenario) -> LincheckFailure?): LincheckFailure? {
    // Try to remove only one operation.
    for (threadId in threads.indices.reversed()) {
        for (actorId in threads[threadId].indices.reversed()) {
            tryMinimize(threadId, actorId)?.run(checkScenario)?.let { return it }
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
                    minimizedScenario.tryMinimize(threadId2, actorId2)?.run(checkScenario)?.let { return it }
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
                optimizedScenario.run(checkScenario)?.let { return it }
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
                optimizedScenario.run(checkScenario)?.let { return it }
            }
        }
    }
    return null
}

/**
 * Tries to minimize execution scenario by removing the actor with [actorId] in the thread with [threadId].
 *
 * @param threadId id of the thread to remove an actor.
 *   Note that init and post parts are placed in the 1st thread with id 0.
 * @param actorId id of the actor to remove.
 * @return minimized scenario, or `null` if the scenario becomes invalid after minimization.
 */
fun ExecutionScenario.tryMinimize(threadId: Int, actorId: Int): ExecutionScenario? {
    require(threadId < threads.size && actorId < threads[threadId].size)
    val initPartSize = when {
        threadId == 0 && actorId < initExecution.size ->
            initExecution.size - 1
        else -> initExecution.size
    }
    val postPartSize = when {
        threadId == 0 && actorId >= initExecution.size + parallelExecution[0].size ->
            postExecution.size - 1
        else -> postExecution.size
    }
    return threads
        .mapIndexed { i, actors ->
            actors.toMutableList().apply {
                if (i == threadId)
                    removeAt(actorId)
            }
        }
        .filter { it.isNotEmpty() }
        .splitIntoParts(initPartSize, postPartSize, validationFunction)
        .takeIf { it.isValid }
}

/**
 * Splits the list of threads into init, post, and parallel parts, constructing the [ExecutionScenario] as a result.
 * The init and post parts are assumed to be placed in the 1st thread.
 * If there is only one thread, then the scenario is not split into init/post parts, only parallel part is left.
 *
 * @param initPartSize the size of the initial part of the execution.
 * @param postPartSize the size of the post part of the execution.
 * @return execution scenario with separate init, post, and parallel parts.
 */
private fun List<List<Actor>>.splitIntoParts(initPartSize: Int, postPartSize: Int, validationFunction: Actor?): ExecutionScenario {
    // empty scenario case
    if (isEmpty()) {
        return ExecutionScenario(listOf(), listOf(), listOf(), validationFunction)
    }
    // get potential init and post parts
    val firstThreadSize = get(0).size
    val initExecution = get(0).subList(0, initPartSize)
    val postExecution = get(0).subList(firstThreadSize - postPartSize, firstThreadSize)
    val parallelExecution = indices.map { i ->
        if (i == 0)
            get(0).subList(initPartSize, firstThreadSize - postPartSize)
        else
            get(i)
    }.toMutableList()
    // if after splitting parallel part of the 1st thread becomes empty, then remove it from the scenario
    if (parallelExecution[0].isEmpty()) {
        parallelExecution.removeAt(0)
    }
    // single-thread scenario is not split into init/post parts
    if (parallelExecution.size == 1) {
        val threadExecution = initExecution + parallelExecution[0] + postExecution
        return ExecutionScenario(listOf(), listOf(threadExecution), listOf(), validationFunction)
    }
    return ExecutionScenario(initExecution, parallelExecution, postExecution, validationFunction)
}
