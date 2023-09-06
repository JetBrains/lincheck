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

fun interface Relation<T> {
    operator fun invoke(x: T, y: T): Boolean

    infix fun union(relation: Relation<T>) = Relation<T> { x, y ->
        this(x, y) || relation(x, y)
    }

    infix fun intersection(relation: Relation<T>) = Relation<T> { x, y ->
        this(x, y) && relation(x, y)
    }

    companion object {
        fun<T> empty() = Relation<T> { _, _ -> false }
    }
}

fun interface Covering<T> {
    operator fun invoke(x: T): List<T>
}

interface Indexer<T> {

    fun index(x: T): Int

    operator fun get(i: Int): T

}

class RelationMatrix<T>(
    nodes: Collection<T>,
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
        iLoop@for (i in 0 until size) {
            jLoop@for (j in 0 until size) {
                if (this[i, j])
                    continue@jLoop
                kLoop@for (k in 0 until size) {
                    if (this[i, k] && this[k, j]) {
                        this[i, j] = true
                        changed = true
                        continue@jLoop
                    }
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