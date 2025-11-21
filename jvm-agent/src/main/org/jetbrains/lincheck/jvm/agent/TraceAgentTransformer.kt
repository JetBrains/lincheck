/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ACC_ABSTRACT
import org.objectweb.asm.Opcodes.ACC_BRIDGE
import org.objectweb.asm.Opcodes.ACC_NATIVE
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.commons.GeneratorAdapter
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

typealias MethodVisitorProvider = (
    className: String,
    fileName: String,
    adapter: GeneratorAdapter,
    access: Int,
    methodName: String,
    descriptor: String
) -> MethodVisitor

class TraceAgentTransformer(val methodTransformer: MethodVisitorProvider) : ClassFileTransformer {
    override fun transform(
        loader: ClassLoader?,
        internalClassName: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classBytes: ByteArray
    ): ByteArray? {
        // Internal class name could be `null` in some cases (can be witnessed on JDK-8),
        // this can be related to the Kotlin compiler bug:
        // - https://youtrack.jetbrains.com/issue/KT-16727/
        if (internalClassName == null) return null
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
    private var fileName: String = ""

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

    override fun visitSource(source: String?, debug: String?) {
        fileName = source!!
    }

    override fun visitMethod(
        access: Int,
        methodName: String,
        desc: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor {
        fun MethodVisitor.newAdapter() = GeneratorAdapter(this, access, methodName, desc)

        val isNotSynthetic = access and (ACC_SYNTHETIC or ACC_BRIDGE or ACC_ABSTRACT or ACC_NATIVE) == 0

        var mv = super.visitMethod(access, methodName, desc, signature, exceptions)
        // Don't transform synthetic methods, as they cannot be asked for by the user
        if (className == TraceAgentParameters.classUnderTraceDebugging &&
            methodName == TraceAgentParameters.methodUnderTraceDebugging &&
            isNotSynthetic) {
            mv = methodTransformer(className, fileName,mv.newAdapter(), access, methodName, desc)
        }

        return mv
    }
}
