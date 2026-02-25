/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

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

    private operator fun get(i: Int, j: Int): Boolean {
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

}
