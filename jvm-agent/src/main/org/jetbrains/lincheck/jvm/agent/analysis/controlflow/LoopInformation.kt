/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.analysis.controlflow

// ==== Loop API (no detection yet) ====

/** Per-method, stable id for a detected loop. */
typealias LoopId = Int

/** Basic-block-level edge. */

/**
 * [LoopInformation] contains information about a single loop.
 *
 * In reducible graphs, [headers] has a single element and [header] equals that element.
 * In irreducible graphs, multiple headers may exist; [header] is a canonical representative
 * (e.g., the minimum index among [headers]).
 */
data class LoopInformation(
    val id: LoopId,
    val header: BasicBlockIndex,
    val headers: Set<BasicBlockIndex>,
    val body: Set<BasicBlockIndex>,
    val backEdges: Set<Edge>,
    val normalExits: Set<Edge>,
    val exceptionalExitHandlers: Set<BasicBlockIndex>,
    val reducible: Boolean,
)

/**
 * [MethodLoopsInformation] contains information about all loops detected in a single method.
 */
class MethodLoopsInformation(
    val loops: List<LoopInformation> = emptyList(),
    val loopsByBlock: Map<BasicBlockIndex, List<LoopId>> = emptyMap(),
) {
    fun hasLoops(): Boolean =
        loops.isNotEmpty()

    fun loop(id: LoopId): LoopInformation =
        loops.first { it.id == id }

    fun headerOf(id: LoopId): BasicBlockIndex =
        loop(id).header

    fun bodyOf(id: LoopId): Set<BasicBlockIndex> =
        loop(id).body

    fun backEdgesOf(id: LoopId): Set<Edge> =
        loop(id).backEdges
    
    fun normalExitsOf(id: LoopId): Set<Edge> =
        loop(id).normalExits

    fun exceptionalExitHandlersOf(id: LoopId): Set<BasicBlockIndex> =
        loop(id).exceptionalExitHandlers
}
