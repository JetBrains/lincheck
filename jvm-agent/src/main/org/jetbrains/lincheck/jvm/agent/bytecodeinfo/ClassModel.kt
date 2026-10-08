/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.bytecodeinfo

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.tree.ClassNode

/** A field exposed to expression compilation; [declaringBinaryName] keeps JVM `$` separators. */
internal class FieldModel(
    val declaringBinaryName: String,
    val name: String,
    val type: Type,
    val isPublic: Boolean,
    val isProtected: Boolean,
    val isPrivate: Boolean,
    val isStatic: Boolean,
    val isSynthetic: Boolean,
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
 *
 * @property isPublic The class file's own `ACC_PUBLIC`, which is what the JVM checks when a class in another
 *   runtime package (the generated expression wrappers) names this class.
 * @property typeParameterCount The number of formal type parameters in the class signature.
 * @property isKotlinFileFacade Whether the class is a Kotlin file facade (`FooKt`), a holder of top-level
 *   declarations with no instances.
 */
internal class ClassModel(
    val binaryName: String,
    val superclassBinaryName: String?,
    val interfaceBinaryNames: List<String>,
    val declaredFields: Map<String, FieldModel>,
    val declaredMethods: List<MethodModel>,
    val declaredConstructors: List<ConstructorModel>,
    val nestedClasses: List<NestedClassModel>,
    val isInterface: Boolean,
    val isPublic: Boolean,
    val typeParameterCount: Int,
    val isKotlinFileFacade: Boolean,
) {
    companion object {
        /** Builds the model from raw class bytes; safe during the class's initial definition. */
        fun fromClassBytes(classBytes: ByteArray): ClassModel {
            val builder = ModelBuilder()
            ClassReader(classBytes).accept(builder, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG)
            return builder.build()
        }

        /** Builds the model from an already parsed class, the way the transformer holds the class it instruments. */
        fun fromClassNode(classNode: ClassNode): ClassModel = ModelBuilder().also(classNode::accept).build()
    }
}

private class ModelBuilder : ClassVisitor(Opcodes.ASM9) {
    private var binaryName = ""
    private var superclassBinaryName: String? = null
    private var interfaceBinaryNames = emptyList<String>()
    private var isInterface = false
    private var isPublic = false
    private var typeParameterCount = 0
    private var isKotlinFileFacade = false
    private val fields = HashMap<String, FieldModel>()
    private val methods = ArrayList<MethodModel>()
    private val constructors = ArrayList<ConstructorModel>()
    private val nestedClasses = ArrayList<NestedClassModel>()

    override fun visit(
        version: Int, access: Int, name: String,
        signature: String?, superName: String?, interfaces: Array<out String>?,
    ) {
        binaryName = name.replace('/', '.')
        superclassBinaryName = superName?.replace('/', '.')
        interfaceBinaryNames = interfaces.orEmpty().map { it.replace('/', '.') }
        isInterface = access and Opcodes.ACC_INTERFACE != 0
        isPublic = access and Opcodes.ACC_PUBLIC != 0
        if (signature != null) {
            SignatureReader(signature).accept(object : SignatureVisitor(Opcodes.ASM9) {
                override fun visitFormalTypeParameter(name: String) {
                    typeParameterCount++
                }
            })
        }
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        if (descriptor != "Lkotlin/Metadata;") return null
        return object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(name: String?, value: Any?) {
                if (name == "k") isKotlinFileFacade = value in KOTLIN_FILE_FACADE_KINDS
            }
        }
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
                isSynthetic = access and Opcodes.ACC_SYNTHETIC != 0,
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

    fun build() = ClassModel(
        binaryName, superclassBinaryName, interfaceBinaryNames, fields, methods, constructors, nestedClasses,
        isInterface, isPublic, typeParameterCount, isKotlinFileFacade,
    )

    private companion object {
        // `k` of `kotlin.Metadata`: a file facade or a part of a multi-file facade.
        val KOTLIN_FILE_FACADE_KINDS = setOf(2, 5)
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
