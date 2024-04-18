/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.fuzzing.coverage

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.ResultWithClock
import java.util.*
import kotlin.collections.ArrayDeque

class HappensBeforeSummary(
    scenario: ExecutionScenario,
    hbClocks: List<List<ResultWithClock>>
) {
    val pairs: MutableMap<Int, Set<Pair<Actor, Actor>>> = mutableMapOf()

    init {
        val timelines: MutableMap<Pair<Int, Int>, ThreadTimeline> = mutableMapOf() // { threadId, operationsCompleted } -> ThreadTimeline
        val clocksReached: MutableMap<Int, Int> = mutableMapOf() // threadId -> index in hbClocks array

        while (
            clocksReached.isEmpty() ||
            clocksReached.any { (threadId, clockIndex) -> clockIndex < hbClocks[threadId].size }
        ) {
            for (threadId in 0 until scenario.nThreads) {
                var currentThreadClockIndex = clocksReached.getOrPut(threadId) { 0 }
                val currentTimeline = ThreadTimeline(threadId)

                while (
                    currentThreadClockIndex < hbClocks[threadId].size &&
                    hbClocks[threadId][currentThreadClockIndex].clockOnStart.clock
                        .mapIndexed { index, i -> Pair(index, i) }
                        .all { (thread, clock) -> clock <= clocksReached.getOrPut(thread) { 0 } }
                ) {
                    // insert happens-before edges from all observed threads
                    hbClocks[threadId][currentThreadClockIndex].clockOnStart.clock.forEachIndexed { index, clock ->
                        val timeline = timelines
                            .getOrPut(Pair(index, clock)) { ThreadTimeline(index) }
                        currentTimeline.merge(timeline)
                    }

                    val actor = getActorFromClock(threadId, currentThreadClockIndex, scenario)
                    currentTimeline.update(actor)

                    timelines[Pair(threadId, currentThreadClockIndex + 1)] = currentTimeline
                    currentThreadClockIndex++
                }

                clocksReached[threadId] = currentThreadClockIndex
            }
        }

        val sortedRelations: MutableList<Set<Pair<Actor, Actor>>> = mutableListOf()
        for (threadId in 0 until scenario.nThreads) {
            val completedOperations =
                scenario.parallelExecution[threadId].size +
                if (threadId == 0) scenario.initExecution.size + scenario.postExecution.size else 0

            sortedRelations.add(timelines[Pair(threadId, completedOperations)]!!.pairs)
            //pairs[threadId] = timelines[Pair(threadId, completedOperations)]!!.pairs
        }

        sortedRelations.sortBy { st ->
            st.joinToString(".") { it.first.method.name + "." + it.second.method.name }
        }

        sortedRelations.forEachIndexed { index, st ->
            pairs[index] = st
        }
    }

    private fun getActorFromClock(threadId: Int, clockIndex: Int, scenario: ExecutionScenario): Actor {
        if (threadId != 0) return scenario.parallelExecution[threadId][clockIndex]

        return if (clockIndex < scenario.initExecution.size) {
            // init part
            scenario.initExecution[clockIndex]
        }
        else if (clockIndex - scenario.initExecution.size < scenario.parallelExecution[threadId].size) {
            // parallel part
            scenario.parallelExecution[threadId][clockIndex - scenario.initExecution.size]
        }
        else {
            // post part
            scenario.postExecution[clockIndex - scenario.initExecution.size - scenario.parallelExecution[threadId].size]
        }
    }

    private fun check(
        from: MutableMap<Int, Set<Pair<Actor, Actor>>>,
        to: MutableMap<Int, Set<Pair<Actor, Actor>>>
    ): Boolean {
        from.forEach { (key, actors) ->
            if (!to.contains(key)) return false
            actors.forEach {
                if (!to[key]!!.contains(it)) return false
            }
        }

        return true
    }

    override fun equals(other: Any?): Boolean {
        if (other !is HappensBeforeSummary) return false

        return (
            check(pairs, other.pairs) &&
            check(other.pairs, pairs)
        )
    }

    override fun hashCode(): Int {
        return pairs.hashCode()
    }
}

private class ThreadTimeline(val threadId: Int) {
    val events: MutableSet<Actor> = mutableSetOf()
    val pairs: MutableSet<Pair<Actor, Actor>> = TreeSet { a, b ->
        // sort pairs lexicographically
        val lexA = a.first.method.name + "." + a.second.method.name
        val lexB = b.first.method.name + "." + b.second.method.name
        lexA.compareTo(lexB)
    }

    /** Inserts happens-before edge with all observed events in the timeline */
    fun update(actor: Actor) {
        events.forEach { pairs.add(Pair(it, actor)) }
        events.add(actor)
    }

    /** Saves as observed all events observed by `other` timeline */
    fun merge(other: ThreadTimeline) {
        events.addAll(other.events)
        if (threadId == other.threadId) {
            // if we are merging with the timeline of the same thread, then add its pairs as well
            pairs.addAll(other.pairs)
        }
    }
}