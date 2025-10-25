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
import org.objectweb.asm.tree.InsnList
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
        val opcode = insn.opcode
        val isUnconditionalJump = (
            opcode == GOTO          ||
            opcode == JSR           ||
            opcode == TABLESWITCH   ||
            opcode == LOOKUPSWITCH
        )
        val label = when {
            dst == src + 1 && !isUnconditionalJump -> EdgeLabel.FallThrough
            else -> EdgeLabel.Jump(insn)
        }
        graph.addEdge(src, dst, label)
    }

    override fun newControlFlowExceptionEdge(src: Int, tryCatchBlock: TryCatchBlockNode): Boolean {
        val label = EdgeLabel.Exception(tryCatchBlock)
        val dst = graph.instructions.indexOf(tryCatchBlock.handler)
        val start = graph.instructions.indexOf(tryCatchBlock.start)
        val end = graph.instructions.indexOf(tryCatchBlock.end)
        /*
        In some cases compiler can generate "TRYCATCHBLOCK start end handler type" instruction where
        handler label in located between start and end labels.
        This happens for example when compiler generate byte-code for try-finally blocks:
            try { /* empty */ }
            finally { /* empty */ }
        Such code will produce the following bytecode:
            TRYCATCHBLOCK L0 L1 L2 null
            TRYCATCHBLOCK L2 L3 L2 null
           L0
            NOP
           L1
            GOTO L4
           L2
           FRAME SAME1 java/lang/Throwable
            ASTORE 1
           L3
            ALOAD 1
            ATHROW
           L4
           FRAME SAME
            RETURN
        Where auto-generate instruction of popping Throwable from the operand stack is
        wrapped into try-catch with self-intersecting handler: L2 is the handler for
        code between L2 and L3 (ASTORE 1 instruction).

        It might seem that there is an infinite try-catch loop, but exception at 'ASTORE 1'
        is never thrown during runtime and 'TRYCATCHBLOCK L2 L3 L2 null' is only required for the
        bytecode verificator. We omit such try-catch handlers and do not add corresponding edges to the CFG.
        */
        if (dst in start..<end) return false
        // When exception edge is between exception handler label and itself,
        // there no point in adding such to the CFG.
        if (dst == src) return false
        graph.addEdge(src, dst, label)
        return true
    }
}

fun emptyControlFlowGraph(): BasicBlockControlFlowGraph =
    BasicBlockControlFlowGraph(InsnList(), emptyList())

fun ControlFlowGraphAnalyzer.buildControlFlowGraph(owner: String, method: MethodNode): BasicBlockControlFlowGraph {
    analyze(owner, method)
    return graph.toBasicBlockGraph()
}

fun buildControlFlowGraph(owner: String, method: MethodNode): BasicBlockControlFlowGraph {
    return ControlFlowGraphAnalyzer().buildControlFlowGraph(owner, method)
}