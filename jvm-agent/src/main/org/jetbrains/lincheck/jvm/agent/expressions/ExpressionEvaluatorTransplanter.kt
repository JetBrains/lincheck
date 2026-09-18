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
 * class Wrapper { Counter __instance; int limit; boolean invoke() { return false; } }
 * ```
 * The output wrapper is equivalent to:
 * ```
 * class Wrapper {
 *     Counter __instance; int limit;
 *     static boolean evaluate(Counter receiver, int limit) { return receiver.count > limit; }
 *     boolean invoke() { return evaluate(__instance, limit); }
 * }
 * ```
 * [ExpressionBytecodeRewriter] handles any application-member access that cannot run directly afterward.
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
        prepareEvaluator: (MethodNode) -> Unit = {},
    ): ByteArray {
        val facade = ClassNode(Opcodes.ASM9).also { ClassReader(facadeBytes).accept(it, 0) }
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

        val wrapper = ClassNode(Opcodes.ASM9).also { ClassReader(wrapperBytes).accept(it, 0) }
        if (evaluatorReceiverType != null) {
            val facadeReceiver = facade.name
            // The wrapper is defined while the receiver class is in its transform callback. Keeping that
            // class out of the evaluator signature prevents its loader from re-entering the definition.
            evaluator.desc = Type.getMethodDescriptor(
                Type.getReturnType(evaluator.desc),
                objectType,
                *Type.getArgumentTypes(evaluator.desc),
            )
            evaluator.localVariables?.filter { it.index == 0 && it.desc == "L$facadeReceiver;" }
                ?.forEach { it.desc = objectType.descriptor }
            evaluator.instructions.toArray().filterIsInstance<FrameNode>().forEach { frame ->
                frame.local = frame.local?.map {
                    if (it == facadeReceiver) objectType.internalName else it
                }?.toMutableList()
            }
            evaluator.instructions.toArray().filterIsInstance<VarInsnNode>()
                .filter { it.opcode == Opcodes.ALOAD && it.`var` == 0 }
                .forEach { loadReceiver ->
                    evaluator.instructions.insert(loadReceiver, TypeInsnNode(Opcodes.CHECKCAST, evaluatorReceiverType.internalName))
                }
        }
        evaluator.access = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
        evaluator.signature = null
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
        getField(Type.getObjectType(wrapper.name), capture.name, capture.type)
    }
}
