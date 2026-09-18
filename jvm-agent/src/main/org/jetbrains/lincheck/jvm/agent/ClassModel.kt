/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** A field exposed to expression compilation; [declaringBinaryName] keeps JVM `$` separators. */
internal class FieldModel(
    val declaringBinaryName: String,
    val name: String,
    val type: Type,
    val isPublic: Boolean,
    val isProtected: Boolean,
    val isPrivate: Boolean,
    val isStatic: Boolean,
)

/** A method declaration needed to reproduce the enclosing class's Java compile-time surface. */
internal class MethodModel(
    val declaringBinaryName: String,
    val name: String,
    val descriptor: String,
    val isPublic: Boolean,
    val isProtected: Boolean,
    val isPrivate: Boolean,
    val isStatic: Boolean,
    val isSynthetic: Boolean,
    val isVarArgs: Boolean,
)

/** A constructor declaration needed by a nested Java class facade. */
internal class ConstructorModel(
    val descriptor: String,
    val isPublic: Boolean,
    val isProtected: Boolean,
    val isPrivate: Boolean,
)

/** A direct member class and the source modifiers recorded in its InnerClasses entry. */
internal class NestedClassModel(
    val binaryName: String,
    val simpleName: String,
    val isPublic: Boolean,
    val isProtected: Boolean,
    val isPrivate: Boolean,
    val isStatic: Boolean,
)

/**
 * The expression-visible surface of a class being instrumented, derived while it may still be defined.
 *
 * Superclass facades are reconstructed from class resources when available.
 */
internal class ClassModel(
    val binaryName: String,
    val superclassBinaryName: String?,
    val declaredFields: Map<String, FieldModel>,
    val declaredMethods: List<MethodModel>,
    val declaredConstructors: List<ConstructorModel>,
    val nestedClasses: List<NestedClassModel>,
    val isInterface: Boolean,
) {
    companion object {
        /** Builds the model from raw class bytes; safe during the class's initial definition. */
        fun fromClassBytes(classBytes: ByteArray): ClassModel {
            var binaryName = ""
            var superclassBinaryName: String? = null
            var isInterface = false
            val fields = HashMap<String, FieldModel>()
            val methods = ArrayList<MethodModel>()
            val constructors = ArrayList<ConstructorModel>()
            val nestedClasses = ArrayList<NestedClassModel>()
            ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int, access: Int, name: String,
                    signature: String?, superName: String?, interfaces: Array<out String>?,
                ) {
                    binaryName = name.replace('/', '.')
                    superclassBinaryName = superName?.replace('/', '.')
                    isInterface = access and Opcodes.ACC_INTERFACE != 0
                }

                override fun visitField(
                    access: Int, name: String, descriptor: String?,
                    signature: String?, value: Any?,
                ): FieldVisitor? {
                    if (descriptor != null) {
                        fields[name] = FieldModel(
                            declaringBinaryName = binaryName,
                            name = name,
                            type = Type.getType(descriptor),
                            isPublic = access and Opcodes.ACC_PUBLIC != 0,
                            isProtected = access and Opcodes.ACC_PROTECTED != 0,
                            isPrivate = access and Opcodes.ACC_PRIVATE != 0,
                            isStatic = access and Opcodes.ACC_STATIC != 0,
                        )
                    }
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name == "<init>") {
                        constructors += ConstructorModel(
                            descriptor = descriptor,
                            isPublic = access and Opcodes.ACC_PUBLIC != 0,
                            isProtected = access and Opcodes.ACC_PROTECTED != 0,
                            isPrivate = access and Opcodes.ACC_PRIVATE != 0,
                        )
                    } else if (name != "<clinit>") {
                        methods += MethodModel(
                            declaringBinaryName = binaryName,
                            name = name,
                            descriptor = descriptor,
                            isPublic = access and Opcodes.ACC_PUBLIC != 0,
                            isProtected = access and Opcodes.ACC_PROTECTED != 0,
                            isPrivate = access and Opcodes.ACC_PRIVATE != 0,
                            isStatic = access and Opcodes.ACC_STATIC != 0,
                            isSynthetic = access and Opcodes.ACC_SYNTHETIC != 0,
                            isVarArgs = access and Opcodes.ACC_VARARGS != 0,
                        )
                    }
                    return null
                }

                override fun visitInnerClass(
                    name: String,
                    outerName: String?,
                    innerName: String?,
                    access: Int,
                ) {
                    if (outerName?.replace('/', '.') == binaryName && innerName != null) {
                        nestedClasses += NestedClassModel(
                            binaryName = name.replace('/', '.'),
                            simpleName = innerName,
                            isPublic = access and Opcodes.ACC_PUBLIC != 0,
                            isProtected = access and Opcodes.ACC_PROTECTED != 0,
                            isPrivate = access and Opcodes.ACC_PRIVATE != 0,
                            isStatic = access and Opcodes.ACC_STATIC != 0,
                        )
                    }
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG)
            return ClassModel(
                binaryName, superclassBinaryName, fields, methods, constructors, nestedClasses, isInterface,
            )
        }
    }
}

/** Loads a byte-derived class model without initializing the target class. */
internal fun loadClassModel(binaryName: String, classLoader: ClassLoader?): ClassModel? {
    val resourceName = binaryName.replace('.', '/') + ".class"
    val stream = classLoader?.getResourceAsStream(resourceName)
        ?: ClassLoader.getSystemResourceAsStream(resourceName)
        ?: return null
    return stream.use { ClassModel.fromClassBytes(it.readBytes()) }
}
