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
import org.objectweb.asm.Type
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
        if (liveDebuggerSettings.lineBreakPoints.any { it.lineNumber == line && it.fileName == fileName} ) {
            adapter.invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
            loadNewCodeLocationId()

            // Load active local variables
            val activeLocals = methodInfo.locals.activeVariables
                .filter { !it.isInlineCallMarker && !it.isInlineLambdaMarker }
                .sortedBy { it.index }
            
            // Push values onto stack as Object[]
            adapter.push(activeLocals.size)
            adapter.newArray(OBJECT_TYPE)
            activeLocals.forEachIndexed { index, local ->
                adapter.dup()
                adapter.push(index)
                adapter.visitVarInsn(local.type.getOpcode(org.objectweb.asm.Opcodes.ILOAD), local.index)
                adapter.box(local.type)
                adapter.arrayStore(OBJECT_TYPE)
            }

            // Push names onto stack as String[]
            adapter.push(activeLocals.size)
            adapter.newArray(Type.getType(String::class.java))
            activeLocals.forEachIndexed { index, local ->
                adapter.dup()
                adapter.push(index)
                adapter.push(local.name)
                adapter.arrayStore(Type.getType(String::class.java))
            }

            adapter.invokeStatic(Injections::onSnapshotLineBreakpoint)
        }
    }
}