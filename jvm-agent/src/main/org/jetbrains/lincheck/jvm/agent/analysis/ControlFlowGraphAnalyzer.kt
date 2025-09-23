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
import org.objectweb.asm.tree.analysis.*

/**
 * Analyzer which builds an instruction-level control-flow graph (CFG) using ASM [Analyzer] class.
 *
 * @see [InstructionControlFlowGraph]
 */
class ControlFlowGraphAnalyzer : Analyzer<BasicValue> {

    val graph = InstructionControlFlowGraph()

    constructor() : super(BasicInterpreter()) // use the default interpreter as we do not really care about values

    override fun newControlFlowEdge(src: Int, dst: Int) {
        graph.addEdge(src, dst)
        // no need to call `super` since it is no-op in ASM's Analyzer.
    }

    override fun newControlFlowExceptionEdge(src: Int, dst: Int): Boolean {
        graph.addExceptionEdge(src, dst)
        // no need to call `super` since it just returns `true` in ASM's Analyzer.
        return true
    }
}
