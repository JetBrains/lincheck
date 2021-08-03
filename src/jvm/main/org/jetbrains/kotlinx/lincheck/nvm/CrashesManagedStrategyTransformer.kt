/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.nvm

import org.jetbrains.kotlinx.lincheck.annotations.CrashFree
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import kotlin.reflect.jvm.javaMethod

internal class CrashesManagedStrategyTransformer(
    cv: ClassVisitor?,
    tracePointConstructors: MutableList<TracePointConstructor>,
    guarantees: List<ManagedStrategyGuarantee>,
    eliminateLocalObjects: Boolean,
    collectStateRepresentation: Boolean,
    constructTraceRepresentation: Boolean,
    codeLocationIdProvider: CodeLocationIdProvider,
    private val crashesEnabledVisitor: CrashEnabledVisitor,
) : ManagedStrategyTransformer(
    cv, tracePointConstructors, guarantees, eliminateLocalObjects,
    collectStateRepresentation, constructTraceRepresentation, codeLocationIdProvider
) {
    override fun createTransformerBeforeSharedVariableVisitor(
        mv: MethodVisitor?,
        methodName: String,
        access: Int,
        desc: String
    ): MethodVisitor? = if (crashesEnabledVisitor.shouldTransform) {
        CrashManagedTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
    } else mv

    private inner class CrashManagedTransformer(methodName: String, mv: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, mv) {
        private var shouldTransform = methodName != "<clinit>" && (mv.access and Opcodes.ACC_BRIDGE) == 0
        private var superConstructorCalled = methodName != "<init>"

        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
            if (descriptor == CRASH_FREE_TYPE) {
                shouldTransform = false
            }
            return adapter.visitAnnotation(descriptor, visible)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean
        ) {
            if (!superConstructorCalled && opcode == Opcodes.INVOKESPECIAL) {
                superConstructorCalled = true
            }
            if (owner !== null && owner.startsWith("org/jetbrains/kotlinx/lincheck/nvm/api/")) {
                // Here the order of points is crucial - switch point must be before crash point.
                // The use case is the following: thread switches on the switch point,
                // then another thread initiates a system crash, force switches to the first thread
                // which crashes immediately.
                invokeBeforeNVMOperation()
                if (name !== null && (name.toLowerCase().contains("flush") || name.contains("setToNVM"))) invokeBeforeCrashPoint()
            }
            adapter.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        override fun visitInsn(opcode: Int) {
            if (opcode in returnInstructions) invokeBeforeCrashPoint()
            adapter.visitInsn(opcode)
        }

        private fun invokeBeforeCrashPoint() {
            if (!shouldTransform || !superConstructorCalled) return
            loadStrategy()
            loadCurrentThreadNumber()
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_CRASH_METHOD)
        }

        private fun invokeBeforeNVMOperation() {
            loadStrategy()
            loadCurrentThreadNumber()
            val tracePointLocal = newTracePointLocal()
            loadNewCodeLocationAndTracePoint(
                tracePointLocal,
                METHOD_TRACE_POINT_TYPE
            ) { iThread, actorId, callStackTrace, ste ->
                MethodCallTracePoint(iThread, actorId, callStackTrace, methodName, ste)
            }
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_NVM_OPERATION_METHOD)
        }
    }
}

private val CRASH_FREE_TYPE = Type.getDescriptor(CrashFree::class.java)
private val BEFORE_CRASH_METHOD = Method.getMethod(ManagedStrategy::beforeCrashPoint.javaMethod)
private val BEFORE_NVM_OPERATION_METHOD = Method.getMethod(ManagedStrategy::beforeNVMOperation.javaMethod)
private val MANAGED_STRATEGY_TYPE = Type.getType(ManagedStrategy::class.java)
private val METHOD_TRACE_POINT_TYPE = Type.getType(MethodCallTracePoint::class.java)
private val returnInstructions = listOf(
    Opcodes.RETURN,
    Opcodes.ARETURN,
    Opcodes.DRETURN,
    Opcodes.FRETURN,
    Opcodes.IRETURN,
    Opcodes.LRETURN
)
