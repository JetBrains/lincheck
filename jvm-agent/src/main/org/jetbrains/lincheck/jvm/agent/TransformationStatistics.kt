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

import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.util.concurrent.ConcurrentLinkedQueue

data class ClassTransformationStatistics(
    val className: String,
    val classBytesSizeBefore: Int,
    val classBytesSizeAfter: Int,
    val transformationTimeNanos: Long,
)

data class MethodTransformationStatistics(
    val className: String,
    val methodName: String,
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
    private val classStats = ConcurrentLinkedQueue<ClassTransformationStatistics>()
    private val methodStats = ConcurrentLinkedQueue<MethodTransformationStatistics>()

    fun saveStatistics(
        originalClassNode: ClassNode,
        originalClassBytes: ByteArray,
        transformedClassBytes: ByteArray,
        transformationTimeNanos: Long,
    ) {
        classStats.add(
            ClassTransformationStatistics(
                className = originalClassNode.name,
                classBytesSizeBefore = originalClassBytes.size,
                classBytesSizeAfter = transformedClassBytes.size,
                transformationTimeNanos = transformationTimeNanos,
            )
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
        val before = countInstructions(originalMethodNode)
        val after = -1 // TODO

        methodStats.add(
            MethodTransformationStatistics(
                className = className,
                methodName = methodName,
                methodInstructionsCountBefore = before,
                methodInstructionsCountAfter = after,
            )
        )
    }

    fun getTransformationStatistics(): TransformationStatistics {
        val classes = classStats.toList()
        val methods = methodStats.toList()

        val totalClasses = classes.size
        val totalMethods = methods.size

        val avgClassBefore = if (totalClasses == 0) 0.0 else classes.map { it.classBytesSizeBefore.toLong() }.average()
        val avgClassAfter = if (totalClasses == 0) 0.0 else classes.map { it.classBytesSizeAfter.toLong() }.average()

        val avgMethodBefore = if (totalMethods == 0) 0.0 else methods.map { it.methodInstructionsCountBefore.toLong() }.average()
        val avgMethodAfter = if (totalMethods == 0) 0.0 else methods.map { it.methodInstructionsCountAfter.toLong() }.average()

        val totalTime = classes.sumOf { it.transformationTimeNanos }
        val avgTime = if (totalClasses == 0) 0.0 else classes.map { it.transformationTimeNanos }.average()

        return TransformationStatistics(
            totalTransformedClassesCount = totalClasses,
            totalTransformedMethodsCount = totalMethods,
            averageClassBytesSizeBefore = avgClassBefore,
            averageClassBytesSizeAfter = avgClassAfter,
            averageMethodInstructionsCountBefore = avgMethodBefore,
            averageMethodInstructionsCountAfter = avgMethodAfter,
            totalTransformationTimeNanos = totalTime,
            averageTransformationTimeNanos = avgTime,
        )
    }

    // Count only executable instructions, skip labels/line numbers/frames etc.
    private fun countInstructions(m: MethodNode): Int =
        m.instructions.count { insn -> insn.opcode >= 0 }
}