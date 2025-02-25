/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers.native_calls

import org.jetbrains.kotlinx.lincheck.transformation.ifStatement
import org.jetbrains.kotlinx.lincheck.transformation.invokeInIgnoredSection
import org.jetbrains.kotlinx.lincheck.transformation.invokeStatic
import org.jetbrains.kotlinx.lincheck.transformation.storeArguments
import org.jetbrains.kotlinx.lincheck.transformation.tryCatchFinally
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.Type.VOID_TYPE
import org.objectweb.asm.Type.getType
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.TraceDebuggerTracker

/**
 * A typealias for defining a builder function that operates on a [GeneratorAdapter] instance.
 * It provides a DSL-style mechanism to configure or modify a [GeneratorAdapter].
 */
internal typealias GeneratorBuilder = GeneratorAdapter.() -> Unit

/**
 * Represents a deterministic call interface, used to describe invocations of specific methods
 * in a way that abstracts details about the method’s owner, name, descriptor, and stateful interaction.
 */
internal interface DeterministicCall {
    val opcode: Int
    val owner: String
    val name: String
    val desc: String
    val isInterface: Boolean
    val stateType: Type
    
    /**
     * Invokes a method call described by a deterministic call using when a state is given.
     *
     * @param generator The generator adapter used to emit bytecode for the method invocation.
     * @param getState A builder that puts the current state of the deterministic call on the stack.
     * @param getReceiver A builder that puts the receiver object on the stack for the method call, or null if the method is static.
     * @param getArgument A lambda function to put the method argument at index `index` on the stack.
     */
    fun invokeFromState(
        generator: GeneratorAdapter, getState: GeneratorBuilder, getReceiver: GeneratorBuilder?,
        getArgument: GeneratorAdapter.(index: Int) -> Unit,
    )

    /**
     * Invokes a method call while saving and managing the invocation state.
     *
     * @param generator The generator adapter used to emit bytecode for the method invocation.
     * @param saveState A lambda function that saves the current state of the deterministic call,
     *                  accepting a builder that puts the current state on the stack.
     *                  **It must be called exactly once per call.**
     * @param getReceiver A builder that places the receiver object on the stack for the method call,
     *                    or null if the method is static.
     * @param getArgument A lambda function to place the method argument at the specified index
     *                    on the stack.
     */
    fun invokeSavingState(
        generator: GeneratorAdapter,
        saveState: GeneratorAdapter.(getState: GeneratorBuilder) -> Unit,
        getReceiver: GeneratorBuilder?,
        getArgument: GeneratorAdapter.(index: Int) -> Unit,
    )
}


/**
 * Invokes the original method call described by the `DeterministicCall` without managing state.
 *
 * @param generator The generator adapter used to emit bytecode for the method invocation.
 * @param getReceiver A builder that places the receiver object on the stack for the method call,
 *                    or null if the method is static.
 * @param getArgument A lambda function to place the method argument at the specified index on the stack.
 */
internal fun DeterministicCall.invokeOriginalCall(
    generator: GeneratorAdapter,
    getReceiver: GeneratorBuilder?,
    getArgument: GeneratorAdapter.(index: Int) -> Unit,
) {
    if (getReceiver != null) getReceiver(generator)
    Type.getArgumentTypes(desc).forEachIndexed { index, _ -> generator.getArgument(index) }
    generator.visitMethodInsn(opcode, owner, name, desc, isInterface)
}

/**
 * Invokes a deterministic call, managing state preservation, state restoration,
 * and specific pre- and post-call actions, such as entering ignored sections
 * and interacting with trace debugging events.
 *
 * @param call The deterministic call description containing method details and state type information.
 * @param getReceiver A lambda function to generate the receiver object for the method call, or null if the method is static.
 * @param getArgument A lambda function to generate the argument at the specified index for the method call.
 */
internal fun GeneratorAdapter.invoke(
    call: DeterministicCall,
    getReceiver: GeneratorBuilder?,
    getArgument: GeneratorAdapter.(index: Int) -> Unit,
) {
    require(call.stateType != VOID_TYPE) { "Cannot have state of the void type" }
    invokeInIgnoredSection {
        getStatic(traceDebuggerTrackerType, TraceDebuggerTracker.NativeMethodCall.name, traceDebuggerTrackerType)
        invokeStatic(Injections::getNextTraceDebuggerEventTrackerId)
        val id = newLocal(Type.LONG_TYPE)
        storeLocal(id)
        loadLocal(id)
        push(call.opcode)
        push(call.owner)
        push(call.name)
        push(call.desc)
        push(call.isInterface)
        invokeStatic(Injections::getNativeCallStateOrNull)
        dup()
        val nonNull = newLabel()
        val end = newLabel()
        ifNonNull(nonNull)
        pop()
        var saveSateInvocationCount = 0
        call.invokeSavingState(
            generator = this,
            saveState = { getState ->
                saveSateInvocationCount++
                loadLocal(id)
                getState()
                box(call.stateType)
                push(call.opcode)
                push(call.owner)
                push(call.name)
                push(call.desc)
                push(call.isInterface)
                invokeStatic(Injections::setNativeCallState)
            },
            getReceiver = getReceiver,
            getArgument = getArgument,
        )
        require(saveSateInvocationCount == 1) {
            """
                |Deterministic call state was not saved or restored correctly:
                |It was called $saveSateInvocationCount times instead of once.
                |${call.owner}.${call.name}${call.desc}""".trimMargin()
        }
        goTo(end)
        visitLabel(nonNull)
        // Stack [non-null Object (cached state)]
        unbox(call.stateType)
        val state = newLocal(call.stateType)
        storeLocal(state)
        call.invokeFromState(
            generator = this,
            getState = { loadLocal(state) },
            getReceiver = getReceiver,
            getArgument = getArgument,
        )
        visitLabel(end)
    }
}

/**
 * Invokes a deterministic call with the given parameters, handling receiver and argument preparation.
 *
 * @param call The deterministic call that encapsulates method details, including the owner, name,
 *             descriptor, and stateful interaction details.
 * @param receiver The index of the receiver object in the local variable table, or null if the method is static.
 * @param arguments An array of indices in the local variable table representing the arguments
 *                  to be passed to the method call.
 */
internal fun GeneratorAdapter.invoke(
    call: DeterministicCall,
    receiver: Int?,
    arguments: IntArray,
) {
    invoke(
        call = call,
        getReceiver = if (receiver != null) fun GeneratorAdapter.() { loadLocal(receiver) } else null,
        getArgument = { loadLocal(arguments[it]) }
    )
}

/**
 * Invokes a method described by a deterministic call while managing receiver and arguments.
 * 
 * It expects arguments to be placed on the stack.
 *
 * @param call The deterministic call containing method details such as the owner, name, descriptor,
 *             and invocation opcode.
 */
internal fun GeneratorAdapter.invoke(
    call: DeterministicCall,
) {
    val arguments = storeArguments(call.desc)
    val ownerType = getType("L${call.owner};")
    val receiver = when (call.opcode) {
        Opcodes.INVOKESTATIC -> null
        Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKEVIRTUAL -> newLocal(ownerType)
        else -> error("Unsupported opcode: ${call.opcode}")
    }
    if (receiver != null) storeLocal(receiver)
    invoke(call = call, receiver = receiver, arguments = arguments)
}

internal data class ThrowableWrapper(val throwable: Throwable) {
    companion object {
        @JvmStatic
        fun fromThrowable(throwable: Throwable) = ThrowableWrapper(throwable)

        @JvmStatic
        fun throwable(wrapper: ThrowableWrapper) = wrapper.throwable
    }
}

/**
 * Executes a code block within a try-catch construct and boxes the result into [Any] upon success.
 * If an exception occurs during the execution of the code block, it wraps the exception using
 * [ThrowableWrapper.fromThrowable].
 *
 * @param successType The result type of the [block] execution upon its success.
 * @param block The code block that will be executed within a try-catch construct.
 */
internal fun GeneratorAdapter.customRunCatching(successType: Type, block: GeneratorBuilder) {
    tryCatchFinally(
        tryBlock = {
            block()
            box(successType)
        },
        catchBlock = {
            invokeStatic(ThrowableWrapper::fromThrowable)
        },
    )
}

/**
 * Handles the evaluation of an object on the stack, either unwrapping it as a successful result or throwing it as an exception
 * if it represents a throwable. Specifically:
 * - If the object on the stack is an instance of `ThrowableWrapper`, the underlying Throwable is retrieved and thrown.
 * - If the object represents a successful result:
 *    - The value is unboxed if the `successType` is not void.
 *    - The value is discarded if the `successType` is void.
 *
 * @param successType the type representing the successful value that should be unboxed if present.
 */
fun GeneratorAdapter.customGetOrThrow(successType: Type) {
    dup()
    ifStatement(
        condition = { instanceOf(throwableWrapperType) },
        thenClause = {
            invokeStatic(ThrowableWrapper::throwable)
            throwException()
        },
        elseClause = {
            if (successType != VOID_TYPE) {
                unbox(successType)
            } else {
                pop()
            }
        },
    )
}

private val traceDebuggerTrackerType = getType(TraceDebuggerTracker::class.java)

private val throwableWrapperType = getType(ThrowableWrapper::class.java)

/**
 * Copies the content of a source array into a destination array using the `System.arraycopy` method.
 *
 * @param getSource A function that generates or retrieves the source array.
 * @param getDestination A function that generates or retrieves the destination array.
 */
internal fun GeneratorAdapter.copyArrayContent(
    getSource: GeneratorBuilder,
    getDestination: GeneratorBuilder,
) {
    getSource()
    push(0)
    getDestination()
    push(0)
    getSource()
    arrayLength()
    invokeStatic(System::arraycopy)
}

/**
 * Copies the contents of an existing array to a new array of the same type.
 *
 * @param elementType The type of elements in the array, used to create a new array of the same type.
 * @param getExistingArray A function that provides the existing array to copy from.
 * @return The local index of the newly created array containing the copied elements.
 */
internal fun GeneratorAdapter.arrayCopy(elementType: Type, getExistingArray: GeneratorBuilder): Int {
    getExistingArray()
    arrayLength()
    newArray(elementType)
    val copiedArray = newLocal(getType("[${elementType.descriptor}"))
    storeLocal(copiedArray)
    copyArrayContent(getSource = getExistingArray, getDestination = { loadLocal(copiedArray) })
    return copiedArray
}

/**
 * A [DeterministicCall] implementation that expects no side effects to be done to the parameters or anything else.
 * The only effect it expects is its return value which is used as a state.
 *
 * @property opcode The opcode of the method instruction.
 * @property owner The internal name of the class containing the method.
 * @property name The name of the method to be invoked.
 * @property desc The descriptor of the method to be invoked.
 * @property isInterface Boolean indicating whether the implementation is an interface method.
 */
internal data class PureDeterministicCall(
    override val opcode: Int,
    override val owner: String,
    override val name: String,
    override val desc: String,
    override val isInterface: Boolean,
) : DeterministicCall {
    override val stateType: Type = Type.getReturnType(desc)
    
    init {
        require(Type.getReturnType(desc) != VOID_TYPE) { "Pure deterministic native call must return a value" }
    }

    override fun invokeFromState(
        generator: GeneratorAdapter,
        getState: GeneratorBuilder,
        getReceiver: GeneratorBuilder?,
        getArgument: GeneratorAdapter.(Int) -> Unit,
    ) = generator.getState()

    override fun invokeSavingState(
        generator: GeneratorAdapter,
        saveState: GeneratorAdapter.(getState: GeneratorBuilder) -> Unit,
        getReceiver: GeneratorBuilder?,
        getArgument: GeneratorAdapter.(Int) -> Unit,
    ) = generator.run {
        invokeOriginalCall(this, getReceiver, getArgument)
        val result = newLocal(stateType)
        storeLocal(result)
        saveState { loadLocal(result) }
        loadLocal(result)
    }
}
