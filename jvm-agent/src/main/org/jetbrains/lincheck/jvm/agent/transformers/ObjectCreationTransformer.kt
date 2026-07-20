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
import org.objectweb.asm.commons.Method
import java.lang.StringBuilder
import kotlin.reflect.KFunction

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

    /* To track object creation, this transformer inserts `Injections::afterObjectConstructor`
     * after each object constructor invocation, and right after array allocation
     * (where the array is already in initialized state and can be treated as a
     * one-shot "constructed object" — see `afterArrayCreation` below).
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

        if(opcode == INVOKESTATIC && owner == "java/lang/reflect/Array" && name == "newInstance") {
            visitInvokeArrayNewInstance(opcode, owner, name, desc, itf)
            return
        }

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
        val constructorType = Type.getType(desc)
        // Fast-path: zero-argument constructor.
        // Covers `java/lang/Object.<init>` (always no-arg) and every other no-arg `<init>` call.
        // We can keep the uninitialized receiver on the operand stack with a single `dup` instead of
        // allocating a local and threading constructor arguments through `storeLocals`/`loadLocal`.
        val isZeroArg = constructorType.argumentTypes.isEmpty()
        invokeIfInAnalyzedCode(
            original = {
                super.visitMethodInsn(opcode, owner, name, desc, itf)
            },
            instrumented = {
                if (isZeroArg) {
                    // Stack before: ..., uninitObj, uninitObj  (from user's NEW + DUP)
                    dup()                                     // ..., uninitObj, uninitObj, uninitObj
                    super.visitMethodInsn(opcode, owner, name, desc, itf)
                                                              // ..., obj, obj   (all uninit refs promoted)
                    invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                                                              // ..., obj, obj, descriptor
                    swap()                                    // ..., obj, descriptor, obj
                    push(owner.toCanonicalClassName())        // ..., obj, descriptor, obj, className
                    invokeStatic(Injections::afterObjectConstructor)
                                                              // ..., obj
                } else {
                    val objectLocal = newLocal(OBJECT_TYPE)
                    val params = storeLocals(constructorType.argumentTypes)
                    copyLocal(objectLocal)
                    params.forEach { loadLocal(it) }
                    super.visitMethodInsn(opcode, owner, name, desc, itf)
                    invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                    loadLocal(objectLocal)
                    push(owner.toCanonicalClassName())
                    invokeStatic(Injections::afterObjectConstructor)
                }
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
            afterArrayCreation(newArrayDescriptor(operand))
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
            // `type` is either an internal object name (e.g. `java/lang/String`) or, when the
            // element is itself an array, an array descriptor (e.g. `[I`).
            val elementDescriptor = if (type.startsWith("[")) type else "L$type;"
            afterArrayCreation("[$elementDescriptor")
        }
    }

    override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) = adapter.run {
        super.visitMultiANewArrayInsn(descriptor, numDimensions)
        afterArrayCreation(descriptor!!)
    }

    /**
     * Injects an `afterObjectConstructor` call right after an array allocation;
     * handles NEWARRAY/ANEWARRAY/MULTIANEWARRAY instructions.
     *
     * Unlike `NEW`, the array is already in a fully-initialized state on top of the stack,
     * so we just `dup` it and feed the duplicate into the injection.
     * The `arrayTypeDescriptor` is the JVM array descriptor (e.g. `[I`, `[Ljava/lang/String;`),
     * converted to a canonical Java name (`int[]`, `java.lang.String[]`) for the `className` argument.
     */
    private fun GeneratorAdapter.afterArrayCreation(arrayTypeDescriptor: String) {
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                dup()
                val arrayLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                loadLocal(arrayLocal)
                push(Type.getType(arrayTypeDescriptor).className)
                invokeStatic(Injections::afterObjectConstructor)
            }
        )
    }

    /** JVM array descriptor for the primitive type encoded by a NEWARRAY operand. */
    private fun newArrayDescriptor(operand: Int): String = when (operand) {
        T_BOOLEAN -> "[Z"
        T_CHAR    -> "[C"
        T_FLOAT   -> "[F"
        T_DOUBLE  -> "[D"
        T_BYTE    -> "[B"
        T_SHORT   -> "[S"
        T_INT     -> "[I"
        T_LONG    -> "[J"
        else -> error("Unknown NEWARRAY operand: $operand")
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
     * `afterInvokeDynamicObjectCreation` similarly to arrays' `afterObjectConstructor`.
     *
     * One subtlety: a non-capturing lambda's call site target returns a JVM-cached singleton,
     * so the same instance shows up on the stack each time the `invokedynamic` is executed.
     * To avoid registering it more than once,
     * we route this site through the dedicated `afterInvokeDynamicObjectCreation` hook
     * rather than the regular `afterObjectConstructor`.
     * The other, "normal" allocation sites (`NEW`/`NEWARRAY`/`ANEWARRAY`/`MULTIANEWARRAY`)
     * are guaranteed to produce a fresh instance per execution and use the plain `afterObjectConstructor`.
     * As such, at runtime, the implementation of `afterInvokeDynamicObjectCreation` injection should be idempotent.
     * Note that implementation of `afterObjectConstructor` generally should also be idempotent
     * with respect to the same object parameter passed to the injected function,
     * since multiple `<init>` calls may be made on the same instance through inheritance chains.
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


    private fun visitInvokeArrayNewInstance(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) = adapter.run {
        // TODO: should also call beforeNewObjectCreation?
        // STACK: elementClass, length
        invokeIfInAnalyzedCode(
            original = {
                visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            },
            instrumented = {
                // STACK: elementClass, length
                swap()
                // STACK: length, elementClass
                dup()
                // STACK: length, elementClass, elementClass
                val elementClass = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                // STACK: length, elementClass
                swap()
                // STACK: elementClass, length
                visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                // STACK: array
                dup()
                // STACK: array, array
                invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                // STACK: array, array, descriptor
                swap()
                loadLocal(elementClass)
                // STACK: array, array, descriptor, elementClass
                getElementTypeNameFromClass()
                // STACK: array, descriptor, array, elementName
                invokeStatic(Injections::afterObjectConstructor)
                // STACK: array
            }
        )
    }

    /**
     * Converts the class of the array elements into the canonical java string represetnation such that
     * it can be used by [Injections::afterObjectConstructor]
     * NOTE: Probably could be written as a regular method.
     *
     * Stack beore: ..., elementClass : Class<*>
     * Stack after: ..., elementClassName : String
     */
    private fun GeneratorAdapter.getElementTypeNameFromClass() {
        val getTypeFun: (Class<*>) -> Type = Type::getType
        val sbType = Type.getType(java.lang.StringBuilder::class.java)
        val typeType = Type.getType(Type::class.java)
        val sbConstructor = Method.getMethod(java.lang.StringBuilder::class.java.getDeclaredConstructor())
        val appendMethod = Method.getMethod(java.lang.StringBuilder::class.java.getDeclaredMethod("append", String::class.java))
        val toStringMethod = Method.getMethod(StringBuilder::class.java.getDeclaredMethod("toString"))
        val getClassNameMethod = Method.getMethod(Type::class.java.getDeclaredMethod("getClassName"))

        // elementClass
        invokeStatic(getTypeFun as KFunction<*>)
        // elementType
        // NOTE: we use StringBuilder for Java8 compatibility
        newInstance(sbType)
        dup()
        // elementType, stringBuilder
        invokeConstructor(sbType, sbConstructor)
        // elementType, stringBuilder
        swap()
        // stringBuilder, elementType
        // NOTE: getClassName handles the annoying part where arrays elements are handled differently,
        //   than all the other types
        invokeVirtual(typeType, getClassNameMethod)
        // stringBuilder, elementString
        invokeVirtual(sbType, appendMethod)
        // stringBuilder
        push("[]")
        // stringBuilder, "[]"
        invokeVirtual(sbType, appendMethod)
        // stringBuilder,
        invokeVirtual(sbType, toStringMethod)
        // name
    }

}
