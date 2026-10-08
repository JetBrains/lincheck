/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions

import org.jetbrains.lincheck.jvm.agent.bytecodeinfo.FieldModel
import org.jetbrains.lincheck.jvm.agent.bytecodeinfo.MethodModel
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field

/**
 * Makes bytecode compiled against mocked application members safe to run against the real classes.
 *
 * [JavaEnclosingClassEvaluator] and [KotlinEnclosingClassEvaluator] expose application members in temporary source
 * so the expression can compile. After [ExpressionEvaluatorTransplanter] moves the evaluator into the wrapper,
 * this step uses [MockedApplicationMembers] to replace those direct field and method instructions with reflective
 * `accessToField_...` and `accessToMethod_...` helpers recognized by the safety checker.
 * Members of classes the wrapper cannot name ([ApplicationClasses.isAccessible]) get the same treatment, public or
 * not, through the class that declares them.
 *
 * For example, the evaluator for `count > limit` may contain:
 * ```
 * GETFIELD sample/Counter.count:I
 * ```
 * This phase replaces it with a call like:
 * ```
 * INVOKESTATIC Wrapper.accessToField_count_0(Object):int
 * ```
 * The generated helper finds `Counter.count` reflectively and returns its value.
 * Application types in helper signatures are represented as `Object` so rewriting does not load the class that is
 * currently being instrumented. Constructors, writes, and `super` calls are not rewritten.
 */
internal object ExpressionBytecodeRewriter {
    fun rewrite(
        classes: Map<String, ByteArray>,
        mockedMembers: MockedApplicationMembers,
        applicationClasses: ApplicationClasses,
    ): Map<String, ByteArray> =
        classes.mapValues { (_, bytes) -> rewriteClass(bytes, mockedMembers, applicationClasses) }

    private fun rewriteClass(
        bytes: ByteArray,
        mockedMembers: MockedApplicationMembers,
        applicationClasses: ApplicationClasses,
    ): ByteArray {
        val classNode = ClassNode(Opcodes.ASM9)
        ClassReader(bytes).accept(classNode, 0)
        val accessors = AccessorGenerator(classNode, applicationClasses)

        for (method in classNode.methods.toList()) {
            for (instruction in method.instructions.toArray()) {
                when (instruction) {
                    is FieldInsnNode -> {
                        if (instruction.opcode != Opcodes.GETFIELD && instruction.opcode != Opcodes.GETSTATIC) continue
                        val field = mockedMembers.field(instruction.owner, instruction.name, instruction.desc)
                            ?: applicationClasses.reflectiveField(
                                instruction.opcode == Opcodes.GETSTATIC,
                                instruction.owner, instruction.name, instruction.desc,
                            )
                            ?: continue
                        val accessor = accessors.field(field)
                        val replacement = MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            classNode.name,
                            accessor.name,
                            accessor.descriptor,
                            false,
                        )
                        method.instructions.set(instruction, replacement)
                        accessor.castResultTo?.let { type ->
                            method.instructions.insert(replacement, TypeInsnNode(Opcodes.CHECKCAST, type.internalName))
                        }
                    }
                    is MethodInsnNode -> {
                        if (instruction.name == "<init>" || instruction.owner == classNode.name) continue
                        val target = mockedMembers.method(instruction.owner, instruction.name, instruction.desc)
                            // A `super` call has its own dispatch; reflection would resolve it virtually.
                            ?: instruction.takeUnless { it.opcode == Opcodes.INVOKESPECIAL }?.let {
                                applicationClasses.reflectiveMethod(
                                    it.opcode == Opcodes.INVOKESTATIC, it.owner, it.name, it.desc,
                                )
                            }
                            ?: continue
                        val accessor = accessors.method(target)
                        val replacement = MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            classNode.name,
                            accessor.name,
                            accessor.descriptor,
                            false,
                        )
                        method.instructions.set(instruction, replacement)
                        accessor.castResultTo?.let { type ->
                            method.instructions.insert(replacement, TypeInsnNode(Opcodes.CHECKCAST, type.internalName))
                        }
                    }
                }
            }
        }

        accessors.addGeneratedMethods()
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        classNode.accept(writer)
        return writer.toByteArray()
    }
}

private data class GeneratedAccessor(
    val name: String,
    val descriptor: String,
    val castResultTo: Type?,
    val methodNode: MethodNode,
)

private class AccessorGenerator(private val owner: ClassNode, private val applicationClasses: ApplicationClasses) {
    private val fieldAccessors = LinkedHashMap<FieldModel, GeneratedAccessor>()
    private val methodAccessors = LinkedHashMap<MethodModel, GeneratedAccessor>()
    private var nextId = 0

    fun field(field: FieldModel): GeneratedAccessor = fieldAccessors.getOrPut(field) {
        generateFieldAccessor(field)
    }

    fun method(method: MethodModel): GeneratedAccessor = methodAccessors.getOrPut(method) {
        generateMethodAccessor(method)
    }

    fun addGeneratedMethods() {
        owner.methods.addAll(fieldAccessors.values.map(GeneratedAccessor::methodNode))
        owner.methods.addAll(methodAccessors.values.map(GeneratedAccessor::methodNode))
    }

    private fun generateFieldAccessor(field: FieldModel): GeneratedAccessor {
        val resultType = field.type
        val accessorResult = resultType.accessorType()
        val argumentTypes = if (field.isStatic) emptyArray() else arrayOf(OBJECT_TYPE)
        val descriptor = Type.getMethodDescriptor(accessorResult, *argumentTypes)
        val name = accessorName("accessToField", field.name)
        val node = newAccessorMethod(name, descriptor)
        val generator = GeneratorAdapter(node, node.access, name, descriptor)

        generator.push(field.declaringBinaryName)
        generator.invokeStatic(CLASS_TYPE, CLASS_FOR_NAME)
        generator.push(field.name)
        generator.invokeVirtual(CLASS_TYPE, GET_DECLARED_FIELD)
        generator.dup()
        generator.push(true)
        generator.invokeVirtual(ACCESSIBLE_OBJECT_TYPE, SET_ACCESSIBLE)
        if (field.isStatic) generator.visitInsn(Opcodes.ACONST_NULL) else generator.loadArg(0)
        generator.invokeVirtual(FIELD_TYPE, FIELD_GET)
        generator.convertReflectiveResult(accessorResult)
        generator.returnValue()
        generator.endMethod()

        return GeneratedAccessor(
            name = name,
            descriptor = descriptor,
            castResultTo = castTo(resultType, accessorResult),
            methodNode = node,
        )
    }

    /** The precise type to cast an `Object`-typed accessor result to, if the wrapper may name it. */
    private fun castTo(resultType: Type, accessorResult: Type): Type? =
        resultType.takeIf { accessorResult == OBJECT_TYPE && it != OBJECT_TYPE && applicationClasses.isAccessible(it) }

    private fun generateMethodAccessor(method: MethodModel): GeneratedAccessor {
        val targetType = Type.getMethodType(method.descriptor)
        val resultType = targetType.returnType
        val accessorResult = resultType.accessorType()
        val targetArguments = targetType.argumentTypes
        val accessorArguments = buildList {
            if (!method.isStatic) add(OBJECT_TYPE)
            targetArguments.mapTo(this) { it.accessorType() }
        }.toTypedArray()
        val descriptor = Type.getMethodDescriptor(accessorResult, *accessorArguments)
        val name = accessorName("accessToMethod", method.name)
        val node = newAccessorMethod(name, descriptor)
        val generator = GeneratorAdapter(node, node.access, name, descriptor)

        generator.push(method.declaringBinaryName)
        generator.invokeStatic(CLASS_TYPE, CLASS_FOR_NAME)
        generator.push(method.name)
        generator.push(targetArguments.size)
        generator.newArray(CLASS_TYPE)
        targetArguments.forEachIndexed { index, argument ->
            generator.dup()
            generator.push(index)
            generator.pushClass(argument)
            generator.arrayStore(CLASS_TYPE)
        }
        generator.invokeVirtual(CLASS_TYPE, GET_DECLARED_METHOD)
        generator.dup()
        generator.push(true)
        generator.invokeVirtual(ACCESSIBLE_OBJECT_TYPE, SET_ACCESSIBLE)
        if (method.isStatic) generator.visitInsn(Opcodes.ACONST_NULL) else generator.loadArg(0)
        generator.push(targetArguments.size)
        generator.newArray(OBJECT_TYPE)
        targetArguments.forEachIndexed { index, argument ->
            generator.dup()
            generator.push(index)
            generator.loadArg(index + if (method.isStatic) 0 else 1)
            generator.box(argument)
            generator.arrayStore(OBJECT_TYPE)
        }
        generator.invokeVirtual(METHOD_TYPE, METHOD_INVOKE)
        when (resultType.sort) {
            Type.VOID -> generator.pop()
            else -> generator.convertReflectiveResult(accessorResult)
        }
        generator.returnValue()
        generator.endMethod()

        return GeneratedAccessor(
            name = name,
            descriptor = descriptor,
            castResultTo = castTo(resultType, accessorResult),
            methodNode = node,
        )
    }

    private fun newAccessorMethod(name: String, descriptor: String) = MethodNode(
        Opcodes.ASM9,
        Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
        name,
        descriptor,
        null,
        null,
    )

    private fun accessorName(prefix: String, memberName: String): String {
        val safeName = memberName.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        return "${prefix}_${safeName}_${nextId++}"
    }

    private fun GeneratorAdapter.pushClass(type: Type) {
        if (type.sort in Type.BOOLEAN..Type.DOUBLE) {
            getStatic(type.boxedType(), "TYPE", CLASS_TYPE)
        } else {
            push(type.classForNameName())
            invokeStatic(CLASS_TYPE, CLASS_FOR_NAME)
        }
    }

    private fun GeneratorAdapter.convertReflectiveResult(type: Type) {
        if (type.sort in Type.BOOLEAN..Type.DOUBLE) unbox(type) else checkCast(type)
    }
}

private fun Type.accessorType(): Type = when (sort) {
    Type.VOID, in Type.BOOLEAN..Type.DOUBLE -> this
    Type.OBJECT -> if (internalName.startsWith("java/") || internalName.startsWith("javax/")) this else OBJECT_TYPE
    Type.ARRAY -> if (elementType.isPlatformOrPrimitive()) this else OBJECT_TYPE
    else -> OBJECT_TYPE
}

private fun Type.isPlatformOrPrimitive(): Boolean = when (sort) {
    in Type.BOOLEAN..Type.DOUBLE -> true
    Type.OBJECT -> internalName.startsWith("java/") || internalName.startsWith("javax/")
    Type.ARRAY -> elementType.isPlatformOrPrimitive()
    else -> false
}

private fun Type.classForNameName(): String = when (sort) {
    Type.ARRAY -> descriptor.replace('/', '.')
    else -> className
}

private fun Type.boxedType(): Type = when (sort) {
    Type.BOOLEAN -> Type.getType(java.lang.Boolean::class.java)
    Type.CHAR -> Type.getType(java.lang.Character::class.java)
    Type.BYTE -> Type.getType(java.lang.Byte::class.java)
    Type.SHORT -> Type.getType(java.lang.Short::class.java)
    Type.INT -> Type.getType(java.lang.Integer::class.java)
    Type.FLOAT -> Type.getType(java.lang.Float::class.java)
    Type.LONG -> Type.getType(java.lang.Long::class.java)
    Type.DOUBLE -> Type.getType(java.lang.Double::class.java)
    else -> this
}

private val OBJECT_TYPE = Type.getType(Object::class.java)
private val CLASS_TYPE = Type.getType(Class::class.java)
private val FIELD_TYPE = Type.getType(Field::class.java)
private val METHOD_TYPE = Type.getType(java.lang.reflect.Method::class.java)
private val ACCESSIBLE_OBJECT_TYPE = Type.getType(AccessibleObject::class.java)
private val CLASS_FOR_NAME = Method("forName", "(Ljava/lang/String;)Ljava/lang/Class;")
private val GET_DECLARED_FIELD = Method("getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;")
private val GET_DECLARED_METHOD =
    Method("getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;")
private val SET_ACCESSIBLE = Method("setAccessible", "(Z)V")
private val FIELD_GET = Method("get", "(Ljava/lang/Object;)Ljava/lang/Object;")
private val METHOD_INVOKE = Method("invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;")
