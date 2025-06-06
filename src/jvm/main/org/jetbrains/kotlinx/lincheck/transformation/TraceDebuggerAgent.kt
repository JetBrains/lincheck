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
import org.jetbrains.kotlinx.lincheck.TraceDebuggerInjections.classUnderTraceDebugging
import org.jetbrains.kotlinx.lincheck.TraceDebuggerInjections.methodUnderTraceDebugging
import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.isInTraceRecorderMode
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
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        check(isInTraceDebuggerMode || isInTraceRecorderMode) {
            "When trace debugger agent is attached to process, " +
            "One of VM parameters `lincheck.traceDebuggerMode` or `lincheck.traceRecorderMode` is expected to be true. " +
            "Rerun with -Dlincheck.traceDebuggerMode=true or -Dlincheck.traceRecorderMode=true."
        }
        check(isInTraceDebuggerMode != isInTraceRecorderMode) {
            "When trace debugger agent is attached to process, " +
            "Only one of VM parameters `lincheck.traceDebuggerMode` or `lincheck.traceRecorderMode` is expected to be true. " +
            "Remove one of -Dlincheck.traceDebuggerMode=true or -Dlincheck.traceRecorderMode=true."
        }
        TraceDebuggerInjections.parseArgs(agentArgs)
        if (isInTraceDebuggerMode) {
            inst.addTransformer(TraceDebuggerTransformer, true)
        } else {
            // This adds turn-on and turn-off of tracing to method in question
            inst.addTransformer(TraceRecorderTransformer, true)
            // This prepare instrumentation of all future classes
            TraceDebuggerInjections.prepareTraceRecorder()
        }
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
        if (classUnderTraceDebugging != internalClassName.toCanonicalClassName()) {
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

internal object TraceRecorderTransformer : ClassFileTransformer {

    override fun transform(
        loader: ClassLoader?,
        internalClassName: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classBytes: ByteArray
    ): ByteArray? {
        // If the class should not be transformed, return immediately.
        if (classUnderTraceDebugging != internalClassName.toCanonicalClassName()) {
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
                TraceRecorderClassVisitor(writer),
                ClassReader.EXPAND_FRAMES
            )
            bytes = writer.toByteArray()

            return bytes
        } catch (e: Throwable) {
            System.err.println("Unable to transform '$internalClassName': $e")
            return classBytes
        }
    }
}

