/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.debugger

import org.jetbrains.lincheck.jvm.agent.ASM_API
import org.jetbrains.lincheck.jvm.agent.ifStatement
import org.jetbrains.lincheck.jvm.agent.invokeStatic
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter

/**
 * Wraps method provided by IDEA with lincheck setup when trace debugger is used.
 *
 * The method body is transformed from:
 * ```kotlin
 * fun methodUnderTraceDebugging() {/* code */}
 * ```
 *
 * To:
 * ```kotlin
 * fun methodUnderTraceDebugging() {
 *   if (TraceDebuggerInjections.isFirstRun) {
 *     TraceDebuggerInjections.isFirstRun = false // This code hidden in TraceDebuggerInjections.runWithLincheck()
 *     TraceDebuggerInjections.runWithLincheck() // lincheck internally calls `method` again
 *   } else {
 *     /* code */
 *   }
 * }
 * ```
 */
internal class TraceDebuggerMethodTransformer(
    className: String,
    fileName: String,
    private val adapter: GeneratorAdapter,
    access: Int,
    name: String,
    descriptor: String,
    firstLine: Int
) : MethodVisitor(ASM_API, adapter) {

    /**
     * This method is called for the target class and method that requires trace-debugger functionality.
     */
    override fun visitCode() = adapter.run {
        ifStatement(
            condition = {
                invokeStatic(TraceDebuggerInjections::isFirstRun)
            },
            thenClause = {
                invokeStatic(TraceDebuggerInjections::runWithLincheck)
            },
            elseClause = {
                visitCode()
            }
        )
    }
}