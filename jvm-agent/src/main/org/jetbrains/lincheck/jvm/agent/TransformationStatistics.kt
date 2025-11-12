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

import org.jetbrains.lincheck.util.*
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.util.concurrent.ConcurrentHashMap

data class ClassTransformationStatistics(
    val className: String,
    val classBytesSizeBefore: Int,
    val classBytesSizeAfter: Int,
    val transformationTimeNanos: Long,
)

data class MethodTransformationStatistics(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val methodInstructionsCountBefore: Int,
    val methodInstructionsCountAfter: Int,
)

data class TransformationStatistics(
    val totalTransformedClassesCount: Int,
    val totalTransformedMethodsCount: Int,

    val averageClassBytesSizeBefore: Double,
    val averageClassBytesSizeAfter: Double,

    val averageMethodInstructionsCountBefore: Double,
    val averageMethodInstructionsCountAfter: Double,

    val totalTransformationTimeNanos: Long,
    val averageTransformationTimeNanos: Double,
)

/**
 * Tracks and aggregates class and method transformation statistics.
 * Thread-safe and allocation-light relative to the accumulated data sizes.
 */
class TransformationStatisticsTracker {
    private val classStats = ConcurrentHashMap<String, ClassStatisticsTracker>()

    fun createStatisticsCollectingVisitor(
        className: String,
        methodName: String,
        methodDescriptor: String,
        methodVisitor: MethodVisitor,
    ): StatisticsCollectingMethodVisitor {
        val classStats = classStats.computeIfAbsent(className) { ClassStatisticsTracker() }
        return StatisticsCollectingMethodVisitorImpl(
            className = className,
            methodName = methodName,
            methodDescriptor = methodDescriptor,
            classStatsTracker = classStats,
            methodVisitor = methodVisitor,
        )
    }

    fun saveStatistics(
        originalClassNode: ClassNode,
        originalClassBytes: ByteArray,
        transformedClassBytes: ByteArray,
        transformationTimeNanos: Long,
    ) {
        classStats.updateInplace(originalClassNode.name, default = ClassStatisticsTracker()) {
            saveStatistics(originalClassNode, originalClassBytes, transformedClassBytes, transformationTimeNanos)
        }
    }

    fun computeStatistics(): TransformationStatistics {
        val classes = classStats.values
            .mapNotNull { it.classStats }
        val methods = classStats.values
            .flatMap { if (it.isRecorded) it.methodStats else emptyList() }

        return TransformationStatistics(
            totalTransformedClassesCount =
                classes.size,
            totalTransformedMethodsCount =
                methods.size,
            averageClassBytesSizeBefore =
                classes.map { it.classBytesSizeBefore.toLong() }.averageOrNull() ?: 0.0,
            averageClassBytesSizeAfter =
                classes.map { it.classBytesSizeAfter.toLong() }.averageOrNull() ?: 0.0,
            averageMethodInstructionsCountBefore =
                methods.map { it.methodInstructionsCountBefore.toLong() }.averageOrNull() ?: 0.0,
            averageMethodInstructionsCountAfter =
                methods.map { it.methodInstructionsCountAfter.toLong() }.averageOrNull() ?: 0.0,
            totalTransformationTimeNanos =
                classes.sumOf { it.transformationTimeNanos },
            averageTransformationTimeNanos =
                classes.map { it.transformationTimeNanos }.averageOrNull() ?: 0.0,
        )
    }
}

private class ClassStatisticsTracker {
    var classStats: ClassTransformationStatistics? = null
        private set

    private val _methodStats: MutableList<MethodTransformationStatistics> = mutableListOf()
    val methodStats: List<MethodTransformationStatistics> get() = _methodStats

    // Map: (methodName + methodDescriptor) -> instructions count AFTER transformation
    private val methodInstructionsCountAfter = ConcurrentHashMap<String, Int>()

    val isRecorded: Boolean get() = (classStats != null)

    fun saveStatistics(
        originalClassNode: ClassNode,
        originalClassBytes: ByteArray,
        transformedClassBytes: ByteArray,
        transformationTimeNanos: Long,
    ) {
        check(classStats == null) { "Class statistics already recorded" }

        classStats = ClassTransformationStatistics(
            className = originalClassNode.name,
            classBytesSizeBefore = originalClassBytes.size,
            classBytesSizeAfter = transformedClassBytes.size,
            transformationTimeNanos = transformationTimeNanos,
        )

        originalClassNode.methods.forEach { originalMethodNode ->
            saveMethodStatistics(
                className = originalClassNode.name,
                methodName = originalMethodNode.name,
                methodDescriptor = originalMethodNode.desc,
                originalMethodNode = originalMethodNode
            )
        }
    }

    private fun saveMethodStatistics(
        className: String,
        methodName: String,
        methodDescriptor: String,
        originalMethodNode: MethodNode,
    ) {
        val before = originalMethodNode.countInstructions()
        val after = methodInstructionsCountAfter[methodName + methodDescriptor] ?: before

        _methodStats.add(
            MethodTransformationStatistics(
                className = className,
                methodName = methodName,
                methodDescriptor = methodDescriptor,
                methodInstructionsCountBefore = before,
                methodInstructionsCountAfter = after,
            )
        )
    }

    fun registerMethodVisitorStatistics(visitor: StatisticsCollectingMethodVisitor) {
        val key = visitor.methodName + visitor.methodDescriptor
        methodInstructionsCountAfter[key] = visitor.instructionsCount
    }
}

abstract class StatisticsCollectingMethodVisitor(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    methodVisitor: MethodVisitor
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

    var instructionsCount: Int = 0
        private set

    protected abstract fun registerMethodStatistics()

    override fun visitInsn(opcode: Int) {
        super.visitInsn(opcode)
        instructionsCount++
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        super.visitIntInsn(opcode, operand)
        instructionsCount++
    }

    override fun visitVarInsn(opcode: Int, `var`: Int) {
        super.visitVarInsn(opcode, `var`)
        instructionsCount++
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        super.visitTypeInsn(opcode, type)
        instructionsCount++
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        super.visitFieldInsn(opcode, owner, name, descriptor)
        instructionsCount++
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        instructionsCount++
    }

    override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any) {
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        instructionsCount++
    }

    override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label) {
        super.visitJumpInsn(opcode, label)
        instructionsCount++
    }

    override fun visitLdcInsn(value: Any) {
        super.visitLdcInsn(value)
        instructionsCount++
    }

    override fun visitIincInsn(`var`: Int, increment: Int) {
        super.visitIincInsn(`var`, increment)
        instructionsCount++
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: org.objectweb.asm.Label, vararg labels: org.objectweb.asm.Label) {
        super.visitTableSwitchInsn(min, max, dflt, *labels)
        instructionsCount++
    }

    override fun visitLookupSwitchInsn(dflt: org.objectweb.asm.Label, keys: IntArray, labels: Array<org.objectweb.asm.Label>) {
        super.visitLookupSwitchInsn(dflt, keys, labels)
        instructionsCount++
    }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        super.visitMultiANewArrayInsn(descriptor, numDimensions)
        instructionsCount++
    }

    override fun visitEnd() {
        super.visitEnd()
        registerMethodStatistics()
    }
}

private class StatisticsCollectingMethodVisitorImpl(
    className: String,
    methodName: String,
    methodDescriptor: String,
    private val classStatsTracker: ClassStatisticsTracker,
    methodVisitor: MethodVisitor,
) : StatisticsCollectingMethodVisitor(className, methodName, methodDescriptor, methodVisitor) {

    override fun registerMethodStatistics() {
        classStatsTracker.registerMethodVisitorStatistics(this)
    }
}

// Count only executable instructions, skip labels/line numbers/frames etc.
private fun MethodNode.countInstructions(): Int =
    instructions.count { insn -> insn.opcode >= 0 }