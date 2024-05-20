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

package org.jetbrains.kotlinx.lincheck.util

import kotlin.reflect.KProperty

interface Computable {
    fun initialize() {}
    fun compute()
    fun invalidate() {}
    fun reset()
}

interface Incremental<T> {
    fun add(element: T)
}

fun<T : Computable> computable(builder: () -> T) =
    ComputableNode(builder)

class ComputableNode<T : Computable>(val builder: () -> T) : Computable {

    private var _value: T? = null

    val value: T get() =
        _value ?: builder().also { _value = it }

    private data class Dependency(
        val computable: ComputableNode<*>,
        // computations of soft dependencies are not enforced
        val soft: Boolean,
    )

    private val dependencies = mutableListOf<Dependency>()

    private data class Subscriber(
        val computable: ComputableNode<*>,
        // tells whether this subscriber should be
        // recursively invalidated on invalidation
        val invalidating: Boolean,
        // tells whether this subscriber should be
        // recursively reset on resetting
        val resetting: Boolean,
    )

    private val subscribers = mutableListOf<Subscriber>()

    private enum class State {
        UNSET, INITIALIZED, COMPUTED
    }

    private var state = State.UNSET

    val unset: Boolean
        get() = (state.ordinal == State.UNSET.ordinal)

    val initialized: Boolean
        get() = (state.ordinal >= State.INITIALIZED.ordinal)

    val computed: Boolean
        get() = (state.ordinal >= State.COMPUTED.ordinal)

    override fun initialize() {
        if (!initialized) {
            initializeDependencies()
            initializeValue()
        }
    }

    private fun initializeValue() {
        value.initialize()
        state = State.INITIALIZED
    }

    override fun compute() {
        if (!computed) {
            computeDependencies()
            computeValue()
        }
    }

    private fun computeValue() {
        value.compute()
        state = State.COMPUTED
    }

    override fun invalidate() {
        if (state == State.COMPUTED) {
            invalidateValue()
            invalidateSubscribers()
        }
    }

    private fun invalidateValue() {
        value.invalidate()
        state = State.INITIALIZED
    }

    override fun reset() {
        if (!unset) {
            resetValue()
            resetSubscribers()
        }
    }

    private fun resetValue() {
        value.reset()
        state = State.UNSET
    }

    fun setComputed() {
        state = State.COMPUTED
        invalidateSubscribers()
    }

    fun setComputed(value: T) {
        this._value = value
        setComputed()
    }

    fun addDependency(dependency: ComputableNode<*>,
        soft: Boolean = false,
        invalidating: Boolean = false,
        resetting: Boolean = false
    ) {
        dependencies.add(Dependency(dependency, soft))
        dependency.subscribers.add(Subscriber(this, invalidating, resetting))
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        initialize()
        compute()
        return value
    }

    private fun initializeDependencies() {
        traverseDependencies { dependency ->
            if (!dependency.soft && !dependency.computable.initialized) {
                dependency.computable.initializeValue()
                true
            } else false
        }
    }

    private fun computeDependencies() {
        traverseDependencies { dependency ->
            if (!dependency.soft && !dependency.computable.computed) {
                dependency.computable.computeValue()
                true
            } else false
        }
    }

    private fun invalidateSubscribers() {
        traverseSubscribers { subscriber ->
            if (subscriber.invalidating && subscriber.computable.computed) {
                subscriber.computable.invalidateValue()
                true
            } else false
        }
    }

    private fun resetSubscribers() {
        traverseSubscribers { subscriber ->
            if (subscriber.resetting && !subscriber.computable.unset) {
                subscriber.computable.resetValue()
                true
            } else false
        }
    }

    private fun traverseDependencies(action: (Dependency) -> Boolean) {
        val stack = ArrayDeque<ComputableNode<*>>(listOf(this))
        val visited = mutableSetOf<ComputableNode<*>>(this)
        while (stack.isNotEmpty()) {
            val computable = stack.removeLast()
            computable.dependencies.forEach { dependency ->
                val unvisited = visited.add(dependency.computable)
                if (unvisited) {
                    val expandable = action(dependency)
                    if (expandable) stack.add(dependency.computable)
                }
            }
        }
    }

    private fun traverseSubscribers(action: (Subscriber) -> Boolean) {
        val stack = ArrayDeque<ComputableNode<*>>(listOf(this))
        val visited = mutableSetOf<ComputableNode<*>>(this)
        while (stack.isNotEmpty()) {
            val computable = stack.removeLast()
            computable.subscribers.forEach { subscriber ->
                val unvisited = visited.add(subscriber.computable)
                if (unvisited) {
                    val expandable = action(subscriber)
                    if (expandable) stack.add(subscriber.computable)
                }
            }
        }
    }

}

fun<T : Computable> ComputableNode<T>.dependsOn(dependency: ComputableNode<*>,
    soft: Boolean = false,
    invalidating: Boolean = false,
    resetting: Boolean = false
): ComputableNode<T> {
    addDependency(dependency, soft, invalidating, resetting)
    return this
}

