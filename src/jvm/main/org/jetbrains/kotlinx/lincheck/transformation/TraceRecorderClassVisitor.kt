/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.kotlinx.lincheck.TraceDebuggerInjections
import org.jetbrains.kotlinx.lincheck.strategy.traceonly.TraceCollectingEventTracker
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter

class TraceRecorderClassVisitor(
    classVisitor: ClassVisitor
): ClassVisitor(ASM_API, classVisitor) {
    private lateinit var className: String

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String,
        interfaces: Array<String>
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        className = name.toCanonicalClassName()
    }

    override fun visitMethod(
        access: Int,
        methodName: String,
        desc: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor {
        fun MethodVisitor.newAdapter() = GeneratorAdapter(this, access, methodName, desc)

        var mv = super.visitMethod(access, methodName, desc, signature, exceptions)
        if (className == TraceDebuggerInjections.classUnderTraceDebugging &&
            methodName == TraceDebuggerInjections.methodUnderTraceDebugging) {
            mv = TraceRecorderRunMethodTransformer(mv.newAdapter())
        }

        return mv
    }
}

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
 *  val recorder = setupTraceRecorder()
 *  try { /* code */ }
 *  finally { recorder.dumpTrace(fileName) }
 * }
 * ```
 */
private class TraceRecorderRunMethodTransformer(
    private val adapter: GeneratorAdapter
) : MethodVisitor(ASM_API, adapter) {
    /**
     * This method is called for the target class and method that requires trace-recorder functionality.
     */

    override fun visitCode() = adapter.run {
        tryCatchFinally(
            tryBlock = {
                invokeStatic(TraceDebuggerInjections::setupTraceRecorder)
                visitCode()
            },
            finallyBlock =  {
                invokeStatic(TraceDebuggerInjections::dumpRecordedTrace)
            }
        )
    }
}
