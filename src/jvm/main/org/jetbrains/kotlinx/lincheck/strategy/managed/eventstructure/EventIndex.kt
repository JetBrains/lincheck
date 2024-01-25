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

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.utils.*


typealias EventIndexClassifier<E, C, K> = (E) -> Pair<C, K>?

interface EventIndex<E : Event, C : Enum<C>, K : Any> {
    operator fun get(category: C, key: K): SortedList<E>
}

interface MutableEventIndex<E : Event, C : Enum<C>, K : Any> : EventIndex<E, C, K> {

    val classifier: EventIndexClassifier<E, C, K>

    fun index(category: C, key: K, event: E)

    fun index(event: E) {
        val (category, key) = classifier(event) ?: return
        index(category, key, event)
    }

    fun index(events: Collection<E>) {
        events.enumerationOrderSorted().forEach { index(it) }
    }

    fun rebuild(events: Collection<E>) {
        reset()
        index(events)
    }

    fun reset()

}

inline fun <E : Event, reified C : Enum<C>, K : Any> EventIndex(
    noinline classifier: EventIndexClassifier<E, C, K>
): EventIndex<E, C, K> {
    return MutableEventIndex(classifier)
}

inline fun <E : Event, reified C : Enum<C>, K : Any> MutableEventIndex(
    noinline classifier: EventIndexClassifier<E, C, K>
): MutableEventIndex<E, C, K> {
    return EventIndexImpl.create(classifier)
}

// TODO: make this class private
class EventIndexImpl<E : Event, C : Enum<C>, K : Any> private constructor(
    nCategories: Int,
    override val classifier: EventIndexClassifier<E, C, K>
) : MutableEventIndex<E, C, K> {

    private val index = Array<MutableMap<K, SortedArrayList<E>>>(nCategories) { mutableMapOf() }

    override operator fun get(category: C, key: K): SortedList<E> {
        return index[category.ordinal][key] ?: sortedArrayListOf()
    }

    override fun index(category: C, key: K, event: E) {
        index[category.ordinal].updateInplace(key, default = sortedArrayListOf()) { add(event) }
    }

    override fun reset() {
        index.forEach { it.clear() }
    }

    companion object {
        inline fun <E : Event, reified C : Enum<C>, K : Any> create(
            noinline classifier: EventIndexClassifier<E, C, K>
        ): MutableEventIndex<E, C, K> {
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

        fun <E : Event, C : Enum<C>, K : Any> create(
            witness: C,
            classifier: (E) -> Pair<C, K>?
        ): MutableEventIndex<E, C, K> {
            return EventIndexImpl(witness.declaringClass.enumConstants.size, classifier)
        }

        fun <E : Event, C : Enum<C>, K : Any> create(): MutableEventIndex<E, C, K> {
            return EventIndexImpl(0) { throw UnsupportedOperationException() }
        }
    }
}

enum class AtomicMemoryAccessCategory {
    ReadRequest,
    ReadResponse,
    Write,
}

interface AtomicMemoryAccessEventIndex : EventIndex<AtomicThreadEvent, AtomicMemoryAccessCategory, MemoryLocation> {

    val locations: Set<MemoryLocation>

    fun getReadRequests(location: MemoryLocation) : SortedList<AtomicThreadEvent> {
        return get(AtomicMemoryAccessCategory.ReadRequest, location)
    }

    fun getReadResponses(location: MemoryLocation): SortedList<AtomicThreadEvent> {
        return get(AtomicMemoryAccessCategory.ReadResponse, location)
    }

    fun getWrites(location: MemoryLocation): SortedList<AtomicThreadEvent> {
        return get(AtomicMemoryAccessCategory.Write, location)
    }

}

typealias AtomicMemoryAccessEventClassifier =
    EventIndexClassifier<AtomicThreadEvent, AtomicMemoryAccessCategory, MemoryLocation>

interface MutableAtomicMemoryAccessEventIndex :
    AtomicMemoryAccessEventIndex,
    MutableEventIndex<AtomicThreadEvent, AtomicMemoryAccessCategory, MemoryLocation>

fun AtomicMemoryAccessEventIndex(objectRegistry: ObjectRegistry): AtomicMemoryAccessEventIndex =
    MutableAtomicMemoryAccessEventIndex(objectRegistry)

fun MutableAtomicMemoryAccessEventIndex(objectRegistry: ObjectRegistry): AtomicMemoryAccessEventIndex =
    MutableAtomicMemoryAccessEventIndexImpl(objectRegistry)

private class MutableAtomicMemoryAccessEventIndexImpl(
    val objectRegistry: ObjectRegistry
) : MutableAtomicMemoryAccessEventIndex {

    override val classifier: AtomicMemoryAccessEventClassifier = { event ->
        val label = (event.label as? MemoryAccessLabel)
        when {
            label is ReadAccessLabel && label.isRequest ->
                (AtomicMemoryAccessCategory.ReadRequest to label.location)

            label is ReadAccessLabel && label.isResponse ->
                (AtomicMemoryAccessCategory.ReadResponse to label.location)

            label is WriteAccessLabel ->
                (AtomicMemoryAccessCategory.Write to label.location)

            else -> null
        }
    }

    override val locations = mutableSetOf<MemoryLocation>()

    private val index = MutableEventIndex<AtomicThreadEvent, AtomicMemoryAccessCategory, MemoryLocation>(classifier)

    override fun get(category: AtomicMemoryAccessCategory, key: MemoryLocation): SortedList<AtomicThreadEvent> =
        index[category, key]

    override fun index(category: AtomicMemoryAccessCategory, key: MemoryLocation, event: AtomicThreadEvent) =
        index.index(category, key, event)

    override fun index(event: AtomicThreadEvent) {
        val label = (event.label as? MemoryAccessLabel) ?: return
        val isNewLocation = locations.add(label.location)
        if (isNewLocation) {
            // if the indexed event is the first memory access to a given location,
            // then also add the object allocation event to the index for this memory location
            val objEntry = objectRegistry[label.location.objID]!!
            index.index(AtomicMemoryAccessCategory.Write, label.location, objEntry.allocation)
        }
        index.index(event)
    }

    override fun reset() {
        locations.clear()
        index.reset()
    }

}