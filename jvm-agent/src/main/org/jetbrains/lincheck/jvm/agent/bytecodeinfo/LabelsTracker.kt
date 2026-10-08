package org.jetbrains.lincheck.jvm.agent.bytecodeinfo

import org.jetbrains.lincheck.jvm.agent.ASM_API
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor

/**
 * [LabelsTracker] tracks active regions of variables and status of the labels in the method.
 */
internal class LabelsTracker(
    visitor: MethodVisitor,
    private val metaInfo: MethodInformation
) : MethodVisitor(ASM_API, visitor) {
    override fun visitLabel(label: Label)  {
        metaInfo.locals.visitLabel(label)
        metaInfo.labels.visitLabel(label)
        super.visitLabel(label)
    }
}