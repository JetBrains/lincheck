/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util.collections

class ObservableList<T>(
    private val delegate: MutableList<T> = mutableListOf(),
    private val onSet: (index: Int, old: T, new: T) -> Unit = { _, _, _ -> },
    private val onAdd: (element: T) -> Unit = {},
    private val onRemove: (element: T) -> Unit = {},
    private val onClear: (elements: List<T>) -> Unit = {}
) : MutableList<T> by delegate {

    override fun set(index: Int, element: T): T {
        return delegate.set(index, element).also { old ->
            onSet(index, old, element)
        }
    }

    override fun add(element: T): Boolean {
        return delegate.add(element).also {
            onAdd(element)
        }
    }

    override fun add(index: Int, element: T) {
        delegate.add(index, element).also {
            onAdd(element)
        }
    }

    override fun addAll(elements: Collection<T>): Boolean {
        return delegate.addAll(elements).also {
            elements.forEach { onAdd(it) }
        }
    }

    override fun addAll(index: Int, elements: Collection<T>): Boolean {
        return delegate.addAll(index, elements).also {
            elements.forEach { onAdd(it) }
        }
    }

    override fun remove(element: T): Boolean {
        return delegate.remove(element).also {
            if (it) onRemove(element)
        }
    }

    override fun removeAt(index: Int): T {
        return delegate.removeAt(index).also {
            onRemove(it)
        }
    }

    override fun removeAll(elements: Collection<T>): Boolean {
        val removed = elements.filter { it in delegate }
        return delegate.removeAll(elements).also {
            removed.forEach { onRemove(it) }
        }
    }

    override fun retainAll(elements: Collection<T>): Boolean {
        val removed = delegate.filter { it !in elements }
        return delegate.retainAll(elements).also {
            removed.forEach { onRemove(it) }
        }
    }

    override fun clear() {
        onClear(delegate)
        delegate.clear()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is List<*>) return false
        if (other is ObservableList<*>) return (delegate == other.delegate)
        return delegate == other
    }

    override fun hashCode(): Int = delegate.hashCode()

    override fun toString(): String = delegate.toString()
}