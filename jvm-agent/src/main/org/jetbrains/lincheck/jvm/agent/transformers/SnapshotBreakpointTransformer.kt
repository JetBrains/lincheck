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
import org.jetbrains.lincheck.trace.TraceContext
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ILOAD
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.Injections

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
    private val liveDebuggerSettings: LiveDebuggerSettings
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {
    
    override fun visitLineNumber(line: Int, start: Label) {
        super.visitLineNumber(line, start)
        if (liveDebuggerSettings.lineBreakPoints.any { it.lineNumber == line && it.className == className.toCanonicalClassName()} ) {
            adapter.invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
            loadNewCodeLocationId()
            val activeLocals = currentActiveLocals
            
            // Pushes local variable values onto the stack as Object[], including
            // - this
            // - method parameters
            // - local variables
            
            // Push new array size
            adapter.push(activeLocals.size)
            
            // Create new array of size activeLocals.size 
            adapter.newArray(OBJECT_TYPE)
            
            activeLocals.forEachIndexed { index, localVariableInfo ->
                
                // Duplicate reference of the newly created array, as it will be consumed by arrayStore
                adapter.dup()
                
                // Push the array index of where to store the variable value
                adapter.push(index)
                
                // Load the variable at slot localVariableInfo.index
                adapter.visitVarInsn(localVariableInfo.type.getOpcode(ILOAD), localVariableInfo.index)
                
                // Boxes primitive values
                adapter.box(localVariableInfo.type)
                
                // Stores the boxed variable value in the new array
                adapter.arrayStore(OBJECT_TYPE)
            }

            adapter.invokeStatic(Injections::onSnapshotLineBreakpoint)
        }
    }
}