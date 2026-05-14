/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor

/**
 * This comparator compares labels in order of their appearance in method instructions.
 *
 * Unknown labels are arbitrarily considered less than any known ones and equals to other unknown ones.
 */
class MethodLabels internal constructor(
    labels: Map<Label, Int>,
    catchTargets: Set<Label>,
    jumpTargets: Set<Label>,
): Comparator<Label> {
    // Index is a serial number of the labels (starting from 0) in order method instructions visited.
    private class LabelInfo(
        val index: Int,
        val isCatchTarget: Boolean,
        val isJumpTarget: Boolean,
        var seen: Boolean,
    )

    private val labels = labels.mapValues { (k, v) ->
        LabelInfo(
            index = v,
            isCatchTarget = catchTargets.contains(k),
            isJumpTarget = jumpTargets.contains(k),
            seen = false,
        )
    }

    override fun compare(
        o1: Label,
        o2: Label
    ): Int {
        val idx1 = labels[o1]?.index ?: -1
        val idx2 = labels[o2]?.index ?: -1
        return idx1.compareTo(idx2)
    }

    fun visitLabel(label: Label) {
        labels[label]?.seen = true
    }

    fun reset() {
        labels.values.forEach { it.seen = false }
    }

    fun isLabelSeen(label: Label): Boolean = labels[label]?.seen ?: false

    fun isCatchTarget(label: Label): Boolean = labels[label]?.isCatchTarget ?: false

    /**
     * Whether [label] is referenced as the target of a jump / switch instruction.
     *
     * Labels referenced only as `LINENUMBER` / `LOCALVARIABLE` anchors are *not* jump
     * targets — they are pure metadata and do not partition the instruction stream.
     */
    fun isJumpTarget(label: Label): Boolean = labels[label]?.isJumpTarget ?: false

    /**
     * Whether [label] is the target of an explicit non-fall-through control-flow edge
     * — a jump, switch case, or exception handler.
     *
     * **Not a full basic-block-entry predicate.**
     * Labels reached only by fall-through after a conditional branch / `tableswitch` / `lookupswitch`
     * also start new basic blocks in strict CFG terms but are *not* included here,
     * since `MethodLabels` collects only labels that are *referenced* as targets.
     */
    fun isJumpOrCatchTarget(label: Label): Boolean =
        labels[label]?.let { it.isJumpTarget || it.isCatchTarget } ?: false

    companion object {
        val EMPTY = MethodLabels(emptyMap(), emptySet(), emptySet())
    }
}

internal class LabelCollectorMethodVisitor(
    private val labels: MutableMap<Label, Int>,
    private val jumpTargets: MutableSet<Label>,
): MethodVisitor(ASM_API, null) {
    override fun visitLabel(label: Label) {
        // Don't call super.visitLabel(): we know that it is no-op
        labels[label] = labels.size
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
        jumpTargets += label
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
        jumpTargets += dflt
        for (l in labels) jumpTargets += l
    }

    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray?, labels: Array<out Label>?) {
        jumpTargets += dflt
        labels?.forEach { jumpTargets += it }
    }
}
