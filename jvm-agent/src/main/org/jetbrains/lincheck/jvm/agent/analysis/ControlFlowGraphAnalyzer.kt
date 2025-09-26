/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.analysis

import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.*
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.tree.analysis.*

/**
 * Analyzer which builds an instruction-level control-flow graph (CFG) using ASM [Analyzer] class.
 *
 * @see [InstructionControlFlowGraph]
 */
class ControlFlowGraphAnalyzer : Analyzer<BasicValue> {

    constructor() :
        // use the default interpreter as we do not really care about values
        super(BasicInterpreter())

    var graph = InstructionControlFlowGraph()
       private set

    override fun init(owner: String, method: MethodNode) {
        super.init(owner, method)
        graph = InstructionControlFlowGraph(method.instructions)
    }

    override fun newControlFlowEdge(src: Int, dst: Int) {
        super.newControlFlowEdge(src, dst)
        val insn = graph.instructions.get(src)
        val label = if (dst == src + 1) EdgeLabel.FallThrough else EdgeLabel.Jump(insn)
        graph.addEdge(src, dst, label)
    }

    override fun newControlFlowExceptionEdge(src: Int, tryCatchBlock: TryCatchBlockNode): Boolean {
        val label = EdgeLabel.Exception(tryCatchBlock)
        val dst = graph.instructions.indexOf(tryCatchBlock.handler)
        graph.addEdge(src, dst, label)
        return super.newControlFlowExceptionEdge(src, dst)
    }
}

fun ControlFlowGraphAnalyzer.buildControlFlowGraph(owner: String, method: MethodNode): BasicBlockControlFlowGraph {
    analyze(owner, method)
    return graph.toBasicBlockGraph()
}

fun buildControlFlowGraph(owner: String, method: MethodNode): BasicBlockControlFlowGraph {
    return ControlFlowGraphAnalyzer().buildControlFlowGraph(owner, method)
}