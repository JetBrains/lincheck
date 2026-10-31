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

import org.jetbrains.kotlinx.lincheck.strategy.managed.MemoryLocation
import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectNumber

/**
 * Returns true if the given event label should be checked for maximality.
 */
internal val EventLabel.isMaximalityCheckedResponse: Boolean get() =
    (this is ReadAccessLabel && isResponse) ||
    isNonReentrantLockResponse
    // TODO: should we handle other types of events?

/**
 * Prepares the maximal extension check for a backward revisit of [request] by [send].
 * Returns null if causal closure or preservation of previous backward choices already rejects the revisit.
 *
 * After cutting [conflicts] other than the old response, and their causal successors,
 * let `retained` contain the events that:
 *   - have [insertionId] at most [request]'s `id`, or
 *   - strictly causally precede [send].
 * A response checked for maximality uses its request's `id` as its [insertionId],
 * even if the response itself was created later.
 * Let `extension` contain all other events from [execution], plus the old response if it exists.
 *
 * Causal closure and backward-choice preservation are checked against this retained prefix.
 * The old response is then cut from it before constructing the reference order,
 * so its reads-from edge cannot constrain that order.
 *
 * Intuitively, the maximal extension condition says that every event in `extension`
 * should be maximally-added, that is, starting from the retained execution with the old response cut,
 * if we add the `extension` events in [insertionId] order, choosing a "maximal" position at each step,
 * we will eventually generate the same set of `extension` events, reaching the same [execution].
 *
 * The exact notion of "maximal" position is defined by the execution's memory model,
 * but typically it means that:
 * - write event has maximal position in its coherence order;
 * - read-response event reads-from coherence-maximal write event.
 * This notion can be naturally extended to other types of events.
 *
 * In addition, conflicts must not causally precede [request] or [send].
 *
 * The maximal extension condition idea is taken from the paper:
 *   - Truly Stateless, Optimal Dynamic Partial Order Reduction (https://dl.acm.org/doi/pdf/10.1145/3498711)
 */
internal fun prepareMaximalRevisitCheck(
    execution: Execution<AtomicThreadEvent>,
    request: AtomicThreadEvent,
    send: AtomicThreadEvent,
    conflicts: List<AtomicThreadEvent> = emptyList(),
): MaximalRevisitChecker? {
    val oldResponse = execution[request.threadId, request.threadPosition + 1]
    check(oldResponse == null || oldResponse.label.isMaximalityCheckedResponse)

    val retainedFrontier = execution.toMutableFrontier().apply {
        cut(conflicts.filter { it != oldResponse })
        // The conceptual deleted set includes the new sender, which is re-added in the target graph.
        cut { it.insertionId > request.id && !causalityOrder(it, send) }
    }

    // Retained events should not causally depend on the events that are going to be deleted.
    if (!isCausallyClosed(retainedFrontier)) return null

    val executionFrontier = execution.toFrontier()
    // Exclude the old response's reads-from edge from the reference order.
    val referenceFrontier = retainedFrontier.copy().apply { if (oldResponse != null) cut(oldResponse) }

    // A backward response may be removed only when its source survives in the retained prefix.
    val extension = executionFrontier.eventsDifference(referenceFrontier)
    if (!preservesBackwardChoices(extension, retainedFrontier)) return null

    return MaximalRevisitChecker(referenceFrontier, executionFrontier)
}

/**
 * Saves the original partition until the revisit is selected for exploration.
 *
 * The extension is recomputed on demand from [executionFrontier],
 * so that a pending check holds only `O(threads)` events rather than the whole extension.
 */
internal class MaximalRevisitChecker(
    val retainedFrontier: ExecutionFrontier<AtomicThreadEvent>,
    private val executionFrontier: ExecutionFrontier<AtomicThreadEvent>,
) {
    /** Requires [retained] to be the saved prefix, with its consistency already checked. */
    fun isMaximal(retained: ExtendedExecution): Boolean =
        isMaximalExtension(executionFrontier.eventsDifference(retainedFrontier), retained)
}

/**
 * Cuts the execution frontier for the backward revisit of [request] by [response].
 * Keeps only the events with id smaller than [request]'s id, or events causally preceding [response].
 */
internal fun MutableExecutionFrontier<AtomicThreadEvent>.cutForBackwardRevisit(
    request: AtomicThreadEvent,
    response: AtomicThreadEvent,
) {
    cut { !(it.insertionId <= request.id || causalityOrder(it, response)) }
}

/**
 * The logical insertion ID, using the request's ID for responses checked for maximality.
 */
private val ThreadEvent.insertionId: EventID
    get() = if (label.isMaximalityCheckedResponse) parent!!.id else id

private fun isCausallyClosed(frontier: ExecutionFrontier<AtomicThreadEvent>): Boolean =
    // Each retained thread is a prefix, so its last clock covers all retained dependencies.
    frontier.events.all { event ->
        frontier.threadIDs.all { tid -> event.causalityClock[tid] <= frontier.getPosition(tid) }
    }

private fun preservesBackwardChoices(
    extension: Sequence<AtomicThreadEvent>,
    retained: ExecutionFrontier<AtomicThreadEvent>,
): Boolean =
    extension.all { event ->
        if (!event.label.isMaximalityCheckedResponse) return@all true
        val syncFrom = event.syncFrom
        // A source created after the request was unavailable when the request was first added.
        // This response can be removed only if that source survives in the retained prefix.
        event.insertionId > syncFrom.id || syncFrom in retained
    }

private fun isMaximalExtension(extension: Sequence<AtomicThreadEvent>, retained: ExtendedExecution): Boolean {
    val cursors = EventCursorMapping(retained)
    // The reference order puts retained send events first, then appends removed send events in the insertion order.
    // Each appended send is maximal by construction; each response must use the current last send.
    for (event in extension.sortedBy { it.insertionId }) {
        val cursor = cursors[event] ?: continue
        if (event.label.isResponse) {
            if (event.syncFrom != cursor.latest) return false
        } else {
            cursor.latest = event
        }
    }
    return true
}

private class EventCursor(var latest: AtomicThreadEvent?)

private class EventCursorMapping(private val retained: ExtendedExecution) {

    // maps each memory location to the latest write event that accesses it
    private val memory = mutableMapOf<MemoryLocation, EventCursor>()

    // maps each mutex to the latest unlock event that unlocks it
    private val locks = mutableMapOf<ObjectNumber, EventCursor>()

    private val retainedUnlocks by lazy(LazyThreadSafetyMode.NONE) {
        retained.filter { it.label.isNonReentrantUnlock }.groupBy { (it.label as MutexLabel).mutexID }
    }

    operator fun get(event: AtomicThreadEvent): EventCursor? {
        val label = event.label
        return when {

            label is WriteAccessLabel || label is ReadAccessLabel && label.isResponse -> {
                memory.getOrPut(label.location) {
                    val latest = retained.memoryAccessEventIndex.getWrites(label.location)
                        .maxByOrNull { retained.coherenceOrder.position(it) }
                    EventCursor(latest ?: event.allocation)
                }
            }

            label.isNonReentrantUnlock || label.isNonReentrantLockResponse -> {
                val mutex = (label as MutexLabel).mutexID
                locks.getOrPut(mutex) {
                    val latest = retainedUnlocks[mutex]?.maxByOrNull { retained.lockOrder.position(it) }
                    EventCursor(latest ?: event.allocation)
                }
            }

            else -> null
        }
    }
}
