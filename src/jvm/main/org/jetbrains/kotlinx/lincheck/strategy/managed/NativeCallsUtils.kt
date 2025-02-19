/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.transformation.ifStatement
import org.jetbrains.kotlinx.lincheck.transformation.invokeInIgnoredSection
import org.jetbrains.kotlinx.lincheck.transformation.invokeStatic
import org.jetbrains.kotlinx.lincheck.transformation.storeArguments
import org.jetbrains.kotlinx.lincheck.transformation.tryCatchFinally
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.Type.VOID_TYPE
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.TraceDebuggerTracker

internal interface DeterministicCall {
    fun invokeFromState(
        generator: GeneratorAdapter,
        getState: GeneratorAdapter.() -> Unit,
        getReceiver: (GeneratorAdapter.() -> Unit)?,
        getArgument: GeneratorAdapter.(index: Int) -> Unit,
    )

    fun invokeSavingState(
        generator: GeneratorAdapter,
        saveState: GeneratorAdapter.(getState: GeneratorAdapter.() -> Unit) -> Unit,
        getReceiver: (GeneratorAdapter.() -> Unit)?,
        getArgument: GeneratorAdapter.(index: Int) -> Unit,
    )
}

internal fun GeneratorAdapter.invoke(
    call: DeterministicCall,
    stateType: Type,
    getReceiver: (GeneratorAdapter.() -> Unit)?,
    getArgument: GeneratorAdapter.(index: Int) -> Unit,
) {
    require(stateType != VOID_TYPE) { "Cannot have state of the void type" }
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
                box(stateType)
                invokeStatic(Injections::setNativeCallState)
            },
            getReceiver = getReceiver,
            getArgument = getArgument,
        )
        goTo(end)
        visitLabel(nonNull)
        // Stack [non-null Object (cached state)]
        unbox(stateType)
        val state = newLocal(stateType)
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
    stateType: Type,
    opcode: Int,
    owner: String,
    methodDescriptor: String,
) {
    val arguments = storeArguments(methodDescriptor)
    val ownerType = Type.getType("L$owner;")
    val receiver = when (opcode) {
        Opcodes.INVOKESTATIC -> null
        Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKEVIRTUAL -> newLocal(ownerType)
        else -> error("Unsupported opcode: $opcode")
    }
    if (receiver != null) storeLocal(receiver)
    invoke(
        call = call,
        stateType = stateType,
        getReceiver = if (receiver != null) fun GeneratorAdapter.() { loadLocal(receiver) } else null,
        getArgument = { loadArg(arguments[it]) }
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

internal fun GeneratorAdapter.customRunCatching(successType: Type, block: GeneratorAdapter.() -> Unit) {
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

private val traceDebuggerTrackerType = Type.getType(TraceDebuggerTracker::class.java)
private val throwableWrapperType = Type.getType(ThrowableWrapper::class.java)
