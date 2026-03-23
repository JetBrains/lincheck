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
import org.jetbrains.lincheck.jvm.agent.analysis.*
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.util.Logger
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.*
import org.objectweb.asm.commons.InstructionAdapter.*
import sun.nio.ch.lincheck.*
import sun.nio.ch.lincheck.BreakpointStorage
import java.util.function.BooleanSupplier
import java.util.function.Function
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
    private val breakpoints: List<SnapshotBreakpoint>,
    private val classLoader: ClassLoader,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {

    private val traceIdCapturers = TraceIdCapturerRegistry(config)

    // Track which lines have been instrumented in the current basic block.
    // Reset when we see a new label (which starts a new block).
    // This avoids duplicate breakpoint hits for multi-line expressions like `foo(y, \n bar())`
    // where the same line number appears multiple times within a single block,
    // while still allowing hits in loops (where each iteration is a separate block entry).
    private val instrumentedLinesInCurrentBlock = mutableSetOf<Int>()

    override fun visitLabel(label: Label) {
        super.visitLabel(label)
        // New label = new basic block, reset the tracking
        instrumentedLinesInCurrentBlock.clear()
    }

    override fun visitLineNumber(line: Int, start: Label) = adapter.run {
        super.visitLineNumber(line, start)
        // Skip if we've already instrumented this line in the current basic block
        if (line in instrumentedLinesInCurrentBlock) return@run

        val canonicalClassName = className.toCanonicalClassName()
        val breakpoint = breakpoints.firstOrNull {
            it.lineNumber == line && it.className == canonicalClassName
        } ?: return@run

        // Check condition safety before emitting any breakpoint code.
        // Must happen before bytecode emission so we can cleanly skip the breakpoint if unsafe.
        if (breakpoint.conditionClassName != null && !isConditionSafe(breakpoint)) {
            return@run
        }

        // Mark this line as instrumented in the current block
        instrumentedLinesInCurrentBlock.add(line)

        Logger.debug { "Inserting snapshot breakpoint at ${className}:${line}" }

        // STACK: <empty>
        val exitLabel = newLabel()
        val threadDescriptorLocal = newLocal(OBJECT_TYPE)
        retrieveThreadDescriptorOrExit(threadDescriptorLocal, exitLabel)
        // STACK: <empty>

        callIfNotInsideBreakpointCondition(threadDescriptorLocal) {
            if (breakpoint.conditionClassName == null) {
                injectBreakpointHit(threadDescriptorLocal, breakpoint.id)
            } else {
                ifStatement(
                    condition = {
                        // The condition may execute code with an installed breakpoint.
                        // To not make a breakpoint hit, we track when we compute the condition.
                        enterBreakpointCondition(threadDescriptorLocal)
                        injectConditionCallWithTryCatch(breakpoint)
                        leaveBreakpointCondition(threadDescriptorLocal)
                    },
                    thenClause = {
                        injectBreakpointHit(threadDescriptorLocal, breakpoint.id)
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
        // === STEP 1: Load the condition class from bytecode (transformation time) ===
        // The condition code is provided as raw bytecode in conditionCodeFragment.
        // We dynamically load it into a Class object so we can access its factory method.
        val conditionClass = loadClassFromBytes(
            userCodeClassLoader = classLoader,
            className = breakpointSettings.conditionClassName!!,
            classBytes = breakpointSettings.conditionCodeFragment!!
        )

        // Condition safety has already been validated in visitLineNumber before bytecode emission.

        // === STEP 2: Register the condition factory (transformation time) ===
        // The condition class has a static "createFactory" method that returns a Function.
        // This factory takes captured variables as input and produces a BooleanSupplier.
        val createFactoryMethod = conditionClass.getDeclaredMethod("createFactory")
        createFactoryMethod.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val factory = createFactoryMethod.invoke(null) as Function<Array<Any?>, BooleanSupplier>
        BreakpointStorage.registerConditionFactory(breakpointSettings.id, factory)

        // === STEP 3: Inject bytecode to push the breakpointId (runtime) ===
        // The following bytecode will execute at runtime when the breakpoint is hit.
        // Push the breakpointId so Injections.createConditionInstance can look up the factory.
        push(breakpointSettings.id)   // Stack: [breakpointId]

        // === STEP 4: Capture local variables and build Object[] array (runtime) ===
        // The condition may reference local variables from the breakpoint location.
        // We need to capture their current values and pass them to the factory.
        val argNames = breakpointSettings.conditionCapturedVars.orEmpty()
        val capturedLocals = argNames.map { argName ->
            // Look up each captured variable name in the current method's active locals.
            // If a variable isn't found, throw an error (this indicates a bug in condition analysis).
            currentActiveLocals.firstOrNull { it.name == argName }
                ?: throw IllegalStateException("Local variable '$argName' not found in active locals at line ${breakpointSettings.lineNumber}")
        }

        // TODO: the fragment below is essentially a copy-paste of `pushArray` method,
        //   but using given types and `visitVarInsn` instead of `loadLocal`;
        //   `pushArray` is not used directly because there is some problem with locals numeration;
        //   probably, we need to somehow take into account locals re-enumeration performed by `GeneratorAdapter`.

        // STACK: <empty>
        push(capturedLocals.size)
        // STACK: arraySize
        visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
        // STACK: array
        for (i in capturedLocals.indices) {
            val idx = capturedLocals[i].index
            val type = capturedLocals[i].type

            // STACK: array
            dup()
            // STACK: array, array
            push(i)
            // STACK: array, array, index
            visitVarInsn(type.getOpcode(ILOAD), idx)
            // STACK: array, array, index, value[index]
            box(type)
            arrayStore(OBJECT_TYPE)
            // STACK: array
        }
        // STACK: array

        // Stack after loop: [...lookup params..., capturedValuesArray]

        // === STEP 5: Create the condition instance and evaluate it (runtime) ===
        // Call Injections.createConditionInstance(breakpointId, capturedValues).
        // This looks up the registered factory and creates a BooleanSupplier instance.
        invokeStatic(Injections::createConditionInstance)
        // Stack: [BooleanSupplier instance]

        // Cast to BooleanSupplier (for type safety)
        checkCast(booleanSupplierType)
        // Stack: [BooleanSupplier instance]

        // Call getAsBoolean() on the BooleanSupplier to evaluate the condition.
        invokeInterface(
            booleanSupplierType,
            Method(
                "getAsBoolean",
                Type.BOOLEAN_TYPE,
                emptyArray()
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

    /**
     * Checks whether the condition bytecode for the given breakpoint is safe (has no side effects).
     * If unsafe, fires the condition-unsafety notification via [BreakpointStorage] and returns `false`.
     */
    private fun isConditionSafe(breakpoint: SnapshotBreakpoint): Boolean {
        val conditionClassName = breakpoint.conditionClassName
            ?: return true // no condition is always safe

        val conditionClassBytes = breakpoint.conditionCodeFragment
        if (conditionClassBytes == null) {
            Logger.error {
                "Condition code for breakpoint at ${breakpoint.fileName}:${breakpoint.lineNumber} is not available"
            }
            return false
        }

        val conditionClass = loadClassFromBytes(
            userCodeClassLoader = classLoader,
            className = conditionClassName,
            classBytes = conditionClassBytes
        )
        val allowedFunctionCalls = { className: String, methodName: String, _: String ->
            className == conditionClassName.toInternalClassName() && methodName.startsWith("accessToField")
        }
        val safetyViolation = ConditionSafetyChecker.checkMethodForSideEffects(
            className = conditionClassName,
            methodName = "invoke",
            methodDescriptor = "()Z",
            classLoader = conditionClass.classLoader,
            allowedFunctionCalls = allowedFunctionCalls
        )
        if (safetyViolation != null) {
            Logger.warn {
                "Breakpoint condition at ${breakpoint.fileName}:${breakpoint.lineNumber} is not safe: $safetyViolation"
            }
            BreakpointStorage.notifyConditionUnsafetyDetected(breakpoint, safetyViolation)
            return false
        }
        return true
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

    private fun GeneratorAdapter.injectBreakpointHit(
        threadDescriptorLocal: Int,
        breakpointId: Int,
    ) {
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
        // Push the breakpoint's integer id as a compile-time constant.
        // Used by the event tracker for O(1) hit-limit checking and condition lookup.
        push(breakpointId)
        invokeStatic(Injections::onSnapshotLineBreakpoint)
    }
}

private val booleanSupplierType = Type.getType(BooleanSupplier::class.java)
