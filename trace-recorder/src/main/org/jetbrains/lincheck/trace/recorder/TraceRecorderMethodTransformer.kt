/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.recorder

import org.jetbrains.lincheck.descriptors.CodeLocations
import org.jetbrains.lincheck.jvm.agent.ASM_API
import org.jetbrains.lincheck.jvm.agent.invokeStatic
import org.jetbrains.lincheck.jvm.agent.toInternalClassName
import org.objectweb.asm.Label
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.GeneratorAdapter

/**
 * Wraps method provided by User with Trace Recorder setup when trace recorder is used.
 *
 * The method body is transformed from:
 * ```kotlin
 * fun methodUnderTraceDebugging() {/* code */}
 * ```
 *
 * To:
 * ```kotlin
 * fun methodUnderTraceDebugging() {
 *  TraceRecorderInjections::startTraceRecorder()
 *  try {
 *    /* code */
 *  }
 *  finally {
 *    TraceRecorderInjections::stopTraceRecorderAndDumpTrace()
 *  }
 * }
 * ```
 */
internal class TraceRecorderMethodTransformer(
    val className: String,
    val fileName: String,
    adapter: GeneratorAdapter,
    access: Int,
    methodName: String,
    descriptor: String
): AdviceAdapter(ASM_API, adapter, access, methodName, descriptor) {
    private val startLabel: Label = Label()

    private var lineNumber = 0
    private var codeLocationId: Int = -1

    override fun visitLineNumber(line: Int, start: Label?) {
        super.visitLineNumber(line, start)
        // Code location has been created without known line number, update line number
        if (lineNumber == 0 && codeLocationId >= 0) {
            CodeLocations.updateCodeLocationLineNumber(codeLocationId, line)
        }
        lineNumber = line
    }

    override fun onMethodEnter() {
        super.onMethodEnter()
        // Make code location
        // Line number may be unknown yet, update it later if needed
        val ste = StackTraceElement(
            /* declaringClass = */ className.toInternalClassName(),
            /* methodName = */ name,
            /* fileName = */ fileName,
            /* lineNumber = */ lineNumber
        )

        codeLocationId = CodeLocations.newCodeLocation(ste)
        push(codeLocationId)

        invokeStatic(TraceRecorderInjections::startTraceRecorder)
        // Start the "try-finally" block here to add stop & dump in "finally"
        visitLabel(startLabel)
    }

    override fun onMethodExit(opcode: Int) {
        super.onMethodExit(opcode)
        if (opcode == ATHROW) return
        invokeStatic(TraceRecorderInjections::stopTraceRecorderAndDumpTrace)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        val endLabel = Label()
        visitLabel(endLabel)
        visitTryCatchBlock(startLabel, endLabel, endLabel, null)
        // Handler
        invokeStatic(TraceRecorderInjections::stopTraceRecorderAndDumpTrace)
        // STACK: Exception
        // This will call onMethodExit() too!
        visitInsn(ATHROW)

        super.visitMaxs(maxStack, maxLocals)
    }
}
