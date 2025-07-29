/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor

class MethodLabels internal constructor(
    labels: Map<Label, Int>
): Comparator<Label> {
    private class LabelInfo(val index: Int, var seen: Boolean)

    private val labels = labels.mapValues { (_, v) -> LabelInfo(v, false) }

    override fun compare(
        o1: Label,
        o2: Label
    ): Int {
        val idx1 = labels[o1]?.index ?: 0
        val idx2 = labels[o2]?.index ?: 0
        return idx1.compareTo(idx2)
    }

    fun visitLabel(label: Label) {
        labels[label]?.seen = true
    }

    fun reset() {
        labels.values.forEach { it.seen = false }
    }

    fun isLabelSeen(label: Label): Boolean = labels[label]?.seen ?: false

    companion object {
        val EMPTY = MethodLabels(emptyMap())
    }
}

internal class LabelCollectorMethodVisitor(
    private val labels: MutableMap<Label, Int>
): MethodVisitor(ASM_API, null) {
    override fun visitLabel(label: Label) {
        // Don't call super.visitLabel(): we know that it is no-op
        labels[label] = labels.size
    }
}
