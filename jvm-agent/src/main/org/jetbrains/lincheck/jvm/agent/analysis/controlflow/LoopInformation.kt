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

import org.jetbrains.lincheck.util.collections.*
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
 *   For reducible loops, `headers == [header]`.
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
 * @property exclusiveExits The set of exceptional and normal exits that are only reachable from
 *   within the loop body. This is useful for calculating the reachability of exception handler opcodes
 *   from outside loops.
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
     val exclusiveExits: Set<BasicBlockIndex>
 ) {
     init {
         validate()
     }

     val isReducible: Boolean
         get() = headers.size == 1

    val isIrreducible: Boolean
        get() = !isReducible

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

         require(exclusiveExits.all { it in exceptionalExitHandlers || normalExits.any { e -> it == e.target } }) {
             "Exclusive exits must be either exceptional or normal"
         }
     }
 }

/**
 * Aggregates loop information for a single method.
 *
 * @property loops All loops detected in the method. Note that there is an invariant which holds: for every two loops
 *                 `a` and `b`: if `a` is an outer loop of `b` (meaning that `b.body` is a subset of `a.body`), then `a.id < b.id`.
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
 * @return set of back edges sorted by their headers (`target` of the edge).
 */
internal fun BasicBlockControlFlowGraph.computeBackEdges(): Set<Edge> {
    require(dominators != null) { "Dominator set must be computed before computing back edges" }
    val backEdges = mutableSetOf<Edge>()
    for (e in normalEdges) {
        val u = e.source
        val h = e.target
        val domU = dominators!!.getOrElse(u) { emptySet() }
        if (h in domU) {
            backEdges.add(e)
        }
    }
    return backEdges
        .sortedWith(compareBy({ it.target }, { it.source }, { it.label }))
        .toSet()
}

/**
 * Compute loops using dominators and back-edge detection.
 * Handles both reducible and irreducible CFGs.
 *
 * **Reducible loops** are detected via back edges: an edge u -> h where h dominates u.
 *   For each header h, the loop body is the union of natural loops of its back edges
 *   (computed by reverse DFS from each back-edge source to h).
 *   If there is more than one back edge to the same header,
 *   the body of the loop is the union of the nodes computed for each back edge.
 *   Since loops can nest, a header for one loop can be in the body of (but not the header of) another loop.
 *
 * **Irreducible loops** (present when the CFG is irreducible) are detected by computing
 *   strongly connected components (SCCs) in the residual graph obtained by removing
 *   dominator-identified back edges. SCCs that are already fully covered by a reducible
 *   loop body are skipped; the remaining cyclic SCCs become irreducible loops whose
 *   headers are the SCC nodes reachable from outside the SCC.
 *
 * The returned loops are sorted so that outer loops receive smaller ids than their inner loops.
 *
 * @param computeIrreducibleLoops whether to detect irreducible loops in addition to reducible ones.
 *   When `false`, only reducible (natural) loops are reported, even if the CFG is irreducible.
 * @return A [MethodLoopsInformation] containing all detected loops (both reducible and irreducible),
 *   each represented by a [LoopInformation] object.
 *   The result is empty if no loops were detected.
 */
internal fun BasicBlockControlFlowGraph.computeLoopsFromDominators(
    computeIrreducibleLoops: Boolean,
): MethodLoopsInformation {
    require(isReducible != null) { "CFG reducibility was not checked before computing the loops" }

    val dominators = dominators
    val backEdges = backEdges
    require(dominators != null) { "Dominator set must be computed before computing loops" }
    require(backEdges != null) { "Back edges must be computed before computing loops" }

    val n = basicBlocks.size
    require(dominators.size == n) { "Dominator set must be of length $n but got ${dominators.size} instead" }
    if (n == 0) return MethodLoopsInformation()

    // Identify back edges grouped by header h
    if (backEdges.isEmpty()) return MethodLoopsInformation()

    // For each header, compute the loop body as a union of natural loops for each back edge to that header.
    // Also calculate normal and exceptional exits
    val backEdgesByHeader = backEdges.groupBy({ it.target }, { it }).mapValues { it.value.toSet() }
    val loopBodiesWithHeaders = mutableListOf<Pair<Set<BasicBlockIndex>, Set<BasicBlockIndex>>>()
    val loops = mutableListOf<LoopInformation>()
    var nextLoopId = 0

    loopBodiesWithHeaders += computeReducibleLoopBodies(backEdgesByHeader).toMutableList()
    if (computeIrreducibleLoops && isReducible == false) {
        loopBodiesWithHeaders += computeIrreducibleLoopBodies(backEdges, loopBodiesWithHeaders)
    }

    // Sort loop bodies by containment and header values, so that for any two loops a and b we could say that
    // if b is an inner loop of a, then id(a) < id(b), so "outer" loops have smaller ids than "inner" loops
    loopBodiesWithHeaders.sortWith { a, b ->
        val aBody = a.second
        val bBody = b.second
        // the "outer" loops will come before their "inner" loops
        if (aBody.size >= bBody.size && aBody.containsAll(bBody)) -1
        else if (aBody.size < bBody.size && bBody.containsAll(aBody)) 1
        else a.first.min().compareTo(b.first.min())
    }

    for ((headers, body) in loopBodiesWithHeaders) {
        val header = headers.min()

        // Collect back edges for this loop.
        val loopBackEdges = if (headers.size == 1 && isReducible == true) {
            // Reducible loop case:
            // back edges are already grouped by header, so we can just take the set for the header.
            backEdgesByHeader[header]!!.toSet()
        } else {
            // Irreducible loop case:
            // collect edges targeting any of its headers from within the body.
            buildSet {
                for (e in normalEdges) {
                    if (e.source in body && e.target in headers) {
                        add(e)
                    }
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
        // All exits that are only reachable from the loop body
        val exclusiveExits = (normalExits.asSequence().map { it.target } + exceptionalExitHandlers.asSequence())
            .filterTo(mutableSetOf()) { isExclusiveExitFromLoopBody(it, body) }

        loops += LoopInformation(
            id = nextLoopId++,
            header = header,
            headers = headers,
            body = body,
            backEdges = loopBackEdges,
            normalExits = normalExits,
            exceptionalExitHandlers = exceptionalExitHandlers,
            exclusiveExits = exclusiveExits
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

private typealias BackEdgesByHeader = Map<BasicBlockIndex, Set<Edge>>
private typealias LoopBodiesWithHeaders = List<Pair<Set<BasicBlockIndex>, Set<BasicBlockIndex>>>

/**
 * Compute reducible (natural) loop bodies from back edges grouped by header.
 *
 * For each header h, the natural loop body is computed by performing a reverse DFS
 * from each back-edge source towards h over normal predecessor edges.
 * When multiple back edges share the same header, a union of their natural loops is taken.
 *
 * Each resulting loop has exactly one header.
 *
 * @param backEdgesByHeader back edges grouped by their header (target) block.
 * @return list of pairs (headers, body) for each reducible loop,
 *   where headers is a singleton set containing the loop header.
 */
private fun BasicBlockControlFlowGraph.computeReducibleLoopBodies(
    backEdgesByHeader: BackEdgesByHeader,
): LoopBodiesWithHeaders {
    val result = mutableListOf<Pair<Set<BasicBlockIndex>, Set<BasicBlockIndex>>>()
    for ((h, adjacentBackEdges) in backEdgesByHeader) {
        val body = mutableSetOf<BasicBlockIndex>()
        body.add(h)
        // Start from each back edge source; perform reverse DFS over normal predecessors until reaching h
        for (e in adjacentBackEdges) {
            val u = e.source
            val stack = ArrayDeque<BasicBlockIndex>()
            // Natural loop includes both u and h initially
            if (body.add(u)) stack.add(u)
            while (stack.isNotEmpty()) {
                val x = stack.removeLast()
                for (p in allPredecessors.neighbours(x)) {
                    if (body.add(p)) stack.add(p)
                }
            }
        }
        result.add(setOf(h) to body.toSet())
    }
    return result
}

/**
 * Compute irreducible loop bodies by finding strongly connected components (SCCs)
 * in the residual graph obtained by removing dominator-identified back edges.
 *
 * SCCs that are fully contained within an already-detected reducible loop body are skipped,
 * since they are already accounted for. For each remaining cyclic SCC, the headers are
 * determined as the SCC nodes reachable from outside the SCC
 * (i.e., having at least one predecessor outside the SCC).
 *
 * Irreducible loops typically have multiple headers.
 *
 * @param backEdges the set of all dominator-identified back edges in the CFG.
 * @param reducibleLoopBodies already computed reducible loop bodies, used to skip covered SCCs.
 * @return list of pairs (headers, body) for each irreducible loop.
 */
private fun BasicBlockControlFlowGraph.computeIrreducibleLoopBodies(
    backEdges: Set<Edge>,
    reducibleLoopBodies: LoopBodiesWithHeaders,
): LoopBodiesWithHeaders {
    val n = basicBlocks.size
    // Build a residual graph by removing dominator-identified back edges
    val residualSuccessors: (BasicBlockIndex) -> Iterable<BasicBlockIndex> = { v ->
        allSuccessors.neighbours(v).filter { u ->
            val edge = normalEdges.find { it.source == v && it.target == u }
            edge == null || edge !in backEdges
        }
    }
    val allNodes = (0 until n).toSet()
    val sccs = computeCyclicStronglyConnectedComponents(allNodes, residualSuccessors)
    val result = mutableListOf<Pair<Set<BasicBlockIndex>, Set<BasicBlockIndex>>>()
    for (scc in sccs) {
        // Skip SCCs that are fully contained in an already-found reducible loop body
        val isAlreadyCovered = reducibleLoopBodies.any { (_, body) -> body.containsAll(scc) }
        if (isAlreadyCovered) continue

        // Headers = SCC nodes reachable from outside the SCC
        val headers = scc.filterTo(mutableSetOf()) { node ->
            allPredecessors.neighbours(node).any { pred -> pred !in scc }
        }
        if (headers.isEmpty()) {
            // If no external entry, the entry block is a header
            if (0 in scc) headers.add(0)
            else headers.add(scc.min())
        }

        // The body is the full SCC
        val body = scc.toSet()

        result.add(headers to body)
    }
    return result
}

/**
 * Compute cyclic strongly connected components (SCCs) of a directed control-flow graph
 * using Tarjan's algorithm.
 *
 * Returns only cyclic SCCs: components of size > 1, or singletons with a self-loop.
 * Acyclic singleton nodes are filtered out, since only cyclic components can represent loops.
 *
 * Nodes are visited in sorted order to produce deterministic results.
 *
 * @param nodes the set of graph nodes to consider.
 * @param successors a function returning the successors of a given node;
 *   successors outside [nodes] are ignored.
 * @return a list of cyclic SCCs, each represented as a set of node indices.
 */
private fun computeCyclicStronglyConnectedComponents(
    nodes: Set<BasicBlockIndex>,
    successors: (BasicBlockIndex) -> Iterable<BasicBlockIndex>
): List<Set<BasicBlockIndex>> {
    var nextIndex = 0
    val index = mutableMapOf<BasicBlockIndex, Int>()
    val lowLink = mutableMapOf<BasicBlockIndex, Int>()
    val stack = ArrayDeque<BasicBlockIndex>()
    val onStack = mutableSetOf<BasicBlockIndex>()
    val result = mutableListOf<Set<BasicBlockIndex>>()

    fun strongConnect(v: BasicBlockIndex) {
        index[v] = nextIndex
        lowLink[v] = nextIndex
        nextIndex++

        stack.addLast(v)
        onStack.add(v)

        for (w in successors(v).filter { it in nodes }.sorted()) {
            if (w !in index) {
                strongConnect(w)
                lowLink[v] = minOf(lowLink.getValue(v), lowLink.getValue(w))
            } else if (w in onStack) {
                lowLink[v] = minOf(lowLink.getValue(v), index.getValue(w))
            }
        }

        if (lowLink.getValue(v) == index.getValue(v)) {
            val component = mutableSetOf<BasicBlockIndex>()
            while (true) {
                val w = stack.removeLast()
                onStack.remove(w)
                component.add(w)
                if (w == v) break
            }

            // Keep only cyclic SCCs:
            // - size > 1, or
            // - singleton with a self-loop
            val isCyclic =
                component.size > 1 ||
                        successors(v).any { it == v }

            if (isCyclic) {
                result += component
            }
        }
    }

    for (v in nodes.sorted()) {
        if (v !in index) {
            strongConnect(v)
        }
    }

    return result
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
internal fun BasicBlockControlFlowGraph.isReducible(): Boolean {
    val n = basicBlocks.size
    if (n == 0) return true
    val backEdges = backEdges
    require(backEdges != null) { "Back edges must be computed before checking reducibility" }
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

/**
 * Checks if the given exit block is exclusive to the loop body,
 * meaning that there is no reverse normal/exceptional edge in the CFG that leads to
 * a basic block outside the loop body.
 */
private fun BasicBlockControlFlowGraph.isExclusiveExitFromLoopBody(
    exitBlock: BasicBlockIndex,
    loopBody: Set<BasicBlockIndex>
): Boolean {
    val neighs = allPredecessors.neighbours(exitBlock)
    var exitIsReachableOnlyFromLoop = true
    for (pred in neighs) {
        if (pred !in loopBody) {
            exitIsReachableOnlyFromLoop = false
            break
        }
    }
    return exitIsReachableOnlyFromLoop
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
            sb.appendLine("  EXCLUSIVE EXITS:")
            loop.exclusiveExits.let {
                if (it.isEmpty()) sb.appendLine("    NONE")
                else sb.appendLine(it.sorted().joinToString(", ") { block -> "B$block" }.prependIndent("    "))
            }
        }
        return sb.toString()
    }
}