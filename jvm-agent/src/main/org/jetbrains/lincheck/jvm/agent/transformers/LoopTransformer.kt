/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import org.jetbrains.lincheck.jvm.agent.MethodInformation
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.*
import org.jetbrains.lincheck.util.*
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter

/**
 * [LoopTransformer] tracks loops enter/exit points and new loop iterations starting points.
 */
internal class LoopTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : InstructionMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

    // Retrieve loop sites planned from the precomputed basic-block CFG.
    private val loopInfo = methodInfo.basicControlFlowGraph.loopInfo.ensureNotNull {
        "Loops information is not available for method $className.$methodName$descriptor"
    }

    // Map from a loop header entry instruction index to loopId
    private val headerEnterSites: Map<InstructionIndex, LoopId> = computeHeaderEnterSites(loopInfo)

    // Map from a normal exit instruction index to the set of exited loopIds
    private val normalExitSites: Map<InstructionIndex, Set<LoopId>> = computeNormalExitSites(loopInfo)

    // Map from an exception handler label block to the set of exited loopIds
    private val handlerEntrySites: Map<Label, Set<LoopId>> = computeHandlerEntrySites(loopInfo)

    // Used to defer handler-entry injection until the first real opcode after the label.
    private var pendingHandlerLoopIds: Set<LoopId>? = null

    override fun visitLabel(label: Label) {
        // If this label is a handler entry for some loop(s), mark as pending;
        // it will trigger injection insertion on the first subsequent real opcode in [beforeInsn].
        handlerEntrySites[label]?.let { ids ->
            pendingHandlerLoopIds = ids
        }
        super.visitLabel(label)
    }

    override fun beforeInsn(index: Int, opcode: Int) {
        // First real opcode after a handler label — if any pending,
        // this is where "afterLoopEnd" for exceptional exits would be injected.
        pendingHandlerLoopIds?.let {
            // TODO: inject afterLoopEnd for each id in [it] at this site
            pendingHandlerLoopIds = null
        }

        // Perform loop header enter check.
        headerEnterSites[index]?.let { loopId ->
            // TODO: inject beforeLoopStart for [loopId] at this site
            // TODO: inject beforeLoopIterationStart for [loopId] at this site
        }

        // Perform loop exit check.
        normalExitSites[index]?.let { loopIds ->
            // TODO: inject afterLoopIterationEnd for all [loopIds] at this site
        }
    }

    override fun afterInsn(index: Int, opcode: Int) {}

    private fun computeHeaderEnterSites(info: MethodLoopsInformation): Map<InstructionIndex, Int> {
        if (!info.hasLoops()) return emptyMap()
        val cfg = methodInfo.basicControlFlowGraph
        val result = mutableMapOf<InstructionIndex, Int>()
        for (loop in info.loops) {
            val idx = cfg.firstOpcodeIndexOf(loop.header) ?: continue
            // If multiple loops share the same header opcode index (rare),
            // prefer the inner loop by letting the later put override only if absent.
            result.putIfAbsent(idx, loop.id)
        }
        return result
    }

    private fun computeNormalExitSites(info: MethodLoopsInformation): Map<InstructionIndex, Set<Int>> {
        if (!info.hasLoops()) return emptyMap()
        val cfg = methodInfo.basicControlFlowGraph
        val result = mutableMapOf<InstructionIndex, MutableSet<Int>>()
        for (loop in info.loops) {
            for (e in loop.normalExits) {
                // By cfg/loop invariants every normal exit is decided by the last real opcode of the source block
                // (either an IF* fall-through or an explicit jump/switch).
                val insnIndex: InstructionIndex = cfg.lastOpcodeIndexOf(e.source) ?: continue
                result.updateInplace(insnIndex, default = mutableSetOf()) { add(loop.id) }
            }
        }
        return result.mapValues { it.value.toSet() }
    }

    private fun computeHandlerEntrySites(info: MethodLoopsInformation): Map<Label, Set<Int>> {
        if (!info.hasLoops()) return emptyMap()
        val cfg = methodInfo.basicControlFlowGraph
        val result = mutableMapOf<Label, MutableSet<Int>>()
        for (loop in info.loops) {
            for (handlerBlock in loop.exceptionalExitHandlers) {
                val label = cfg.firstLabelOf(handlerBlock) ?: continue
                result.updateInplace(label, default = mutableSetOf()) { add(loop.id) }
            }
        }
        return result.mapValues { it.value.toSet() }
    }
}
