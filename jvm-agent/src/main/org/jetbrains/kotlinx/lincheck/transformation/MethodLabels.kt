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

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ACC_NATIVE
import kotlin.collections.mutableMapOf
import kotlin.jvm.Throws

class MethodLabels internal constructor(
    private val labels: Map<Label, Int>
): Comparator<Label> {
    override fun compare(
        o1: Label,
        o2: Label
    ): Int {
        val idx1 = labels[o1] ?: 0
        val idx2 = labels[o2] ?: 0
        return idx1.compareTo(idx2)
    }

    companion object {
        val EMPTY = MethodLabels(emptyMap())
    }
}

class MethodLabelsClassVisitor(visitor: ClassVisitor): ClassVisitor(ASM_API, visitor) {
    private val labels = mutableMapOf<String, MutableMap<Label, Int>>()

    override fun visitMethod(
        access: Int,
        name: String,
        desc: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, desc, signature, exceptions)
        if (access and ACC_NATIVE != 0) {
            return mv
        }
        return LabelCollectorMethodVisitor(mv, labels.computeIfAbsent(name + desc) { mutableMapOf() })
    }

    fun getLabels(): Map<String, MethodLabels> {
        return labels.mapValues { MethodLabels(it.value) }
    }
}

private class LabelCollectorMethodVisitor(
    visitor: MethodVisitor,
    private val labels: MutableMap<Label, Int>
): MethodVisitor(ASM_API, visitor) {
    private var index = 0
    override fun visitLabel(label: Label) {
        super.visitLabel(label)
        labels[label] = index++
    }
}
