/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.distributed


internal class LogList<E>(
    val strategy: DistributedStrategy<*, E>, val iNode: Int,
    val list: MutableList<E> = mutableListOf()
) : MutableList<E> {
    override val size: Int
        get() = list.size

    override fun contains(element: E): Boolean = list.contains(element)

    override fun containsAll(elements: Collection<E>): Boolean = list.containsAll(elements)

    override fun get(index: Int): E = list[index]

    override fun indexOf(element: E): Int = list.indexOf(element)

    override fun isEmpty(): Boolean = list.isEmpty()

    override fun iterator(): MutableIterator<E> = list.iterator()

    override fun lastIndexOf(element: E): Int = list.lastIndexOf(element)

    override fun add(element: E): Boolean {
        strategy.beforeLogModify(iNode)
        return list.add(element)
    }

    override fun add(index: Int, element: E) {
        strategy.beforeLogModify(iNode)
        list.add(index, element)
    }

    override fun addAll(index: Int, elements: Collection<E>): Boolean {
        strategy.beforeLogModify(iNode)
        return list.addAll(index, elements)
    }

    override fun addAll(elements: Collection<E>): Boolean {
        strategy.beforeLogModify(iNode)
        return list.addAll(elements)
    }

    override fun clear() {
        strategy.beforeLogModify(iNode)
        list.clear()
    }

    override fun listIterator(): MutableListIterator<E> = list.listIterator()

    override fun listIterator(index: Int): MutableListIterator<E> = list.listIterator(index)

    override fun remove(element: E): Boolean {
        strategy.beforeLogModify(iNode)
        return list.remove(element)
    }

    override fun removeAll(elements: Collection<E>): Boolean {
        strategy.beforeLogModify(iNode)
        return list.removeAll(elements)
    }

    override fun removeAt(index: Int): E {
        strategy.beforeLogModify(iNode)
        return list.removeAt(index)
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        strategy.beforeLogModify(iNode)
        return list.retainAll(elements)
    }

    override fun set(index: Int, element: E): E {
        strategy.beforeLogModify(iNode)
        return list.set(index, element)
    }

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> {
        return list.subList(fromIndex, toIndex)
    }

    override fun toString(): String = list.toString()
}