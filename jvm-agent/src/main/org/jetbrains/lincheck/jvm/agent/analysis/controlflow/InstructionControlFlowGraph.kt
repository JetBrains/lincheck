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

import org.objectweb.asm.tree.InsnList

/**
 * A type alias representing the index of an instruction within a method.
 */
typealias InstructionIndex = Int

/**
 * Represents an instruction-level control flow graph for a method.
 *
 * Each node in the graph represents an instruction of the method, stored as an instruction index.
 * Each edge in the graph represents a control flow transition between two instructions.
 */
class InstructionControlFlowGraph(val instructions: InsnList) : ControlFlowGraph() {
    constructor() : this(InsnList())
}