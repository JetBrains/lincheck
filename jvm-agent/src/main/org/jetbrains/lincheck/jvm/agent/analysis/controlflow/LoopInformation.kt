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

fun MethodLoopsInformation.prettyPrint(): String =
    MethodLoopsInformationPrinter(this).prettyPrint()

private class MethodLoopsInformationPrinter(val loopInfo: MethodLoopsInformation) {

    fun prettyPrint(): String {
        val sb = StringBuilder()

        sb.appendLine("LOOPS")
        if (loopInfo.loops.isEmpty()) {
            sb.appendLine("  NONE")
        }
        for ((index, loop) in loopInfo.loops.withIndex()) {
            sb.appendLine("LOOP ${index + 1}")
            sb.appendLine("  HEADER: B${loop.header}")
            sb.appendLine("  BODY: ${loop.body.sorted().joinToString(", ") { "B$it" }}")
            sb.appendLine("  BACK EDGES:")
            sb.appendLine(loop.backEdges.toFormattedString().prependIndent("    "))
            sb.appendLine("  NORMAL EXITS:")
            loop.normalExits.let {
                if (it.isEmpty()) sb.appendLine().appendLine("    NONE")
                else sb.appendLine(it.toFormattedString().prependIndent("    "))
            }
            sb.append("  EXCEPTION EXIT HANDLERS: ")
            loop.exceptionalExitHandlers.let {
                if (it.isEmpty()) sb.appendLine("NONE")
                else sb.appendLine(it.sorted().joinToString(", ") { block -> "B$block" })
            }
        }
        sb.appendLine()

        sb.appendLine("LOOPS BY BLOCK")
        if (loopInfo.loopsByBlock.isEmpty()) {
            sb.appendLine("  NONE")
        }
        for ((block, loops) in loopInfo.loopsByBlock.toSortedMap()) {
            sb.appendLine("  B$block: ${loops.sorted().joinToString(", ") { "LOOP ${it + 1}" }}")
        }

        return sb.toString()
    }
}