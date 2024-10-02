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

import org.jetbrains.kotlinx.lincheck.util.*

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

fun<T> Relation<T>.orEqual(x: T, y: T): Boolean {
    return (x == y) || this(x, y)
}

fun<T> Relation<T>.unordered(x: T, y: T): Boolean {
    return (x != y) && !this(x, y) && !this(y, x)
}

fun<T> Relation<T>.maxOrNull(x: T, y: T): T? = when {
    x == y -> x
    this(x, y) -> y
    this(y, x) -> x
    else -> null
}

fun<T> Relation<T>.max(x: T, y: T): T =
    maxOrNull(x, y) ?: throw IncomparableArgumentsException("$x and $y are incomparable")

class IncomparableArgumentsException(message: String): Exception(message)

// covering for each element returns a list of elements on which it depends;
// in terms of a graph: for each node it returns source nodes of its incoming edges
fun interface Covering<T> {
    operator fun invoke(x: T): List<T>
}

interface Enumerator<T> {

    operator fun get(x: T): Int

    // TODO: change to `List` and use it to optimize iteration in `RelationMatrix`?
    operator fun get(i: Int): T

}

fun<T : Comparable<T>> SortedList<T>.toEnumerator(): Enumerator<T> = object : Enumerator<T> {
    private val list = this@toEnumerator
    override fun get(i: Int): T = list[i]
    override fun get(x: T): Int = list.indexOf(x)
}

class RelationMatrix<T>(
    // TODO: take nodes from the enumerator (?)
    val nodes: Collection<T>,
    val enumerator: Enumerator<T>,
) : Relation<T> {

    private val size = nodes.size

    private val matrix = Array(size) { BooleanArray(size) }

    private var version = 0

    constructor(nodes: Collection<T>, enumerator: Enumerator<T>, relation: Relation<T>) : this (nodes, enumerator) {
        add(relation)
    }

    override operator fun invoke(x: T, y: T): Boolean =
        get(x, y)

    private inline operator fun get(i: Int, j: Int): Boolean {
        return matrix[i][j]
    }

    operator fun get(x: T, y: T): Boolean =
        get(enumerator[x], enumerator[y])

    private operator fun set(i: Int, j: Int, value: Boolean) {
        version += (matrix[i][j] != value).toInt()
        matrix[i][j] = value
    }

    operator fun set(x: T, y: T, value: Boolean) =
        set(enumerator[x], enumerator[y], value)

    fun add(relation: Relation<T>) {
        for (i in 0 until size) {
            val x = enumerator[i]
            for (j in 0 until size) {
                this[i, j] = this[i, j] || relation(x, enumerator[j])
            }
        }
    }

    fun order(ordering: List<T>, strict: Boolean = true) {
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
        val value  = this[i, j]
        this[i, j] = this[j, i]
        this[j, i] = value
    }

    fun transpose() {
        for (i in 1 until size) {
            for (j in 0 until i) {
                swap(i, j)
            }
        }
    }

    fun transitiveClosure() {
        // TODO: optimize -- skip the computation for already transitive relation;
        //  track this by saving relation version number at the last call to `transitiveClosure`
        kLoop@for (k in 0 until size) {
            iLoop@for (i in 0 until size) {
                if (!this[i, k])
                    continue@iLoop
                jLoop@for (j in 0 until size) {
                    this[i, j] = this[i, j] || this[k, j]
                }
            }
        }
    }

    fun transitiveReduction() {
        jLoop@for (j in 0 until size) {
            iLoop@for (i in 0 until size) {
                if (!this[i, j])
                    continue@iLoop
                kLoop@for (k in 0 until size) {
                    if (this[i, k] && this[j, k]) {
                        this[i, k] = false
                    }
                }
            }
        }
    }

    fun equivalenceClosure(equivClassMapping : (T) -> List<T>?) {
        for (i in 0 until size) {
            val x = enumerator[i]
            val xClass = equivClassMapping(x)
            for (j in 0 until size) {
                val y = enumerator[j]
                val yClass = equivClassMapping(y)
                if (this[x, y] && xClass !== yClass) {
                    xClass?.forEach { this[it, y] = true }
                    yClass?.forEach { this[x, it] = true }
                }
            }
        }
    }

    fun fixpoint(block: RelationMatrix<T>.() -> Unit) {
        do {
            val changed = trackChanges { block() }
        } while (changed)
    }

    fun trackChanges(block: RelationMatrix<T>.() -> Unit): Boolean {
        val version = this.version
        block(this)
        return (version != this.version)
    }

    fun isIrreflexive(): Boolean {
        for (i in 0 until size) {
            if (this[i, i])
                return false
        }
        return true
    }

    fun toGraph(): Graph<T> = toGraph(nodes, enumerator)

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