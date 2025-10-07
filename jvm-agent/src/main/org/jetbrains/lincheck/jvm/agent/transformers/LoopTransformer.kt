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
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.InstructionIndex
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
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

    // TODO: use LoopId typealias instead of Int

    // Map from a loop header entry instruction index to loopId
    private val headerEnterSites: Map<InstructionIndex, Int> = mutableMapOf()

    // Map from a normal exit instruction index to the set of exited loopIds
    private val normalExitSites: Map<InstructionIndex, Set<Int>> = mutableMapOf()

    // Map from an exception handler label block to the set of exited loopIds
    private val handlerEntrySites: Map<Label, Set<Int>> = mutableMapOf()

    // Used to defer handler-entry injection until the first real opcode after the label.
    private var pendingHandlerLoopIds: MutableSet<Int>? = null

    override fun visitLabel(label: Label) {
        // If this label is a handler entry for some loop(s), mark as pending;
        // it will trigger injection insertion on the first subsequent real opcode in [beforeInsn].
        handlerEntrySites[label]?.let { ids ->
            pendingHandlerLoopIds = ids.toMutableSet()
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
}
