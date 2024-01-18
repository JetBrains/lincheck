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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.utils.*

class EventIndex<E : Event, C : Enum<C>, K : Any> private constructor(
    nCategories: Int,
    private val classifier: (E) -> Pair<C, K>
) {
    private val index = Array<MutableMap<K, MutableSet<E>>>(nCategories) { mutableMapOf() }

    fun index(event: E) {
        val (category, key) = classifier(event)
        index[category.ordinal].updateInplace(key, default = linkedSetOf()) { add(event) }
    }

    fun index(events: Collection<E>) {
        events.forEach { index(it) }
    }

    operator fun get(category: C, key: K): Set<E> {
        return index[category.ordinal][key] ?: emptySet()
    }

    fun reset() {
        index.forEach { it.clear() }
    }

    fun rebuild(events: Collection<E>) {
        reset()
        index(events)
    }

    companion object {
        inline fun <E : Event, reified C : Enum<C>, K : Any> create(
            noinline classifier: (E) -> Pair<C, K>
        ): EventIndex<E, C, K> {
            // We cannot directly call the private constructor taking the number of categories,
            // because calling private methods from public inline methods is forbidden.
            // Neither we want to expose this constructor, because passing
            // an invalid number of categories will cause runtime exceptions.
            // Thus, we introduce an auxiliary factory method taking an object of enum class as a witness.
            // This constructor infers the correct total number of categories through the passed witness.
            val categories = enumValues<C>()
            return if (categories.isNotEmpty())
                create(categories[0], classifier)
            else
                create()
        }

        fun <E : Event, C : Enum<C>, K : Any> create(witness: C, classifier: (E) -> Pair<C, K>): EventIndex<E, C, K> {
            return EventIndex(witness.declaringClass.enumConstants.size, classifier)
        }

        fun <E : Event, C : Enum<C>, K : Any> create(): EventIndex<E, C, K> {
            return EventIndex(0) { throw UnsupportedOperationException() }
        }
    }
}