/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.util.Logger
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.*
import org.objectweb.asm.commons.InstructionAdapter.*
import sun.nio.ch.lincheck.*
import kotlin.collections.forEach
import kotlin.collections.orEmpty

internal class SnapshotBreakpointTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    context: TraceContext,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    config: TransformationConfiguration,
    private val liveDebuggerSettings: LiveDebuggerSettings,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {
    
    private val traceIdCapturers = TraceIdCapturerRegistry(config)

    override fun visitLineNumber(line: Int, start: Label) = adapter.run {
        super.visitLineNumber(line, start)

        val breakpointSettings = liveDebuggerSettings.lineBreakPoints.firstOrNull {
            it.lineNumber == line && it.className == className.toCanonicalClassName()
        }
        if (breakpointSettings == null) return@run

        Logger.debug { "Inserting snapshot breakpoint at ${className}:${line}" }

        // STACK: <empty>
        val exitLabel = newLabel()
        val threadDescriptorLocal = newLocal(OBJECT_TYPE)
        retrieveThreadDescriptorOrExit(threadDescriptorLocal, exitLabel)
        // STACK: <empty>

        callIfNotInsideBreakpointCondition(threadDescriptorLocal) {
            if (breakpointSettings.conditionClassName == null) {
                injectBreakpointHit(threadDescriptorLocal)
            } else {
                ifStatement(
                    condition = {
                        // The condition may execute code with an installed breakpoint.
                        // To not make a breakpoint hit, we track when we compute the condition.
                        enterBreakpointCondition(threadDescriptorLocal)
                        injectConditionCallWithTryCatch(breakpointSettings)
                        leaveBreakpointCondition(threadDescriptorLocal)
                    },
                    thenClause = {
                        injectBreakpointHit(threadDescriptorLocal)
                    }
                )
            }
        }

        visitLabel(exitLabel)
    }

    private fun GeneratorAdapter.retrieveThreadDescriptorOrExit(threadDescriptorLocal: Int, exitLabel: Label) {
        // STACK: <empty>
        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        // STACK: threadDescriptor
        dup()
        storeLocal(threadDescriptorLocal)
        // STACK: threadDescriptor
        ifNull(exitLabel)
    }

    private fun GeneratorAdapter.enterBreakpointCondition(threadDescriptorLocal: Int) {
        loadLocal(threadDescriptorLocal)
        invokeStatic(Injections::enterBreakpointCondition)
    }

    private fun GeneratorAdapter.leaveBreakpointCondition(threadDescriptorLocal: Int) {
        loadLocal(threadDescriptorLocal)
        invokeStatic(Injections::leaveBreakpointCondition)
    }

    private fun GeneratorAdapter.injectConditionCall(breakpointSettings: SnapshotBreakpoint) {
        // Extract argument names and load their values
        val argNames = breakpointSettings.conditionArgs.orEmpty()
        val argTypes = mutableListOf<Type>()

        argNames.forEach { argName ->
            // Find the local variable by name in the current active locals
            val localVar = currentActiveLocals.firstOrNull { it.name == argName }
            if (localVar != null) {
                // Load the local variable value
                visitVarInsn(localVar.type.getOpcode(ILOAD), localVar.index)
                argTypes.add(localVar.type)
            } else {
                // If variable not found, handle gracefully by skipping this condition
                throw IllegalStateException("Local variable '$argName' not found in active locals at line ${breakpointSettings.lineNumber}")
            }
        }

        // Call the condition function with the loaded arguments
        invokeStatic(
            Type.getObjectType(breakpointSettings.conditionClassName!!.toInternalClassName()),
            Method(
                breakpointSettings.conditionMethodName!!,
                Type.BOOLEAN_TYPE,
                argTypes.toTypedArray()
            )
        )
        // Stack: [boolean result] - this will be consumed by the surrounding ifStatement
    }

    /**
     * Wraps the condition call in a try-catch block.
     * If the condition evaluation throws an exception, it returns `false`.
     * This ensures that breakpoint conditions don't crash the application.
     */
    private fun GeneratorAdapter.injectConditionCallWithTryCatch(breakpointSettings: SnapshotBreakpoint) {
        // We need to store the result in a local variable because try-catch blocks
        // require the stack to be empty at block boundaries.
        val resultLocal = newLocal(Type.BOOLEAN_TYPE)
        // Initialize to false (default if exception occurs)
        push(false)
        storeLocal(resultLocal)

        tryCatchFinally(
            tryBlock = {
                injectConditionCall(breakpointSettings)
                storeLocal(resultLocal)
            },
            exceptionType = Type.getType(Throwable::class.java),
            catchBlock = {
                // Exception is on the stack, just pop it and leave resultLocal as false
                pop()
            }
        )

        // Load the result onto the stack
        loadLocal(resultLocal)
    }

    private fun GeneratorAdapter.callIfNotInsideBreakpointCondition(threadDescriptorLocal: Int, block: () -> Unit) {
        ifStatement(
            condition = {
                loadLocal(threadDescriptorLocal)
                invokeStatic(Injections::isNotInsideBreakpointCondition)
            },
            thenClause = {
                block()
            }
        )
    }

    private fun GeneratorAdapter.injectBreakpointHit(threadDescriptorLocal: Int) {
        loadLocal(threadDescriptorLocal)
        loadNewCodeLocationId()
        val activeLocals = currentActiveLocals

        // Pushes local variable values onto the stack as Object[], including
        // - this
        // - method parameters
        // - local variables

        // Push new array size
        push(activeLocals.size)

        // Create new array of size activeLocals.size
        newArray(OBJECT_TYPE)

        activeLocals.forEachIndexed { index, localVariableInfo ->
            // Duplicate reference of the newly created array, as it will be consumed by arrayStore
            dup()
            // Push the array index of where to store the variable value
            push(index)
            // Load the variable at slot localVariableInfo.index
            visitVarInsn(localVariableInfo.type.getOpcode(ILOAD), localVariableInfo.index)
            // Boxes primitive values
            box(localVariableInfo.type)
            // Stores the boxed variable value in the new array
            arrayStore(OBJECT_TYPE)
        }

        traceIdCapturers.loadTraceIdIfAvailable(adapter)
        invokeStatic(Injections::onSnapshotLineBreakpoint)
    }
}
