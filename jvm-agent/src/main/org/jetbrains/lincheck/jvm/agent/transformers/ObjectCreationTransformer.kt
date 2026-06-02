/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import sun.nio.ch.lincheck.*
import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.trace.TraceContext
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE

/**
 * [ObjectCreationTransformer] tracks creation of new objects,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class ObjectCreationTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    context: TraceContext,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {

    override val requiresTypeAnalyzer: Boolean = true

    /* To track object creation, this transformer inserts:
     *  - `Injections::afterNewObjectCreation` after an array is allocated;
     *  - `Injections::afterObjectConstructor` after each object constructor invocation.
     *
     * The created object is passed into the injected function as an argument.
     *
     * In order to achieve this, this transformer tracks the following instructions:
     * `NEW`, `NEWARRAY`, `ANEWARRAY`, and `MULTIANEWARRAY`;
     *
     * It is possible to inject the injection call right after array objects creation
     * (i.e., after all instructions listed above except `NEW`),
     * since the array is in initialized state right after its allocation.
     * However, when an object is allocated via `NEW` it is first in uninitialized state,
     * until its constructor (i.e., `<init>` method) is called.
     * Trying to pass the object in uninitialized into the injected function would result
     * in a bytecode verification error.
     * Thus, we postpone the injection up after the constructor call (i.e., `<init>`).
     *
     * Another difficulty is that because of the inheritance, there could exist several
     * constructor calls (i.e., `<init>`) for the same object.
     * We need to distinguish between the base class constructor call inside the derived class constructor,
     * and the actual initializing constructor call from the object creation call size.
     *
     * Therefore, to tackle these issues, we maintain a counter of allocated, but not yet initialized objects.
     * Whenever we encounter a constructor call (i.e., `<init>`) we either:
     *  - check the counter and inject the object constructor tracking method
     *    if the constructor corresponds to a preceding `NEW` instruction; or
     *  - detect a `super()`/`this()` constructor call via the stack frame and inject
     *    the same tracking method for `this` after that call returns.
     *
     * The solution with allocated objects counter is inspired by:
     * https://github.com/google/allocation-instrumenter
     *
     * TODO: keeping just a counter might be not reliable in some cases,
     *   perhaps we need more robust solution, checking for particular bytecode instructions sequence, e.g.:
     *   `NEW; DUP; INVOKESPECIAL <init>`
     */
    private var uninitializedObjects = 0

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (name != "<init>") {
            super.visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }

        if (isReceiverUninitializedThis(desc)) {
            invokeAfterConstructorForCurrentThis(opcode, owner, name, desc, itf)
            return
        }

        if (uninitializedObjects > 0) {
            invokeAfterConstructorForNewObject(opcode, owner, name, desc, itf)
            uninitializedObjects--
            return
        }

        super.visitMethodInsn(opcode, owner, name, desc, itf)
    }

    private fun GeneratorAdapter.invokeAfterConstructorForNewObject(
        opcode: Int,
        owner: String,
        name: String,
        desc: String,
        itf: Boolean
    ) {
        invokeIfInAnalyzedCode(
            original = {
                super.visitMethodInsn(opcode, owner, name, desc, itf)
            },
            instrumented = {
                val objectLocal = newLocal(OBJECT_TYPE)
                val constructorType = Type.getType(desc)
                val params = storeLocals(constructorType.argumentTypes)
                copyLocal(objectLocal)
                params.forEach { loadLocal(it) }
                super.visitMethodInsn(opcode, owner, name, desc, itf)
                invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                loadLocal(objectLocal)
                push(owner.toCanonicalClassName())
                invokeStatic(Injections::afterObjectConstructor)
            }
        )
    }

    private fun GeneratorAdapter.invokeAfterConstructorForCurrentThis(
        opcode: Int,
        owner: String,
        name: String,
        desc: String,
        itf: Boolean
    ) {
        invokeIfInAnalyzedCode(
            original = {
                super.visitMethodInsn(opcode, owner, name, desc, itf)
            },
            instrumented = {
                super.visitMethodInsn(opcode, owner, name, desc, itf)
                invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                loadThis()
                push(owner.toCanonicalClassName())
                invokeStatic(Injections::afterObjectConstructor)
            }
        )
    }

    /**
     * Returns true when the receiver of the constructor call being visited is
     * [UNINITIALIZED_THIS], i.e. this is a `super()` or `this()` delegation call
     * inside the current constructor body.
     */
    private fun isReceiverUninitializedThis(desc: String): Boolean {
        if (methodName != "<init>") return false
        val stack = typeAnalyzer?.stack ?: return false
        val argSlots = Type.getArgumentTypes(desc).sumOf { it.size }
        return stack.getStackElementAt(argSlots) == UNINITIALIZED_THIS
    }


    override fun visitIntInsn(opcode: Int, operand: Int) = adapter.run {
        super.visitIntInsn(opcode, operand)
        if (opcode == NEWARRAY) {
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    dup()
                    val arrayLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                    invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                    loadLocal(arrayLocal)
                    invokeStatic(Injections::afterNewObjectCreation)
                }
            )
        }
    }

    override fun visitTypeInsn(opcode: Int, type: String) = adapter.run {
        if (opcode == NEW) {
            // TODO: We always instrument allocation here, including allocations of immutable values
            //   (e.g. `String`s, boxed primitives) that may be filtered out at runtime
            //   by `ObjectTracker.shouldTrackObject`.
            //   Consider adding a `TransformationConfiguration` flag
            //   to skip instrumenting allocations of immutable types,
            //   to avoid the runtime overhead when immutable-value tracking is disabled.
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                    push(type.toCanonicalClassName())
                    invokeStatic(Injections::beforeNewObjectCreation)
                }
            )
            uninitializedObjects++
        }
        super.visitTypeInsn(opcode, type)
        if (opcode == ANEWARRAY) {
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    dup()
                    val arrayLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                    invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                    loadLocal(arrayLocal)
                    invokeStatic(Injections::afterNewObjectCreation)
                }
            )
        }
    }

    override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) = adapter.run {
        super.visitMultiANewArrayInsn(descriptor, numDimensions)
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                dup()
                val arrayLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                loadLocal(arrayLocal)
                invokeStatic(Injections::afterNewObjectCreation)
            }
        )
    }

    /*
     * In addition to `NEW`/`NEWARRAY`/`ANEWARRAY`/`MULTIANEWARRAY`, the JVM has
     * a fifth, less obvious allocation site we have to recognize: `invokedynamic`.
     *
     * For a lambda expression, the user-visible bytecode contains only an
     * `invokedynamic` instruction whose bootstrap is `LambdaMetafactory.metafactory` (or `altMetafactory`).
     * The actual `NEW` for the lambda instance never
     * appears in the instrumented program — it lives inside a JVM-spun synthetic
     * proxy class (`Foo$$Lambda$NN/0x...`), produced at runtime by `InnerClassLambdaMetafactory`.
     *
     * That proxy is unreachable for our agent:
     *  - its name contains `$$Lambda`, so it is filtered out by
     *    `LincheckInstrumentation.canRetransformClass`;
     *  - on JDK 15+ it is a hidden class, which is not modifiable
     *    (`Instrumentation.isModifiableClass` returns `false`);
     *  - the surrounding `java.lang.invoke.MethodHandle*` machinery is wrapped in ignored sections
     *    (see `TransformationProfile.shouldWrapInIgnoredSection` and `isIgnoredMethodHandleMethod`).
     *
     * As a result, `NEW`-only instrumentation cannot observe the lambda being allocated
     *
     * The cleanest place to hook this allocation is the `invokedynamic` call site itself:
     * the freshly allocated object is left on the operand stack
     * as the instruction's result, so we can `dup` it and feed it to
     * `afterNewObjectCreation` similarly to arrays.
     *
     * One subtlety: a non-capturing lambda's call site target returns a JVM-cached singleton,
     * so the same instance shows up on the stack each time the `invokedynamic` is executed.
     * To avoid registering it more than once,
     * we route this site through the dedicated `afterInvokeDynamicObjectCreation` hook
     * rather than the regular `afterNewObjectCreation`.
     * The other, "normal" allocation sites (`NEW`/`NEWARRAY`/`ANEWARRAY`/`MULTIANEWARRAY`)
     * are guaranteed to produce a fresh instance per execution and use the plain `afterNewObjectCreation`.
     * As such, at runtime, the implementation of `afterInvokeDynamicObjectCreation` injection should be idempotent,
     * while implementation of `afterNewObjectCreation` is not obligatory idempotent.
     *
     * References:
     *  - JVMS §6.5 invokedynamic — describes how the bootstrap method's
     *    result (a `CallSite`) is invoked and pushes the produced object onto the stack:
     *    https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-6.html#jvms-6.5.invokedynamic
     *  - JEP 309: Dynamic class-file constants — https://openjdk.org/jeps/309
     *  - LambdaMetafactory javadoc:
     *    https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/LambdaMetafactory.html
     */
    override fun visitInvokeDynamicInsn(
        name: String?,
        descriptor: String?,
        bootstrapMethodHandle: Handle?,
        vararg bootstrapMethodArguments: Any?
    ) = adapter.run {
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                if (isObjectCreatingBootstrapMethod(bootstrapMethodHandle?.owner)) {
                    dup()
                    val objectLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                    invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                    loadLocal(objectLocal)
                    invokeStatic(Injections::afterInvokeDynamicObjectCreation)
                }
            }
        )
    }

    /**
     * Tests if the bootstrap method handle owner [bootstrapMethodOwner]
     * (in JVM internal form, e.g. `"java/lang/invoke/LambdaMetafactory"`)
     * corresponds to a bootstrap factory whose call sites allocate
     * a fresh object instance that Lincheck must register as a tracked allocation.
     *
     * Currently, this matches:
     *
     *  - `java.lang.invoke.LambdaMetafactory` — covers both `metafactory` and
     *    `altMetafactory` (the latter is used by the Java compiler for `Serializable` lambdas,
     *    multi-interface lambdas, and lambdas with extra bridge methods).
     *
     *  - `java.lang.invoke.StringConcatFactory` — covers `makeConcat` and `makeConcatWithConstants`,
     *    both of which produce a fresh `String` on every invocation.
     *
     * Other JDK bootstrap factories are intentionally not matched here:
     *  - `java.lang.runtime.ObjectMethods` (records) and `java.lang.runtime.SwitchBootstraps` (pattern switch)
     *     return cached MethodHandle`s;
     *  - `java.lang.invoke.ConstantBootstraps` returns constants.
     *
     * None of these need the same "register as NEW" treatment.
     */
    private fun isObjectCreatingBootstrapMethod(bootstrapMethodOwner: String?): Boolean =
        bootstrapMethodOwner == "java/lang/invoke/LambdaMetafactory" ||

        // TODO: We always instrument allocation of concatenated strings,
        //   even though tracking of immutable values may be filtered out at runtime
        //   by `ObjectTracker.shouldTrackObject`.
        //   Consider adding a `TransformationConfiguration` flag
        //   to skip instrumenting allocations of immutable types,
        //   to avoid the runtime overhead when immutable-value tracking is disabled.
        bootstrapMethodOwner == "java/lang/invoke/StringConcatFactory"

}
