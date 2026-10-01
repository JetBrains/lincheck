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

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * Moves a compiled expression evaluator from the disposable enclosing-class facade into the runtime wrapper.
 *
 * The Java or Kotlin enclosing-class evaluator compiles two classes independently: a facade containing the user's
 * expression and an [ExpressionWrapper] containing capture fields and a placeholder `invoke()`.
 * This step copies the facade's evaluator method into the wrapper, makes it static, and rewrites `invoke()` to pass
 * the captured receiver and locals to it.
 *
 * For `count > limit` in `Counter`, the inputs are equivalent to:
 * ```
 * class Counter { boolean evaluate(int limit) { return count > limit; } }
 * class Wrapper { Object __instance; int limit; boolean invoke() { return false; } }
 * ```
 * The output wrapper is equivalent to:
 * ```
 * class Wrapper {
 *     Object __instance; int limit;
 *     static boolean evaluate(Object receiver, int limit) { return ((Counter) receiver).count > limit; }
 *     boolean invoke() { return evaluate(__instance, limit); }
 * }
 * ```
 *
 * The evaluator's signature names no application class afterwards. The wrapper is defined while the instrumented
 * class may still be in its own definition, and looking up `createFactory` reflectively resolves the signature of
 * every declared method; a parameter typed as that class would load it a second time, which the JVM refuses.
 * So application-typed parameters, the receiver included, travel as `Object` and are cast back where the
 * evaluator loads them. A class the wrapper cannot name at all (see [ApplicationClasses.isAccessible]) is never
 * cast to: its values stay `Object` throughout, and [ExpressionBytecodeRewriter] reaches its members reflectively.
 */
internal object ExpressionEvaluatorTransplanter {
    private val objectType = Type.getType(Any::class.java)

    fun transplant(
        facadeBytes: ByteArray,
        wrapperBytes: ByteArray,
        evaluatorMethodName: String,
        languageName: String,
        evaluatorReceiverType: Type?,
        receiverCapture: CapturedLocal?,
        evaluationCaptures: List<CapturedLocal>,
        kind: ExpressionKind,
        applicationClasses: ApplicationClasses,
        prepareEvaluator: (MethodNode) -> Unit = {},
    ): ByteArray {
        val facade = ClassNode(Opcodes.ASM9).also { ClassReader(facadeBytes).accept(it, ClassReader.EXPAND_FRAMES) }
        val evaluator = facade.methods.singleOrNull { it.name == evaluatorMethodName }
            ?: throw ExpressionCompilationException("Expression compiler produced no evaluation method")
        val hasLambda = evaluator.instructions.toArray().filterIsInstance<InvokeDynamicInsnNode>()
            .any { it.bsm.owner == "java/lang/invoke/LambdaMetafactory" }
        if (hasLambda) {
            throw ExpressionCompilationException(
                "$languageName lambdas in agent-compiled expressions are not yet supported",
            )
        }
        prepareEvaluator(evaluator)
        eraseApplicationTypes(evaluator, evaluatorReceiverType, applicationClasses)
        evaluator.access = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
        evaluator.signature = null

        val wrapper = ClassNode(Opcodes.ASM9).also { ClassReader(wrapperBytes).accept(it, 0) }
        wrapper.methods.add(evaluator)

        val returnType = when (kind) {
            ExpressionKind.CONDITION -> Type.BOOLEAN_TYPE
            ExpressionKind.WATCHES -> Type.getType(Array<Any?>::class.java)
        }
        val caller = when (kind) {
            ExpressionKind.CONDITION -> wrapper.methods.single {
                it.name == "invoke" && Type.getReturnType(it.desc) == returnType
            }
            ExpressionKind.WATCHES -> wrapper.methods.single { it.name == ExpressionWrapper.WATCH_VALUES_HELPER }
        }
        caller.instructions.clear()
        caller.tryCatchBlocks.clear()
        caller.localVariables?.clear()
        val generator = GeneratorAdapter(caller, caller.access, caller.name, caller.desc)
        if (receiverCapture != null) {
            generator.loadCapture(wrapper, receiverCapture)
        } else if (evaluatorReceiverType != null) {
            generator.visitInsn(Opcodes.ACONST_NULL)
        }
        evaluationCaptures.forEach { capture -> generator.loadCapture(wrapper, capture) }
        generator.invokeStatic(Type.getObjectType(wrapper.name), Method(evaluatorMethodName, evaluator.desc))
        generator.returnValue()
        generator.endMethod()
        return ClassWriter(ClassWriter.COMPUTE_MAXS).also(wrapper::accept).toByteArray()
    }

    private fun GeneratorAdapter.loadCapture(wrapper: ClassNode, capture: CapturedLocal) {
        loadThis()
        getField(Type.getObjectType(wrapper.name), capture.name, ExpressionWrapper.storageType(capture.type))
    }

    /**
     * Makes the evaluator's descriptor free of application classes: [receiverType] becomes a leading `Object`
     * parameter and every application-typed parameter becomes `Object`. Loads of such a parameter are followed by a
     * cast back to the declared type when the wrapper may name it. Classes it may not name are erased from the
     * method altogether: parameters, frames, and local variables become `Object`, and casts to them are dropped.
     */
    private fun eraseApplicationTypes(evaluator: MethodNode, receiverType: Type?, applicationClasses: ApplicationClasses) {
        val declaredArguments = Type.getArgumentTypes(evaluator.desc)
        val erasedParameters = LinkedHashMap<Int, Type>()
        var slot = 0
        if (receiverType != null) {
            erasedParameters[slot] = receiverType
            slot = 1
        }
        for (argument in declaredArguments) {
            if (argument.isApplicationType()) erasedParameters[slot] = argument
            slot += argument.size
        }
        val inaccessible = (referencedClassNames(evaluator) + listOfNotNull(receiverType?.elementName()))
            .filterNot(applicationClasses::isAccessible)
            .toSet()
        val erasedArguments = declaredArguments.map { if (it.isApplicationType()) objectType else it }
        evaluator.desc = Type.getMethodDescriptor(
            Type.getReturnType(evaluator.desc),
            *(listOfNotNull(receiverType?.let { objectType }) + erasedArguments).toTypedArray(),
        )

        val instructions = evaluator.instructions
        for (instruction in instructions.toArray()) {
            when (instruction) {
                is VarInsnNode -> if (instruction.opcode == Opcodes.ALOAD) {
                    val declared = erasedParameters[instruction.`var`] ?: continue
                    if (declared != objectType && declared.internalName !in inaccessible) {
                        instructions.insert(instruction, TypeInsnNode(Opcodes.CHECKCAST, declared.internalName))
                    }
                }
                is TypeInsnNode -> if (Type.getObjectType(instruction.desc).elementName() in inaccessible) {
                    when (instruction.opcode) {
                        Opcodes.CHECKCAST -> instructions.remove(instruction)
                        else -> throw ExpressionCompilationException(
                            "The expression uses ${instruction.desc.replace('/', '.')}, which is not public; " +
                                "only its members can be used",
                        )
                    }
                }
                is FrameNode -> {
                    instruction.local = instruction.local?.eraseLocals(erasedParameters.keys, inaccessible)
                    instruction.stack = instruction.stack?.map { it.erased(inaccessible) }
                }
            }
        }
        evaluator.localVariables?.forEach { local ->
            if (local.index in erasedParameters || Type.getType(local.desc).elementName() in inaccessible) {
                local.desc = objectType.descriptor
            }
        }
    }

    /** Every class an evaluator's parameters, casts, frames, and locals name. */
    private fun referencedClassNames(evaluator: MethodNode): Set<String> = buildSet {
        Type.getArgumentTypes(evaluator.desc).mapNotNullTo(this) { it.elementName() }
        evaluator.instructions.toArray().forEach { instruction ->
            when (instruction) {
                is TypeInsnNode -> Type.getObjectType(instruction.desc).elementName()?.let(::add)
                is FrameNode -> (instruction.local.orEmpty() + instruction.stack.orEmpty())
                    .filterIsInstance<String>()
                    .mapNotNullTo(this) { Type.getObjectType(it).elementName() }
                else -> Unit
            }
        }
        evaluator.localVariables?.mapNotNullTo(this) { Type.getType(it.desc).elementName() }
    }

    /** Erases the entries of the parameter [slots] and every entry naming an [inaccessible] class. */
    private fun List<Any>.eraseLocals(slots: Set<Int>, inaccessible: Set<String>): List<Any> {
        var slot = 0
        return map { entry ->
            val erased = if (slot in slots) objectType.internalName else entry.erased(inaccessible)
            slot += if (entry == Opcodes.LONG || entry == Opcodes.DOUBLE) 2 else 1
            erased
        }
    }

    private fun Any.erased(inaccessible: Set<String>): Any {
        if (this !is String) return this
        val type = Type.getObjectType(this)
        if (type.elementName() !in inaccessible) return this
        return if (type.sort == Type.ARRAY) {
            Type.getType("[".repeat(type.dimensions) + objectType.descriptor).internalName
        } else {
            objectType.internalName
        }
    }

    /** The internal name of this type's class, arrays unwrapped; `null` for primitives. */
    private fun Type.elementName(): String? {
        var element = this
        while (element.sort == Type.ARRAY) element = element.elementType
        return element.internalName.takeIf { element.sort == Type.OBJECT }
    }
}
