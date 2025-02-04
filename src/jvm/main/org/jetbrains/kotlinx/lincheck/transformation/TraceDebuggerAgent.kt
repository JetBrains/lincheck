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

import org.jetbrains.kotlinx.lincheck.canonicalClassName
import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.transformation.TraceDebuggerAgent.classUnderTraceDebugging
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

/**
 * Agent that is set as `premain` entry class for fat trace debugger jar archive.
 * This archive when attached to the jvm process expects also a `-Dlincheck.traceDebuggerMode=true`
 * in order to enable trace debugging plugin functionality.
 */
internal object TraceDebuggerAgent {
    val classUnderTraceDebugging: String = System.getProperty("traceDebugger.className", "")
    val methodUnderTraceDebugging: String = System.getProperty("traceDebugger.methodName", "")

    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        check(isInTraceDebuggerMode) {
            "When trace debugger agent is attached to process, " +
            "VM parameter `lincheck.traceDebuggerMode` is expected to be true. " +
            "Rerun with -Dlincheck.traceDebuggerMode=true."
        }
        check(classUnderTraceDebugging.isNotEmpty()) { "Class name under trace debugging not specified" }
        check(methodUnderTraceDebugging.isNotEmpty()) { "Method name under trace debugging not specified" }

        inst.addTransformer(TraceDebuggerTransformer, true)
    }
}

internal object TraceDebuggerTransformer : ClassFileTransformer {

    override fun transform(
        loader: ClassLoader?,
        internalClassName: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classBytes: ByteArray
    ): ByteArray? {
        // If the class should not be transformed, return immediately.
        if (classUnderTraceDebugging != internalClassName.canonicalClassName) {
            return null
        }
        return transformImpl(loader, internalClassName, classBytes)
    }

    private fun transformImpl(
        loader: ClassLoader?,
        internalClassName: String,
        classBytes: ByteArray
    ): ByteArray {
        try {
            val bytes: ByteArray
            val reader = ClassReader(classBytes)
            val writer = SafeClassWriter(reader, loader, ClassWriter.COMPUTE_FRAMES)

            reader.accept(
                TraceDebuggerClassVisitor(writer),
                ClassReader.SKIP_FRAMES
            )
            bytes = writer.toByteArray()

            return bytes
        } catch (e: Throwable) {
            System.err.println("Unable to transform '$internalClassName': $e")
            return classBytes
        }
    }
}