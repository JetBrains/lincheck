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

internal typealias GeneratorBuilder = GeneratorAdapter.() -> Unit

internal interface DeterministicCall {
    val opcode: Int
    val owner: String
    val name: String
    val desc: String
    val isInterface: Boolean
    val stateType: Type
    
    fun invokeFromState(
        generator: GeneratorAdapter, getState: GeneratorBuilder, getReceiver: GeneratorBuilder?,
        getArgument: GeneratorAdapter.(index: Int) -> Unit,
    )

    fun invokeSavingState(
        generator: GeneratorAdapter,
        saveState: GeneratorAdapter.(getState: GeneratorBuilder) -> Unit,
        getReceiver: GeneratorBuilder?,
        getArgument: GeneratorAdapter.(index: Int) -> Unit,
    )
}


internal fun DeterministicCall.invokeOriginalCall(
    generator: GeneratorAdapter,
    getReceiver: GeneratorBuilder?,
    getArgument: GeneratorAdapter.(index: Int) -> Unit,
) {
    if (getReceiver != null) getReceiver(generator)
    Type.getArgumentTypes(desc).forEachIndexed { index, _ -> generator.getArgument(index) }
    generator.visitMethodInsn(opcode, owner, name, desc, isInterface)
}

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
        invokeStatic(Injections::getNativeCallStateOrNull)
        dup()
        val nonNull = newLabel()
        val end = newLabel()
        ifNonNull(nonNull)
        pop()
        call.invokeSavingState(
            generator = this,
            saveState = { getState ->
                loadLocal(id)
                getState()
                box(call.stateType)
                invokeStatic(Injections::setNativeCallState)
            },
            getReceiver = getReceiver,
            getArgument = getArgument,
        )
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

internal data class ThrowableWrapper(val throwable: Throwable) {
    companion object {
        @JvmStatic
        fun fromThrowable(throwable: Throwable) = ThrowableWrapper(throwable)

        @JvmStatic
        fun throwable(wrapper: ThrowableWrapper) = wrapper.throwable
    }
}

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
fun GeneratorAdapter.copyArrayContent(
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

fun GeneratorAdapter.copyArray(elementType: Type, getExistingArray: GeneratorBuilder): Int {
    getExistingArray()
    arrayLength()
    newArray(elementType)
    val copiedArray = newLocal(getType("[${elementType.descriptor}"))
    storeLocal(copiedArray)
    copyArrayContent(getSource = getExistingArray, getDestination = { loadLocal(copiedArray) })
    return copiedArray
}

private val traceDebuggerTrackerType = getType(TraceDebuggerTracker::class.java)
private val throwableWrapperType = getType(ThrowableWrapper::class.java)

internal data class SimpleDeterministicCall(
    override val opcode: Int,
    override val owner: String,
    override val name: String,
    override val desc: String,
    override val isInterface: Boolean,
) : DeterministicCall {
    override val stateType: Type = Type.getReturnType(desc)

    override fun invokeFromState(
        generator: GeneratorAdapter,
        getState: GeneratorBuilder,
        getReceiver: GeneratorBuilder?,
        getArgument: GeneratorAdapter.(Int) -> Unit,
    ) = generator.run {
        getState()
    }

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
