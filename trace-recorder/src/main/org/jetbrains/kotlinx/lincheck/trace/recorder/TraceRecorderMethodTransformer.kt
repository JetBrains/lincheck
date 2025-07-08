/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.trace.recorder

import org.jetbrains.kotlinx.lincheck.transformation.ASM_API
import org.jetbrains.kotlinx.lincheck.transformation.invokeStatic
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
    adapter: GeneratorAdapter,
    access: Int,
    name: String,
    descriptor: String
): AdviceAdapter(ASM_API, adapter, access, name, descriptor) {
    private val startLabel: Label = Label()

    override fun onMethodEnter() {
        super.onMethodEnter()
        invokeStatic(TraceRecorderInjections::startTraceRecorder)
        // Start "try-finally block here to add stop & dump in "finally"
        visitLabel(startLabel)
     }

     override fun onMethodExit(opcode: Int) {
         invokeStatic(TraceRecorderInjections::stopTraceRecorderAndDumpTrace)
         super.onMethodExit(opcode)
     }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        val endLabel = Label()
        visitLabel(endLabel)
        visitTryCatchBlock(startLabel, endLabel, endLabel, null)
        // Handler
        // STACK: Exception
        // This will call onMethodExit() too!
        visitInsn(ATHROW)

        super.visitMaxs(maxStack, maxLocals)
    }
}
