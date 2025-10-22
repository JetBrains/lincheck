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

import org.jetbrains.lincheck.util.*
import kotlin.collections.withIndex


/** Per-method, stable id for a detected loop. */
typealias LoopId = Int

/**
 * Describes a single loop detected in a basic-block control-flow graph (CFG) of a method.
 *
 * In reducible graphs, [headers] contains exactly one element and [header] equals that element.
 * In irreducible graphs, [headers] may contain multiple entries;
 * in this case [header] is a canonical representative (e.g., the minimal block index in [headers]).
 *
 * A CFG is called reducible if every loop has a single entry point block (a header)
 * that dominates all other nodes in that loop.
 * Conventional structured control-flow constructs produced by Java/Kotlin compilers
 * (while/for/do-while, if/else, break/continue, switch, try/catch/finally) normally yield reducible graphs.
 *
 * A CFG is irreducible if a loop has multiple distinct entry points with no single dominator.
 * Irreducible graphs arise due to unstructured goto jumps.
 * They should not normally appear in regular code produced by Java/Kotlin compiler,
 * and typically can only appear in custom generated or modified bytecode.
 *
 * @property id A per-method, stable identifier of this loop.
 *   Ids are unique only within a single method.
 *
 * @property header The canonical header basic block index of the loop.
 *   For reducible graphs this is the single entry block that dominates the entire loop body.
 *   For irreducible cases it is a chosen representative from [headers].
 *
 * @property headers All basic blocks that serve as entries into this looping region.
 *   Contains a single element for reducible loops; may contain multiple elements for irreducible loops.
 *
 * @property body The set of basic blocks that form the loop body (the strongly connected component of the graph).
 *   Every path that performs one or more iterations stays within this set until it
 *   eventually exits via an edge listed in [normalExits]
 *   or via an exception to one of the [exceptionalExitHandlers].
 *
 * @property backEdges The set of block-level back edges that close the iteration
 *   and return control to the [header] (or one of [headers] for irreducible cases).
 *   These correspond to conditional branches (IF_*) or jumps (e.g., GOTO)
 *   whose target is inside the loop header set.
 *
 * @property normalExits The set of normal (non-exception) edges leaving the loop body,
 *   i.e., edges whose source belongs to [body] and target lies outside [body].
 *   These edges model exits due to a failing loop condition, break statements,
 *   or switch branches that jump outside the loop.
 *
 * @property exceptionalExitHandlers The set of handler basic block indices that are reachable via
 *   an exceptional edge from inside [body] and are located outside [body].
 *   Entering any of these handlers (from loop body) represents an abrupt loop exit.
 *   Note that a handler can be shared by code outside the loop as well,
 *   for instance in the following case `try { while(...) {...}; foo(); } catch (...) { ... }`.
 *   Here, the handler is reachable from both the loop body and outside it (from `foo()` method call).
 *   Thus, injections placed in the exception handlers may need to perform additional checks at runtime
 *   to see whether the injection was reached from loop or not.
 *
 * @property isReducible True if this loop is reducible (i.e., single-header), false otherwise.
 */
 data class LoopInformation(
     val id: LoopId,
     val header: BasicBlockIndex,
     val headers: Set<BasicBlockIndex>,
     val body: Set<BasicBlockIndex>,
     val backEdges: Set<Edge>,
     val normalExits: Set<Edge>,
     val exceptionalExitHandlers: Set<BasicBlockIndex>,
 ) {
     init {
         validate()
     }

     val isReducible: Boolean
         get() = headers.size == 1

     private fun validate() {
         require(headers.isNotEmpty()) {
             "Loop headers set must not be empty"
         }
         require(header in headers) {
             "Canonical header must be one of headers"
         }
         require(body.isNotEmpty()) {
             "Loop body must not be empty"
         }

         require(headers.all { it in body }) {
             "All headers must be inside body"
         }

         require(backEdges.all { it.source in body && it.target in headers }) {
             "Back edges must originate in body and target a header"
         }

         require(normalExits.all { it.source in body && it.target !in body && it.label !is EdgeLabel.Exception }) {
             "Normal exits must go from body to outside and must not be exception edges"
         }

         require(exceptionalExitHandlers.all { it !in body }) {
             "Exceptional exit handlers must be outside of loop body"
         }
     }
 }

/**
 * Aggregates loop information for a single method.
 *
 * @property loops All loops detected in the method.
 * @property loopsByBlock For each basic block, the list of loop ids this block belongs to.
 */
class MethodLoopsInformation(
    val loops: List<LoopInformation> = emptyList(),
    val loopsByBlock: Map<BasicBlockIndex, List<LoopId>> = emptyMap(),
) {
    init {
        require(loops.allIndexed { index, loop -> loop.id == index }) {
            "Loop ids should match their positions in the list"
        }
    }

    /**
     * Returns true if at least one loop was detected.
     */
    fun hasLoops(): Boolean = loops.isNotEmpty()

    /**
     * Returns the loop with the given [id], or null if not found.
     */
    fun getLoopInfo(id: LoopId): LoopInformation? =
        loops.getOrNull(id)
}

// == LOOPS EXTRACTION FROM CFG ==

/**
 * Compute dominator sets for each basic block using the classical iterative algorithm.
 * The entry block is assumed to be block 0 when the graph is non-empty.
 * Returns an array where index i holds the set of dominators of block i (including i).
 *
 * A node d dominates node n if every path from the entry node to n must go through d.
 * By definition, every node dominates itself. The entry node dominates all nodes in
 * a reducible control flow graph.
 *
 * @return An array of dominator sets for each basic block, including the entry block.
 */
internal fun BasicBlockControlFlowGraph.computeDominators(): Array<Set<BasicBlockIndex>> {
    val n = basicBlocks.size
    if (n == 0) return emptyArray()

    val doms: Array<Set<BasicBlockIndex>> = Array(n) { (0 until n).toMutableSet() }
    // Entry is block 0 when present
    doms[0] = setOf(0)

    var changed = true
    while (changed) {
        changed = false
        for (b in 1 until n) {
            // Intersection of dominators of predecessors: dom(b) = {b} + intersect(dom(p1), dom(p2), ...)
            val newSet = allPredecessors.neighbours(b).map { doms[it] }.intersectAll().apply { add(b) }
            if (newSet != doms[b]) {
                doms[b] = newSet
                changed = true
            }
        }
    }
    return doms
}

/**
 * This algorithm cannot detect loops in irreducible graphs. It only produces reducible loops.
 * Thus, expects reducible CFG.
 *
 * Compute loops using dominators and back-edge detection.
 * A back edge is an edge u -> h (non-exception) where h dominates u.
 * For each header h, the loop body is the union of natural loops of its back edges.
 *
 * Note: "natural loops" are loops calculated via the algorithm below.
 * If there is more than one back edge to the same header, the body of the loop is the union of the nodes computed for each back edge.
 * Since loops can nest, a header for one loop can be in the body of (but not the header of) another loop.
 *
 * @param dominators An array of dominator sets for each basic block.
 * @return A list of detected loops, each represented by a [LoopInformation] object.
 *   The list is empty if no loops were detected.
 *   The loops are sorted by the header block index.
 */
internal fun BasicBlockControlFlowGraph.computeLoopsFromDominators(dominators: Array<Set<BasicBlockIndex>>): MethodLoopsInformation {
    require(isReducible) { "Cannot compute loops on irreducible CFG" }
    val n = basicBlocks.size
    require(dominators.size == n) { "Dominator set must be of length $n but got ${dominators.size} instead" }
    if (n == 0) return MethodLoopsInformation()

    // Identify back edges grouped by header h
    val backEdgesByHeader = mutableMapOf<BasicBlockIndex, MutableSet<Edge>>()
    val normalEdges = edges.filter { it.label !is EdgeLabel.Exception }
    for (e in normalEdges) {
        val u = e.source
        val h = e.target
        val domU = dominators.getOrElse(u) { emptySet() }
        if (h in domU) {
            backEdgesByHeader.updateInplace(h, default = mutableSetOf()) { add(e) }
        }
    }
    if (backEdgesByHeader.isEmpty()) return MethodLoopsInformation()

    // For each header, compute loop body as a union of natural loops for each back edge to that header.
    // Also calculate normal and exceptional exits
    val loops = mutableListOf<LoopInformation>()
    var nextLoopId = 0
    for ((h, backEdges) in backEdgesByHeader.toSortedMap()) {
        val body = mutableSetOf<BasicBlockIndex>()
        body.add(h)
        // Start from each back edge source; perform reverse DFS over normal predecessors until reaching h
        for (e in backEdges) {
            val u = e.source
            val stack = ArrayDeque<BasicBlockIndex>()
            // Natural loop includes both u and h initially
            if (body.add(u)) stack.add(u)
            while (stack.isNotEmpty()) {
                val x = stack.removeLast()
                for (p in normalPredecessors.neighbours(x)) {
                    if (body.add(p)) stack.add(p)
                }
            }
        }
        // Normal exits: edges from body to outside, non-exception
        val normalExits = buildSet {
            for (e in normalEdges) {
                if (e.source in body && e.target !in body) add(e)
            }
        }
        // Exceptional exit handlers: targets of exception edges leaving the body
        val exceptionalExitHandlers = buildSet {
            for (e in edges) {
                if (e.label is EdgeLabel.Exception && e.source in body && e.target !in body) {
                    add(e.target)
                }
            }
        }
        loops += LoopInformation(
            id = nextLoopId++,
            header = h,
            headers = setOf(h),
            body = body,
            backEdges = backEdges,
            normalExits = normalExits,
            exceptionalExitHandlers = exceptionalExitHandlers,
        )
    }

    // Map blocks to loop ids
    val loopsByBlock = mutableMapOf<BasicBlockIndex, MutableList<LoopId>>()
    for (loop in loops) {
        for (b in loop.body) {
            loopsByBlock.updateInplace(b, default = mutableListOf()) { add(loop.id) }
        }
    }
    return MethodLoopsInformation(loops = loops, loopsByBlock = loopsByBlock)
}

/**
 * CFG is called *reducible* if removal of its back edges leads to a graph that:
 * * is acyclic (in terms of graph theory)
 * * each basic block of CFG can be reached from the initial one
 *
 * The initial basic block in our case is the basic block at index 0.
 * And reachability calculation can use both normal (except for back-edges) and exceptional edges.
 *
 * See also https://www.csd.uwo.ca/~mmorenom/CS447/Lectures/CodeOptimization.html/node6.html.
 */
internal fun BasicBlockControlFlowGraph.isReducible(dominators: Array<Set<BasicBlockIndex>>): Boolean {
    val n = basicBlocks.size
    // Identify back edges
    val backEdges = mutableSetOf<Edge>()
    val normalEdges = edges.filter { it.label !is EdgeLabel.Exception }
    for (e in normalEdges) {
        val u = e.source
        val h = e.target
        val domU = dominators.getOrElse(u) { emptySet() }
        if (h in domU) {
            backEdges.add(e)
        }
    }
    // Calculate successors for each basic block (excluding back edges)
    val filteredSuccessors: AdjacencyMap = allSuccessors.mapValues { (_, neighs) -> neighs.filter { it !in backEdges }.toSet() }

    // check for cycles
    val colors = Array(n) { 0 }
    fun dfs(v: Int): Boolean {
        colors[v] = 1 // ENTERED
        var hasCycle = false
        for (u in filteredSuccessors.neighbours(v)) {
            when (colors[u]) {
                0 -> hasCycle = dfs(u) or hasCycle
                1 -> hasCycle = true // Found cycle
                // 2 (EXITED) - already fully explored, skip
            }
        }
        colors[v] = 2 // EXITED
        return hasCycle
    }

    val hasCycle = dfs(0)
    val allBlocksReachable = colors.all { it == 2 /* we visited all blocks in dfs */ }

    // CFG is reducible if it does not have cycles (in terms of graph theory) and all blocks are reachable from entry
    return !hasCycle && allBlocksReachable
}

// == LOOPS PRETTY-PRINTING ==

fun MethodLoopsInformation.toFormattedString(): String =
    MethodLoopsInformationPrinter(this).toFormattedString()

private class MethodLoopsInformationPrinter(val loopInfo: MethodLoopsInformation) {

    fun toFormattedString(): String {
        val sb = StringBuilder()
        if (loopInfo.loops.isEmpty()) {
            sb.appendLine("NO LOOPS")
        }
        for ((index, loop) in loopInfo.loops.withIndex()) {
            sb.appendLine("LOOP ${index + 1}")
            sb.appendLine("  HEADER: B${loop.header}")
            sb.appendLine("  BODY: ${loop.body.sorted().joinToString(", ") { "B$it" }}")
            sb.appendLine("  BACK EDGES:")
            sb.appendLine(loop.backEdges.toFormattedString().prependIndent("    "))
            sb.appendLine("  NORMAL EXITS:")
            loop.normalExits.let {
                if (it.isEmpty()) sb.appendLine("    NONE")
                else sb.appendLine(it.toFormattedString().prependIndent("    "))
            }
            sb.appendLine("  EXCEPTION EXIT HANDLERS:")
            loop.exceptionalExitHandlers.let {
                if (it.isEmpty()) sb.appendLine("    NONE")
                else sb.appendLine(it.sorted().joinToString(", ") { block -> "B$block" }.prependIndent("    "))
            }
        }
        return sb.toString()
    }
}