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

package org.jetbrains.kotlinx.lincheck.utils

import kotlin.reflect.KProperty

interface Computable {
    fun initialize() {}
    fun compute()
    fun invalidate() {}
    fun reset()
}

fun<T : Computable> computable(dependsOn: List<Computable> = listOf(), builder: () -> T) =
    ComputableDelegate(builder(), dependsOn)

class ComputableDelegate<T : Computable>(
    val value: T,
    // by passing list of dependencies in the constructor,
    // we effectively prevent the creation of cyclic dependencies
    private val dependencies: List<Computable> = listOf(),
) : Computable {

    private val followers = mutableListOf<Computable>()

    init {
        dependencies.forEach { dependency ->
            if (dependency is ComputableDelegate<*>)
                dependency.followers.add(this)
        }
    }

    private enum class State {
        UNSET, INITIALIZED, COMPUTED
    }

    private var state = State.UNSET

    private val unset: Boolean
        get() = (state.ordinal == State.UNSET.ordinal)

    private val initialized: Boolean
        get() = (state.ordinal >= State.INITIALIZED.ordinal)

    private val computed: Boolean
        get() = (state.ordinal >= State.COMPUTED.ordinal)

    override fun initialize() {
        if (!initialized) {
            dependencies.forEach { it.initialize() }
            value.initialize()
            state = State.INITIALIZED
        }
    }

    override fun compute() {
        if (!computed) {
            dependencies.forEach { it.compute() }
            value.compute()
            state = State.COMPUTED
        }
    }

    override fun invalidate() {
        if (state == State.COMPUTED) {
            value.invalidate()
            state = State.INITIALIZED
            followers.forEach { it.invalidate() }
        }
    }

    override fun reset() {
        if (!unset) {
            value.reset()
            state = State.UNSET
            followers.forEach { it.invalidate() }
        }
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        initialize()
        compute()
        return value
    }
}

