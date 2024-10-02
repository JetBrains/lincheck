/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.util.*
import java.util.*

// adjacency list is a function that
// for each node of a graph returns destination nodes of its outgoing edges
interface Graph<T> {
    val nodes: Collection<T>
    fun adjacent(node: T): List<T>
}

fun<T> topologicalSorting(graph: Graph<T>): List<T>? {
    val result = mutableListOf<T>()
    val state = graph.initializeTopoSortState()
    val queue: Queue<T> = LinkedList()
    for (node in graph.nodes) {
        if (state[node]!!.indegree == 0)
            queue.add(node)
    }
    while (queue.isNotEmpty()) {
        val node = queue.poll()
        result.add(node)
        graph.adjacent(node).forEach {
            if (--state[it]!!.indegree == 0)
                queue.add(it)
        }
    }
    if (result.size != graph.nodes.size) {
        return null
    }
    return result
}

fun<T> topologicalSortings(graph: Graph<T>): Sequence<List<T>> {
    val result = MutableList<T?>(graph.nodes.size) { null }
    val state = graph.initializeTopoSortState()
    return sequence {
        yieldTopologicalSortings(graph, 0, result, state)
    }
}

private suspend fun<T> SequenceScope<List<T>>.yieldTopologicalSortings(
    graph: Graph<T>,
    depth: Int,
    result: MutableList<T?>,
    state: TopoSortState<T>,
) {
    // this flag is used to detect terminal recursive calls
    var isTerminal = true
    // iterate through all nodes
    for (node in graph.nodes) {
        val nodeState = state[node]!!
        // skip visited and not-yet ready nodes
        if (nodeState.visited || nodeState.indegree != 0)
            continue
        // push the current node on top of the result list
        result[depth] = node
        // mark node as visited
        nodeState.visited = true
        // decrease indegree of all adjacent nodes
        for (adjacentNode in graph.adjacent(node))
            state[adjacentNode]!!.indegree--
        // explore topological sortings recursively
        yieldTopologicalSortings(graph, depth + 1, result, state)
        // since we made recursive call, reset the isTerminal flag
        isTerminal = false
        // rollback the state
        for (adjacentNode in graph.adjacent(node))
            state[adjacentNode]!!.indegree++
        nodeState.visited = false
        result[depth] = null
    }
    // if we are at terminal call, yield the resulting sorted list
    if (isTerminal) {
        val sorting = result.toMutableList().requireNoNulls()
        yield(sorting)
    }
}

private typealias TopoSortState<T> = MutableMap<T, TopoSortNodeState>

private data class TopoSortNodeState(
    var visited: Boolean,
    var indegree: Int,
) {
    companion object {
        fun initial() = TopoSortNodeState(false, 0)
    }
}

private fun<T> Graph<T>.initializeTopoSortState(): TopoSortState<T> {
    val state = mutableMapOf<T, TopoSortNodeState>()
    for (node in nodes) {
        state.putIfAbsent(node, TopoSortNodeState.initial())
        for (adjacentNode in adjacent(node)) {
            state.updateInplace(adjacentNode, default = TopoSortNodeState.initial()) {
                indegree++
            }
        }
    }
    return state
}