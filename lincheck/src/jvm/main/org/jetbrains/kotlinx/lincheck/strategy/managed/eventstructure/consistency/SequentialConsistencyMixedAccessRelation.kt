/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency

import org.jetbrains.kotlinx.lincheck.strategy.managed.MemoryLocation
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.util.mutableThreadMapOf
import org.jetbrains.lincheck.util.CachedRelation
import org.jetbrains.lincheck.util.MemoryOrdering
import org.jetbrains.lincheck.util.RelationMatrix

/**
 * Orders volatile accesses in an execution that may also contain weaker memory accesses.
 *
 * Uses the following PSC (partial sequential consistency order) relation variant between volatile reads and writes:
 *     `[Vol] ; (po + rf + co + rb + hb_loc + po;hb;po) ; [Vol]`.
 *
 * When no location mixes volatile accesses with weaker modes, the read/write constraint uses simpler formula:
 *     `[Vol] ; (hb + rf + co + rb) ; [Vol]`.
 *
 * Note that `rf` edges are already included in `hb_loc` / `hb`,
 * so the `rf` component is optional in the definitions above.
 *
 * Sequentially consistent fences `fullFence()` additionally contribute the following edges:
 *     `[Fvol] ; (hb + hb;eco_psc;hb) ; [Fvol]`
 *     `[Vol] ; (co + rb + hb_loc) ; hb ; [Fvol]`
 *     `[Fvol] ; hb ; (co + rb + hb_loc) ; [Vol]`
 *
 * Here `eco_psc` is the supplied [extendedCoherence] relation,
 * extended under [MemoryModel.RelaxedJava] with `[Rplain];rb;rf;[Rplain]`.
 *
 * Strong happens-before, `shb := po + po;hb;po`, also relates volatile accesses and fences in either direction:
 *     `[Vol] ; shb ; [Vol]`.
 *
 * The relation is not transitively closed, thus it is not an order by itself;
 * it is only suitable for checking acyclicity, which is equivalent to irreflexivity of its transitive closure.
 *
 *
 * [compute] caches edges involving a fence in a matrix containing volatile reads, writes, and fences;
 * read/write pairs are evaluated directly, with no matrix allocated when volatile fences are absent.
 * Per-location coherence frontiers summarize the accesses before and after each fence.
 * Reads contribute their source writes, retaining their distinction from writes for `rf` edges.
 * Separate happens-before frontiers preserve the `hb_loc` components.
 * Pruning requires [coherenceOrder] to be a strict partial order on same-location writes.
 * When source writes at a location are totally ordered, each upper/lower frontier has at most one entry.
 * Changes require [invalidate] followed by [compute]; the relation is not maintained incrementally.
 *
 * See the following paper on the details of the PSC relation definition:
 *   - [Repairing sequential consistency in C/C++ 11](https://dl.acm.org/doi/pdf/10.1145/3140587.3062352).
 */
class SequentialConsistencyMixedAccessRelation(
    val execution: Execution<AtomicThreadEvent>,
    val extendedCoherence: ExtendedCoherenceOrder,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val memoryModel: MemoryModel,
) : CachedRelation<AtomicThreadEvent> {

    val coherenceOrder = extendedCoherence.coherenceOrder

    private var computed = false

    // whether the execution does not mix volatile and non-volatile accesses to the same location
    private var unmixed = false

    private var fenceMatrix: RelationMatrix<AtomicThreadEvent>? = null

    override fun invalidate() {
        computed = false
        fenceMatrix = null
    }

    override fun compute() {
        if (computed) return
        unmixed = memoryAccessEventIndex.locationInfo.values.none { info ->
            MemoryOrdering.VOLATILE in info.accessModes && info.accessModes.any { it != MemoryOrdering.VOLATILE }
        }
        fenceMatrix = computeFenceMatrix()
        computed = true
    }

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        // skip non-volatile accesses
        if (!x.isVolatileEvent() || !y.isVolatileEvent()) return false

        if (x.isVolatileFence() || y.isVolatileFence()) {
            return fenceMatrix?.get(x, y) ?: false
        }
        return pscReadWrite(x, y)
    }

    private fun pscReadWrite(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        if (unmixed) {
            // hb + co + rb
            return happensBeforeOrder(x, y) || coherence(x, y) || readsBefore(x, y)
        }
        // po + co + rb + hb_loc + po;hb;po
        return programOrder(x, y) || communication(x, y) || pscStrongHappensBefore(x, y)
    }

    private fun pscFence(
        x: AtomicThreadEvent,
        y: AtomicThreadEvent,
        frontiers: Map<AtomicThreadEvent, Map<MemoryLocation, FenceFrontiers>>,
    ): Boolean {
        if (!x.isVolatileFence() && !y.isVolatileFence()) return false

        if (x.isVolatileFence() && y.isVolatileFence()) {
            // hb
            if (happensBeforeOrder(x, y)) return true

            // hb ; eco_psc ; hb
            val xFrontiers = frontiers.getValue(x)
            val yFrontiers = frontiers.getValue(y)
            return xFrontiers.any { (location, xFront) ->
                val yFront = yFrontiers[location] ?: return@any false
                xFront.upper.any { a ->
                    yFront.lower.any { b ->
                        extendedCoherenceHelper(a, b)
                    }
                }
            }
        }

        // po
        if (programOrder(x, y)) return true

        if (x.isVolatileFence()) {
            val frontier = frontiers.getValue(x)[(y.label as MemoryAccessLabel).location]
            // hb ; (co + rb + hb_loc)
            if (frontier != null && (
                y.label is WriteAccessLabel && frontier.upper.any { coherenceOrder(it.writeOf(), y) } ||
                frontier.upperHappensBefore.values.any { happensBeforeOrder(it, y) }
            )) return true
        } else /* if (y.isVolatileFence()) */ {
            val frontier = frontiers.getValue(y)[(x.label as MemoryAccessLabel).location]
            val source = x.writeOf()
            // (co + rb + hb_loc) ; hb
            if (frontier != null && (
                frontier.lowerWrites.any { coherenceOrder(source, it.writeOf()) } ||
                frontier.lowerHappensBefore.values.any { happensBeforeOrder(x, it) }
            )) return true
        }

        // po ; hb ; po
        if (pscStrongHappensBefore(x, y)) return true

        return false
    }

    private fun pscStrongHappensBefore(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        val xSuccessor = execution.nextEvent(x) ?: return false
        val yPredecessor = execution.previousEvent(y) ?: return false
        return xSuccessor == yPredecessor || happensBeforeOrder(xSuccessor, yPredecessor)
    }

    private fun computeFenceMatrix(): RelationMatrix<AtomicThreadEvent>? {
        val volatileAccesses = execution.filter { it.isVolatileEvent() }
        val volatileFences = volatileAccesses.filter { it.isVolatileFence() }
        if (volatileFences.isEmpty()) return null

        val matrix = RelationMatrix(volatileAccesses, buildEnumerator(volatileAccesses))
        val accesses = execution
            .filter { it.isWriteOrReadResponse() }
            .groupBy { (it.label as MemoryAccessLabel).location }
        val frontiers = volatileFences.associateWith { computeFenceFrontiers(it, accesses) }
        for (x in volatileFences) {
            for (y in volatileFences) {
                // hb ; eco_psc ; hb can relate a fence to itself.
                matrix[x, y] = pscFence(x, y, frontiers)
            }
            for (y in volatileAccesses) {
                if (y.isVolatileFence()) continue
                matrix[x, y] = pscFence(x, y, frontiers)
                matrix[y, x] = pscFence(y, x, frontiers)
            }
        }
        return matrix
    }

    private class FenceFrontiers {
        val upper = arrayListOf<AtomicThreadEvent>()
        val lower = arrayListOf<AtomicThreadEvent>()

        // (co + rb) ; hb needs writes that reads may subsume in lower.
        val lowerWrites = arrayListOf<AtomicThreadEvent>()

        val upperHappensBefore = mutableThreadMapOf<AtomicThreadEvent>()
        val lowerHappensBefore = mutableThreadMapOf<AtomicThreadEvent>()
    }

    private fun computeFenceFrontiers(
        fence: AtomicThreadEvent,
        accesses: Map<MemoryLocation, List<AtomicThreadEvent>>,
    ): Map<MemoryLocation, FenceFrontiers> {
        val frontiers = mutableMapOf<MemoryLocation, FenceFrontiers>()
        for ((location, events) in accesses) {
            val frontier = FenceFrontiers()
            var updated = false

            for (event in events) {
                val after = happensBeforeOrder(fence, event)
                val before = happensBeforeOrder(event, fence)
                if (!after && !before) continue

                updated = true
                if (after) {
                    addToCoherenceFrontier(frontier.upper, event, minimal = true)
                    addToHappensBeforeFrontier(frontier.upperHappensBefore, event, minimal = true)
                }
                if (before) {
                    addToCoherenceFrontier(frontier.lower, event, maximal = true)
                    addToHappensBeforeFrontier(frontier.lowerHappensBefore, event, maximal = true)

                    if (event.label is WriteAccessLabel) {
                        // (co + rb) ; hb: only writes can be the intermediate target.
                        addToCoherenceFrontier(frontier.lowerWrites, event, maximal = true)
                    }
                }
            }
            if (updated) frontiers[location] = frontier
        }
        return frontiers
    }

    private fun addToCoherenceFrontier(
        frontier: MutableList<AtomicThreadEvent>,
        event: AtomicThreadEvent,
        minimal: Boolean = false,
        maximal: Boolean = false,
    ) {
        require(minimal xor maximal)
        val toRemove = mutableSetOf<AtomicThreadEvent>()
        for (index in frontier.indices) {
            val existing = frontier[index]
            val first = if (minimal) existing else event
            val last = if (minimal) event else existing
            // if the new event is subsumed (for eco queries) by any existing,
            // then we do not add it to the frontier.
            if (coherencePrecedesOrEquals(first, last)) {
                return
            }
            // if the new event subsumes (for eco queries) any existing,
            // then we remove the existing from the frontier
            if (coherencePrecedesOrEquals(last, first)) {
                toRemove.add(existing)
            }
        }
        frontier.removeAll(toRemove)
        frontier.add(event)
    }

    private fun addToHappensBeforeFrontier(
        frontier: MutableMap<Int, AtomicThreadEvent>,
        event: AtomicThreadEvent,
        minimal: Boolean = false,
        maximal: Boolean = false,
    ) {
        require(minimal xor maximal)
        // The first/last access in each thread subsumes the others for hb_loc queries.
        val existing = frontier[event.threadId]
        if (existing == null ||
            if (minimal)
                event.threadPosition < existing.threadPosition
            else
                event.threadPosition > existing.threadPosition
        ) {
            frontier[event.threadId] = event
        }
    }

    private fun coherencePrecedesOrEquals(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        // Reads of the same write have identical eco_psc predecessors and successors, regardless of access mode.
        val xWrite = x.writeOf()
        val yWrite = y.writeOf()
        if (xWrite == yWrite) return x.label !is ReadAccessLabel || y.label is ReadAccessLabel
        return coherenceOrder(xWrite, yWrite)
    }

    private fun programOrder(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean =
        x.threadId == y.threadId && x.threadPosition < y.threadPosition

    private fun coherence(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean =
        x.label is WriteAccessLabel && y.label is WriteAccessLabel && coherenceOrder(x, y) // assumes `co <= same_loc`

    private fun readsBefore(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean =
        x.label is ReadAccessLabel && y.label is WriteAccessLabel && coherenceOrder(x.readsFrom, y) && sameLocation(x, y)

    private fun communication(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        if (!sameLocation(x, y)) return false
        // co + rb + hb_loc
        return coherence(x, y) || readsBefore(x, y) || happensBeforeOrder(x, y)
    }

    private fun extendedCoherenceHelper(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        if (extendedCoherence(x, y)) return true
        if (memoryModel != MemoryModel.RelaxedJava) return false
        // [Rplain] ; rb ; rf ; [Rplain]
        return x.isPlainRead() && y.isPlainRead() && sameLocation(x, y) &&
            coherenceOrder(x.readsFrom, y.readsFrom)
    }
}

private fun AtomicThreadEvent.writeOf(): AtomicThreadEvent =
    if (label is ReadAccessLabel) readsFrom else this

private fun AtomicThreadEvent.isPlainRead(): Boolean =
    (label as? ReadAccessLabel)?.memoryOrdering == MemoryOrdering.PLAIN

private fun AtomicThreadEvent.isWriteOrReadResponse(): Boolean =
    label is WriteAccessLabel || (label is ReadAccessLabel && label.isResponse)

internal fun AtomicThreadEvent.isVolatileFence(): Boolean =
    (label as? FenceLabel)?.memoryOrdering == MemoryOrdering.VOLATILE

internal fun AtomicThreadEvent.isVolatileEvent(): Boolean {
    if (isVolatileFence()) return true
    val label = label as? MemoryAccessLabel ?: return false
    return label.memoryOrdering == MemoryOrdering.VOLATILE && (label.isWrite || label.isResponse)
}
