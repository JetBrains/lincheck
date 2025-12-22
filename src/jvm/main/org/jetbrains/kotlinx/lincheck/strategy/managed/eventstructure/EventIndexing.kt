/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.updateInplace


typealias EventIndexClassifier<E, C, K> = (E) -> Pair<C, K>?

interface EventIndex<E : Event, C : Enum<C>, K : Any> {

    operator fun get(category: C, key: K): SortedList<E>

    fun enumerator(category: C, key: K): Enumerator<E>?
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

    private val index = Array<MutableMap<K, SortedMutableList<E>>>(nCategories) { mutableMapOf() }

    override operator fun get(category: C, key: K): SortedList<E> {
        return index[category.ordinal][key] ?: sortedListOf()
    }

    override fun index(category: C, key: K, event: E) {
        index[category.ordinal].updateInplace(key, default = sortedMutableListOf()) { add(event) }
    }

    override fun enumerator(category: C, key: K): Enumerator<E>? {
        return index[category.ordinal][key]?.toEnumerator()
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
            return EventIndexImpl(witness.declaringJavaClass.enumConstants.size, classifier)
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

    // TODO: move race status maintenance logic into separate class (?)
    interface LocationInfo {
        val isReadWriteRaceFree: Boolean
        val isWriteWriteRaceFree: Boolean
    }

    val locationInfo: Map<MemoryLocation, LocationInfo>

    fun getReadRequests(location: MemoryLocation) : SortedList<AtomicThreadEvent> =
        get(AtomicMemoryAccessCategory.ReadRequest, location)

    fun getReadResponses(location: MemoryLocation): SortedList<AtomicThreadEvent> =
        get(AtomicMemoryAccessCategory.ReadResponse, location)

    fun getWrites(location: MemoryLocation): SortedList<AtomicThreadEvent> =
        get(AtomicMemoryAccessCategory.Write, location)

}

interface MutableAtomicMemoryAccessEventIndex :
    AtomicMemoryAccessEventIndex,
    MutableEventIndex<AtomicThreadEvent, AtomicMemoryAccessCategory, MemoryLocation>

val AtomicMemoryAccessEventIndex.locations: Set<MemoryLocation>
    get() = locationInfo.keys

val AtomicMemoryAccessEventIndex.LocationInfo.isRaceFree: Boolean
    get() = isReadWriteRaceFree && isWriteWriteRaceFree

fun AtomicMemoryAccessEventIndex.isWriteWriteRaceFree(location: MemoryLocation): Boolean =
    locationInfo[location]?.isWriteWriteRaceFree ?: true

fun AtomicMemoryAccessEventIndex.isReadWriteRaceFree(location: MemoryLocation): Boolean =
    locationInfo[location]?.isReadWriteRaceFree ?: true

fun AtomicMemoryAccessEventIndex.isRaceFree(location: MemoryLocation): Boolean =
    locationInfo[location]?.isRaceFree ?: true

fun AtomicMemoryAccessEventIndex.getLastWrite(location: MemoryLocation): AtomicThreadEvent? =
    getWrites(location).lastOrNull()


fun AtomicMemoryAccessEventIndex(): AtomicMemoryAccessEventIndex =
    MutableAtomicMemoryAccessEventIndex()

fun MutableAtomicMemoryAccessEventIndex(): MutableAtomicMemoryAccessEventIndex =
    MutableAtomicMemoryAccessEventIndexImpl()

typealias AtomicMemoryAccessEventClassifier =
        EventIndexClassifier<AtomicThreadEvent, AtomicMemoryAccessCategory, MemoryLocation>

private class MutableAtomicMemoryAccessEventIndexImpl : MutableAtomicMemoryAccessEventIndex {

    private data class LocationInfoData(
        override var isReadWriteRaceFree: Boolean,
        override var isWriteWriteRaceFree: Boolean,
        // TODO: also handle case when all accesses are totally ordered,
        //   i.e. there is no even read-read "races"
    ) : AtomicMemoryAccessEventIndex.LocationInfo {
        constructor(): this(isReadWriteRaceFree = true, isWriteWriteRaceFree = true)
    }

    private val _locationInfo = mutableMapOf<MemoryLocation, LocationInfoData>()

    override val locationInfo: Map<MemoryLocation, AtomicMemoryAccessEventIndex.LocationInfo>
        get() = _locationInfo

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

    private val index = MutableEventIndex<AtomicThreadEvent, AtomicMemoryAccessCategory, MemoryLocation>(classifier)

    override fun get(category: AtomicMemoryAccessCategory, key: MemoryLocation): SortedList<AtomicThreadEvent> =
        index[category, key]

    override fun index(category: AtomicMemoryAccessCategory, key: MemoryLocation, event: AtomicThreadEvent) =
        index.index(category, key, event)

    override fun index(event: AtomicThreadEvent) {
        val label = (event.label as? MemoryAccessLabel) ?: return
        if (label.location !in locations) {
            /* If the indexed event is the first memory access to a given location,
             * then we also need to add the object allocation event
             * to the index for this memory location.
             */
            index.index(AtomicMemoryAccessCategory.Write, label.location, event.allocation!!)
            /* Also initialize the race status data */
            _locationInfo[label.location] = LocationInfoData()
        }
        updateRaceStatus(label.location, event)
        index.index(event)
    }

    private fun updateRaceStatus(location: MemoryLocation, event: AtomicThreadEvent) {
        val info = _locationInfo[location]!!
        if (!info.isWriteWriteRaceFree && !info.isReadWriteRaceFree)
            return
        when {
            event.label is WriteAccessLabel -> {
                if (info.isWriteWriteRaceFree) {
                    // to detect write-write race,
                    // it is sufficient to check only against the latest write event
                    val lastWrite = getLastWrite(location)!!
                    info.isWriteWriteRaceFree = causalityOrder(lastWrite, event)
                }
                if (info.isReadWriteRaceFree) {
                    // to detect read-write race,
                    // we need to check against all the read-request events
                    info.isReadWriteRaceFree = getReadRequests(location).all { read ->
                        causalityOrder(read, event)
                    }
                }
            }

            // in race-free case, to detect read-write race
            // it is sufficient to check only against the latest write event
            event.label is ReadAccessLabel && info.isRaceFree -> {
                val lastWrite = getLastWrite(location)!!
                if (causalityOrder(lastWrite, event))
                    return
                check(causalityOrder.unordered(lastWrite, event))
                info.isReadWriteRaceFree = false
            }

            // in case when there was already a write-write race, to detect read-write race,
            // we need to check against all the write events
            event.label is ReadAccessLabel && info.isReadWriteRaceFree -> {
                info.isReadWriteRaceFree = getWrites(location).all { write ->
                    causalityOrder(write, event)
                }
            }
        }
    }

    override fun enumerator(category: AtomicMemoryAccessCategory, key: MemoryLocation): Enumerator<AtomicThreadEvent>? =
        index.enumerator(category, key)

    override fun reset() {
        _locationInfo.clear()
        index.reset()
    }

}