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
import org.jetbrains.kotlinx.lincheck.transformation.SafeClassWriter
import org.jetbrains.kotlinx.lincheck.transformation.toCanonicalClassName
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

typealias MethodVisitorProvider = (
    adapter: GeneratorAdapter,
    access: Int,
    name: String,
    descriptor: String
) -> MethodVisitor

class TraceAgentTransformer(val methodTransformer: MethodVisitorProvider) : ClassFileTransformer {
    override fun transform(
        loader: ClassLoader?,
        internalClassName: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classBytes: ByteArray
    ): ByteArray? {
        // If the class should not be transformed, return immediately.
        if (TraceAgentParameters.classUnderTraceDebugging != internalClassName.toCanonicalClassName()) {
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
                TraceAgentClassVisitor(writer, methodTransformer),
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

private class TraceAgentClassVisitor(
    classVisitor: ClassVisitor,
    val methodTransformer: MethodVisitorProvider
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
        if (className == TraceAgentParameters.classUnderTraceDebugging &&
            methodName == TraceAgentParameters.methodUnderTraceDebugging) {
            mv = methodTransformer(mv.newAdapter(), access, methodName, desc)
        }

        return mv
    }
}
