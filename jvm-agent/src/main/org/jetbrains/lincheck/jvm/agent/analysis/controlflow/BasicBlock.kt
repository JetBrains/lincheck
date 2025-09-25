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

/**
 * Class representing a basic block within a control-flow graph.
 *
 * A basic block is a contiguous, non-branching sequence of instructions
 * with a single entry point and a single exit point (branch).
 *
 * @property index Unique index of the basic block within its control-flow graph.
 * @param instructions List of instruction indices contained in this block.
 */
data class BasicBlock(
    val index: Int,
    val instructions: List<InstructionIndex>,
)