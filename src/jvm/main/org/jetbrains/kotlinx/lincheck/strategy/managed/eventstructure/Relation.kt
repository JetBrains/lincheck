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

    infix fun or(relation: Relation<T>) = Relation<T> { x, y ->
        this(x, y) || relation(x, y)
    }

    infix fun and(relation: Relation<T>) = Relation<T> { x, y ->
        this(x, y) && relation(x, y)
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
    // TODO: do not save `nodes` inside object, it is error-prone
    //  because underlying collection can change between methods' calls
    val nodes: Collection<T>,
    val indexer: Indexer<T>,
    relation: Relation<T>? = null
) : Relation<T> {

    val size = nodes.size

    val matrix: BooleanArray = BooleanArray(size * size)

    init {
        relation?.let { add(it) }
    }

    private fun startIndex(x: T): Int =
        indexer.index(x) * size

    private fun endIndex(x: T): Int =
        indexer.index(x) * size + size

    private fun index(i: Int, j: Int): Int =
        i * size + j

    override operator fun invoke(x: T, y: T): Boolean =
        get(x, y)

    private operator fun get(i: Int, j: Int): Boolean {
        return matrix[index(i, j)]
    }

    operator fun get(x: T, y: T): Boolean =
        get(indexer.index(x), indexer.index(y))

    private operator fun set(i: Int, j: Int, value: Boolean) {
        matrix[index(i, j)] = value
    }

    operator fun set(x: T, y: T, value: Boolean) =
        set(indexer.index(x), indexer.index(y), value)

    fun add(relation: Relation<T>) {
        for (x in nodes) {
            for (y in nodes) {
                this[x, y] = this[x, y] || relation(x, y)
            }
        }
    }

    fun remove(relation: Relation<T>) {
        for (x in nodes) {
            for (y in nodes) {
                this[x, y] = this[x, y] && !relation(x, y)
            }
        }
    }

    fun filter(relation: Relation<T>) {
        for (x in nodes) {
            for (y in nodes) {
                this[x, y] = this[x, y] && relation(x, y)
            }
        }
    }

    private fun swap(i: Int, j: Int) {
        val bool = this[i, j]
        this[i, j] = this[j, i]
        this[j, i] = bool
    }

    fun transpose() {
        for (i in 1 until nodes.size) {
            for (j in 0 until i) {
                swap(i, j)
            }
        }
    }

    fun closure(rule: (T, T, T) -> Boolean): Boolean {
        var changed = false
        xLoop@for (x in nodes) {
            yLoop@for (y in nodes) {
                if (this[x, y])
                    continue@yLoop
                zLoop@for (z in nodes) {
                    if (rule(x, y, z)) {
                        this[x, y] = true
                        changed = true
                        continue@yLoop
                    }
                }
            }
        }
        return changed
    }

    fun transitiveClosure(): Boolean {
        var changed = false
        xLoop@for (x in nodes) {
            yLoop@for (y in nodes) {
                if (this[x, y])
                    continue@yLoop
                zLoop@for (z in nodes) {
                    if (this[x, z] && this[z, y]) {
                        this[x, y] = true
                        changed = true
                        continue@yLoop
                    }
                }
            }
        }
        return changed
    }

    fun transitiveReduction(): Boolean {
        var changed = false
        xLoop@for (y in nodes) {
            yLoop@for (x in nodes) {
                if (!this[x, y])
                    continue@yLoop
                zLoop@for (z in nodes) {
                    if (this[x, z] && this[y, z]) {
                        this[x, z] = false
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    fun isIrreflexive(): Boolean {
        for (x in nodes) {
            if (this[x, x])
                return false
        }
        return true
    }

    fun covering() = object : Covering<T> {

        val indexer = this@RelationMatrix.indexer

        val covering: List<List<T>> = Array(nodes.size) { i ->
            val x = indexer[i]
            (startIndex(x) until endIndex(x)).mapNotNull { j ->
                val y = indexer[j - startIndex(x)]
                if (this@RelationMatrix[x, y]) y else null
            }
        }.asList()

        override fun invoke(x: T): List<T> =
            covering[indexer.index(x)]

    }

}