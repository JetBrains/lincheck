/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.strategy.managed

import org.jetbrains.lincheck.strategy.nativecalls.DeterministicMethodDescriptor
import org.jetbrains.lincheck.lincheck_trace.MethodCallTracePoint
import org.jetbrains.lincheck.datastructures.ManagedStrategyGuarantee
import org.jetbrains.lincheck.descriptors.*
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.util.*

/**
 * A thread's method frames, including a root frame for the test receiver.
 */
internal class ShadowStack {
    private val frames = arrayListOf<ShadowStackFrame>()

    val currentFrame: ShadowStackFrame
        get() = frames.last()

    val analysisSection: AnalysisSectionType
        get() = frames.lastOrNull()?.analysisSection ?: AnalysisSectionType.NORMAL

    val callerAnalysisSection: AnalysisSectionType?
        get() = frames.getOrNull(frames.lastIndex - 1)?.analysisSection

    val firstTracePoint: MethodCallTracePoint?
        get() = frames.firstNotNullOfOrNull { it.tracePoint }

    val lastTracePoint: MethodCallTracePoint?
        get() = frames.lastOrNull { it.tracePoint != null }?.tracePoint

    fun push(instance: Any?, tracePoint: MethodCallTracePoint?, analysisSection: AnalysisSectionType) {
        val currentSection = this.analysisSection
        val effectiveSection = if (currentSection.isCallStackPropagating() && analysisSection < currentSection) {
            currentSection
        } else {
            analysisSection
        }
        frames.add(ShadowStackFrame(instance, tracePoint, effectiveSection))
    }

    fun pop(): ShadowStackFrame {
        check(frames.size > 1) { "Shadow stack cannot be empty" }
        return frames.removeLast()
    }

    // Activate the callee's runtime section only after entry bookkeeping succeeds.
    fun enterAnalysisSection() {
        val section = analysisSection
        if (section == AnalysisSectionType.IGNORED ||
            // TODO: atomic should have different semantics compared to ignored
            section == AnalysisSectionType.ATOMIC
        ) {
            enterIgnoredSection()
        }
    }

    fun leaveAnalysisSection(section: AnalysisSectionType) {
        if (section == AnalysisSectionType.IGNORED ||
            // TODO: atomic should have different semantics compared to ignored
            section == AnalysisSectionType.ATOMIC
        ) {
            leaveIgnoredSection()
        }
    }

    fun inSilentSection(): Boolean = analysisSection.isSilent()
}

/**
 * A method's receiver, optional trace point, and effective analysis section.
 */
internal class ShadowStackFrame(
    val instance: Any?,
    val tracePoint: MethodCallTracePoint?,
    val analysisSection: AnalysisSectionType,
) {
    val instanceClassName = instance?.javaClass?.name

    fun isCurrentStackFrameReceiver(obj: Any): Boolean = obj === instance

    fun findCurrentReceiverFieldReferringTo(context: TraceContext, obj: Any): FieldAccessLocation? =
        instance?.findInstanceFieldReferringTo(obj)?.toAccessLocation(context)
}

internal fun AnalysisProfile.methodAnalysisSectionType(
    userDefinedGuarantees: List<ManagedStrategyGuarantee>?,
    owner: Any?,
    className: String,
    methodName: String,
    atomicMethodDescriptor: AtomicMethodDescriptor?,
    deterministicMethodDescriptor: DeterministicMethodDescriptor<*, *>?,
): AnalysisSectionType {
    // Ignore static MethodHandle calls.
    if (isIgnoredMethodHandleMethod(className, methodName)) {
        return AnalysisSectionType.IGNORED
    }
    // Ignore `toString()` on primitive and immutable types.
    if (owner.isImmutable && methodName == "toString") {
        return AnalysisSectionType.IGNORED
    }
    // Ignore methods called on standard I/O streams.
    // The owner is compared by reference: its `equals` is code of the analyzed program.
    if (owner === System.`in` || owner === System.out || owner === System.err) {
        return AnalysisSectionType.IGNORED
    }
    // TODO: decide if we need to introduce special `DETERMINISTIC` guarantee?
    if (deterministicMethodDescriptor != null) {
        // We use `ATOMIC` here to collect and print these calls.
        return AnalysisSectionType.ATOMIC
    }
    // Should this call be atomic?
    if (atomicMethodDescriptor != null) {
        return AnalysisSectionType.ATOMIC
    }
    // Check user-defined guarantees.
    val ownerName = owner?.javaClass?.canonicalName ?: className
    val section = getAnalysisSectionFor(ownerName, methodName)
    userDefinedGuarantees?.forEach { guarantee ->
        if (guarantee.classPredicate(ownerName) && guarantee.methodPredicate(methodName)) {
            return guarantee.type
        }
    }
    return section
}
