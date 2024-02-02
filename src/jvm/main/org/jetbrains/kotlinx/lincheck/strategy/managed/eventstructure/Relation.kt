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

import org.jetbrains.kotlinx.lincheck.utils.*

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

interface Enumerator<T> {

    operator fun get(x: T): Int

    operator fun get(i: Int): T

}

class RelationMatrix<T>(
    // TODO: take nodes from the enumerator (?)
    val nodes: Collection<T>,
    val enumerator: Enumerator<T>,
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
        get(enumerator[x], enumerator[y])

    private operator fun set(i: Int, j: Int, value: Boolean) {
        matrix[i][j] = value
    }

    operator fun set(x: T, y: T, value: Boolean) =
        set(enumerator[x], enumerator[y], value)

    fun change(x: T, y: T, new: Boolean): Boolean {
        val old = this[x, y]
        this[x, y] = new
        return old != new
    }

    fun add(relation: Relation<T>) {
        for (i in 0 until size) {
            val x = enumerator[i]
            for (j in 0 until size) {
                this[i, j] = this[i, j] || relation(x, enumerator[j])
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
                this[i, j] = this[i, j] && !relation(enumerator[i], enumerator[j])
            }
        }
    }

    fun filter(relation: Relation<T>) {
        for (i in 0 until size) {
            for (j in 0 until size) {
                this[i, j] = this[i, j] && relation(enumerator[i], enumerator[j])
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
            val x = enumerator[i]
            jLoop@for (j in 0 until size) {
                val y = enumerator[j]
                kLoop@for (k in 0 until size) {
                    val z = enumerator[k]
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
            val indexer = this@RelationMatrix.enumerator
            matrix[i].forEachIndexed { j, b ->
                if (!b) return@forEachIndexed
                result.add(indexer[j])
            }
            result
        }

        override fun adjacent(node: T): List<T> {
            val idx = this@RelationMatrix.enumerator[node]
            return adjacencyList[idx]
        }
    }

    fun covering() = object : Covering<T> {

        val enumerator = this@RelationMatrix.enumerator

        val covering: List<List<T>> = Array(this@RelationMatrix.size) { i ->
            val x = enumerator[i]
            (0 until size).mapNotNull { j ->
                val y = enumerator[j]
                if (this@RelationMatrix[x, y]) y else null
            }
        }.asList()

        override fun invoke(x: T): List<T> =
            covering[enumerator[x]]

    }

}

fun<T> Relation<T>.toGraph(nodes: Collection<T>, enumerator: Enumerator<T>) = object : Graph<T> {
    private val relation = this@toGraph

    override val nodes: Collection<T>
        get() = nodes

    private val adjacencyList = Array(nodes.size) { i ->
        val x = enumerator[i]
        nodes.filter { y -> relation(x, y) }
    }

    override fun adjacent(node: T): List<T> {
        val idx = enumerator[node]
        return adjacencyList[idx]
    }
}
