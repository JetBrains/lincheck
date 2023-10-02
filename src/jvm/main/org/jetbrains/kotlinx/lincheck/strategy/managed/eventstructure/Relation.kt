/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

fun interface Relation<in T> {
    operator fun invoke(x: T, y: T): Boolean

    companion object {
        fun<T> empty() = Relation<T> { _, _ -> false }
    }
}

infix fun<T> Relation<T>.union(relation: Relation<T>) = Relation<T> { x, y ->
    this(x, y) || relation(x, y)
}

infix fun<T> Relation<T>.intersection(relation: Relation<T>) = Relation<T> { x, y ->
    this(x, y) && relation(x, y)
}

// covering for each element returns a list of elements on which it depends;
// in terms of a graph: for each node it returns source nodes of its incoming edges
fun interface Covering<T> {
    operator fun invoke(x: T): List<T>
}

// adjacency list is a function that
// for each node of a graph returns destination nodes of its outgoing edges
interface Graph<T> {
    val nodes: Collection<T>
    fun adjacent(node: T): List<T>
}

fun<T> topologicalSortings(graph: Graph<T>): Sequence<List<T>> {
    val state = mutableMapOf<T, TopoSortNodeState>()
    val result = MutableList<T?>(graph.nodes.size) { null }
    for (node in graph.nodes) {
        state[node] = TopoSortNodeState.initial()
    }
    for (node in graph.nodes) {
        for (adjacentNode in graph.adjacent(node)) {
            state[adjacentNode]!!.indegree++
        }
    }
    return sequence {
        yieldTopologicalSortings(graph, 0, result, state)
    }
}

private data class TopoSortNodeState(
    var visited: Boolean,
    var indegree: Int,
) {
    companion object {
        fun initial() = TopoSortNodeState(false, 0)
    }
}

private suspend fun<T> SequenceScope<List<T>>.yieldTopologicalSortings(
    graph: Graph<T>,
    depth: Int,
    result: MutableList<T?>,
    state: MutableMap<T, TopoSortNodeState>,
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


interface Indexer<T> {

    fun index(x: T): Int

    operator fun get(i: Int): T

}

class RelationMatrix<T>(
    val nodes: Collection<T>,
    val indexer: Indexer<T>,
    relation: Relation<T>? = null
) : Relation<T> {

    private val size = nodes.size

    private val matrix = Array(size) { BooleanArray(size) }

    init {
        relation?.let { add(it) }
    }

    override operator fun invoke(x: T, y: T): Boolean =
        get(x, y)

    private inline operator fun get(i: Int, j: Int): Boolean {
        return matrix[i][j]
    }

    operator fun get(x: T, y: T): Boolean =
        get(indexer.index(x), indexer.index(y))

    private operator fun set(i: Int, j: Int, value: Boolean) {
        matrix[i][j] = value
    }

    operator fun set(x: T, y: T, value: Boolean) =
        set(indexer.index(x), indexer.index(y), value)

    fun change(x: T, y: T, new: Boolean): Boolean {
        val old = this[x, y]
        this[x, y] = new
        return old != new
    }

    fun add(relation: Relation<T>) {
        for (i in 0 until size) {
            for (j in 0 until size) {
                this[i, j] = this[i, j] || relation(indexer[i], indexer[j])
            }
        }
    }

    fun addTotalOrdering(ordering: List<T>, strict: Boolean = true) {
        for (i in ordering.indices) {
            for (j in i until ordering.size) {
                if (strict && i == j)
                    continue
                this[ordering[i], ordering[j]] = true
            }
        }
    }

    fun remove(relation: Relation<T>) {
        for (i in 0 until size) {
            for (j in 0 until size) {
                this[i, j] = this[i, j] && !relation(indexer[i], indexer[j])
            }
        }
    }

    fun filter(relation: Relation<T>) {
        for (i in 0 until size) {
            for (j in 0 until size) {
                this[i, j] = this[i, j] && relation(indexer[i], indexer[j])
            }
        }
    }

    private fun swap(i: Int, j: Int) {
        val bool = this[i, j]
        this[i, j] = this[j, i]
        this[j, i] = bool
    }

    fun transpose() {
        for (i in 1 until size) {
            for (j in 0 until i) {
                swap(i, j)
            }
        }
    }

    fun closure(rule: (T, T, T) -> Boolean): Boolean {
        var changed = false
        iLoop@for (i in 0 until size) {
            val x = indexer[i]
            jLoop@for (j in 0 until size) {
                val y = indexer[j]
                kLoop@for (k in 0 until size) {
                    val z = indexer[k]
                    if (rule(x, y, z)) {
                        this[i, j] = true
                        changed = true
                        continue@jLoop
                    }
                }
            }
        }
        return changed
    }

    fun transitiveClosure(): Boolean {
        var changed = false
        kLoop@for (k in 0 until size) {
            iLoop@for (i in 0 until size) {
                if (!this[i, k])
                    continue@iLoop
                jLoop@for (j in 0 until size) {
                    val connected = this[i, j]
                    this[i, j] = connected || this[k, j]
                    changed = changed || !connected && this[k, j]
                }
            }
        }
        return changed
    }

    fun transitiveReduction(): Boolean {
        var changed = false
        jLoop@for (j in 0 until size) {
            iLoop@for (i in 0 until size) {
                if (!this[i, j])
                    continue@iLoop
                kLoop@for (k in 0 until size) {
                    if (this[i, k] && this[j, k]) {
                        this[i, k] = false
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    fun isIrreflexive(): Boolean {
        for (i in 0 until size) {
            if (this[i, i])
                return false
        }
        return true
    }

    fun asGraph() = object : Graph<T> {
        override val nodes: Collection<T>
            get() = this@RelationMatrix.nodes

        private val adjacencyList = Array<List<T>>(nodes.size) { i ->
            val result = mutableListOf<T>()
            val indexer = this@RelationMatrix.indexer
            matrix[i].forEachIndexed { j, b ->
                if (!b) return@forEachIndexed
                result.add(indexer[j])
            }
            result
        }

        override fun adjacent(node: T): List<T> {
            val idx = this@RelationMatrix.indexer.index(node)
            return adjacencyList[idx]
        }
    }

    fun covering() = object : Covering<T> {

        val indexer = this@RelationMatrix.indexer

        val covering: List<List<T>> = Array(this@RelationMatrix.size) { i ->
            val x = indexer[i]
            (0 until size).mapNotNull { j ->
                val y = indexer[j]
                if (this@RelationMatrix[x, y]) y else null
            }
        }.asList()

        override fun invoke(x: T): List<T> =
            covering[indexer.index(x)]

    }

}