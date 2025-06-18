/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.traceagent

import org.jetbrains.kotlinx.lincheck.transformation.ASM_API
import org.jetbrains.kotlinx.lincheck.transformation.invokeInsideIgnoredSection
import org.jetbrains.kotlinx.lincheck.transformation.invokeStatic
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
     override fun onMethodEnter() {
         invokeStatic(TraceRecorderInjections::startTraceRecorder)
     }

     override fun onMethodExit(opcode: Int) {
         invokeStatic(TraceRecorderInjections::stopTraceRecorderAndDumpTrace)
     }
}
