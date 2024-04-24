/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.objectweb.asm.Type
import org.objectweb.asm.Type.INT_TYPE
import org.objectweb.asm.Type.LONG_TYPE
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.Injections

internal open class AtomicPrimitiveMethodTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    /**
     * Process methods like *.set(receiver, value)
     */
    protected fun processSetFieldMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        // STACK: receiver, value
        val argumentTypes = Type.getArgumentTypes(desc)
        val argumentLocals = copyLocals(
            valueTypes = argumentTypes,
            localTypes = arrayOf(OBJECT_TYPE, OBJECT_TYPE),
        )
        // STACK: receiver, value
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: <empty>
        loadLocals(argumentLocals)
        // STACK: boxedValue, receiver
        invokeStatic(Injections::onWriteToObjectFieldOrArrayCell)
    }

    /**
     * Process methods like *.set(receiver, index, value)
     */
    protected fun processSetArrayElementMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        val argumentTypes = Type.getArgumentTypes(desc)
        val argumentType = argumentTypes.last()
        val valueLocal = newLocal(OBJECT_TYPE)
        val indexLocal = newLocal(INT_TYPE)
        val receiverLocal = newLocal(OBJECT_TYPE)
        // STACK: value, index, receiver
        box(argumentType)
        // STACK: boxedValue, index, receiver
        storeLocal(valueLocal)
        // STACK: index, receiver
        storeLocal(indexLocal)
        // STACK: receiver
        storeLocal(receiverLocal)
        // STACK: <empty>
        loadLocal(receiverLocal)
        // STACK: receiver
        loadLocal(indexLocal)
        // STACK: index, receiver
        loadLocal(valueLocal)
        // STACK: boxedValue, index, receiver
        unbox(argumentType)
        // STACK: value, index, receiver
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: <empty>
        loadLocal(receiverLocal)
        // STACK: receiver
        loadLocal(valueLocal)
        // STACK: boxedValue, receiver
        invokeStatic(Injections::onWriteToObjectFieldOrArrayCell)
    }

    /**
     * Process methods like *.compareAndSet(receiver, expected, desired)
     */
    protected fun processCompareAndSetFieldMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        val argumentTypes = Type.getArgumentTypes(desc)
        val nextType = argumentTypes.last()
        val nextValueLocal = newLocal(OBJECT_TYPE)
        val currentType = argumentTypes[argumentTypes.lastIndex - 1]
        val currenValueLocal = newLocal(OBJECT_TYPE)
        val receiverLocal = newLocal(OBJECT_TYPE)
        // STACK: nextValue, currentValue, receiver
        box(nextType)
        // STACK: boxedNextValue, currentValue, receiver
        storeLocal(nextValueLocal)
        // STACK: currentValue, receiver
        box(currentType)
        // STACK: boxedCurrentValue, receiver
        storeLocal(currenValueLocal)
        // STACK: receiver
        storeLocal(receiverLocal)
        // STACK: <empty>
        loadLocal(receiverLocal)
        // STACK: receiver
        loadLocal(currenValueLocal)
        // STACK: boxedCurrentValue, receiver
        unbox(currentType)
        // STACK: currentValue, receiver
        loadLocal(nextValueLocal)
        // STACK: boxedNextValue, currentValue, receiver
        unbox(nextType)
        // STACK: nextValue, currentValue, receiver
        visitMethodInsn(opcode, owner, name, desc, itf)
        loadLocal(receiverLocal)
        // STACK: receiver
        loadLocal(nextValueLocal)
        // STACK: boxedNextValue, receiver
        invokeStatic(Injections::onWriteToObjectFieldOrArrayCell)
    }

    /**
     * Process methods like *.compareAndSet(receiver, index, expected, desired)
     */
    protected fun processCompareAndSetArrayElementMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        val argumentTypes = Type.getArgumentTypes(desc)
        val nextType = argumentTypes.last()
        val nextValueLocal = newLocal(OBJECT_TYPE)
        val currentType = argumentTypes[argumentTypes.lastIndex - 1]
        val currenValueLocal = newLocal(OBJECT_TYPE)
        val indexLocal = newLocal(INT_TYPE)
        val receiverLocal = newLocal(OBJECT_TYPE)
        // STACK: nextValue, currentValue, index, receiver
        box(nextType)
        // STACK: boxedNextValue, currentValue, index, receiver
        storeLocal(nextValueLocal)
        // STACK: currentValue, index, receiver
        box(currentType)
        // STACK: boxedCurrentValue, index, receiver
        storeLocal(currenValueLocal)
        // STACK: index, receiver
        storeLocal(indexLocal)
        // STACK: receiver
        storeLocal(receiverLocal)
        // STACK: <empty>
        loadLocal(receiverLocal)
        // STACK: receiver
        loadLocal(indexLocal)
        // STACK: index, receiver
        loadLocal(currenValueLocal)
        // STACK: boxedCurrentValue, index, receiver
        unbox(currentType)
        // STACK: currentValue, index, receiver
        loadLocal(nextValueLocal)
        // STACK: boxedNextValue, currentValue, index, receiver
        unbox(nextType)
        // STACK: nextValue, currentValue, index, receiver
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: cas-result
        loadLocal(receiverLocal)
        // STACK: receiver, cas-result
        loadLocal(nextValueLocal)
        // STACK: boxedNextValue, receiver, cas-result
        invokeStatic(Injections::onWriteToObjectFieldOrArrayCell)
    }

    /**
     * Process methods like *.compareAndSet(receiver, offset, expected, desired)
     */
    protected fun processCompareAndSetByOffsetMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        val nextValueLocal = newLocal(OBJECT_TYPE)
        val currenValueLocal = newLocal(OBJECT_TYPE)
        val offsetLocal = newLocal(LONG_TYPE)
        val receiverLocal = newLocal(OBJECT_TYPE)
        // STACK: nextValue, currentValue, offset, receiver
        storeLocal(nextValueLocal)
        // STACK: currentValue, offset, receiver
        storeLocal(currenValueLocal)
        // STACK: offset, receiver
        storeLocal(offsetLocal)
        // STACK: receiver
        storeLocal(receiverLocal)
        // STACK: <empty>
        loadLocal(receiverLocal)
        // STACK: receiver
        loadLocal(offsetLocal)
        // STACK: offset, receiver
        loadLocal(currenValueLocal)
        // STACK: currentValue, offset, receiver
        loadLocal(nextValueLocal)
        // STACK: nextValue, currentValue, offset, receiver
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: cas-result
        loadLocal(receiverLocal)
        // STACK: receiver, cas-result
        loadLocal(nextValueLocal)
        // STACK: nextValue, receiver, cas-result
        invokeStatic(Injections::onWriteToObjectFieldOrArrayCell)
    }

    /**
     * Process methods like *.getAndSet(receiver, offset, expected, desired)
     */
    protected fun processGetAndSetByOffsetMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        val nextValueLocal = newLocal(OBJECT_TYPE)
        val offsetLocal = newLocal(LONG_TYPE)
        val receiverLocal = newLocal(OBJECT_TYPE)
        // STACK: nextValue, offset, receiver
        storeLocal(nextValueLocal)
        // STACK: offset, receiver
        storeLocal(offsetLocal)
        // STACK: receiver
        storeLocal(receiverLocal)
        // STACK: <empty>
        loadLocal(receiverLocal)
        // STACK: receiver
        loadLocal(offsetLocal)
        // STACK: offset, receiver
        loadLocal(nextValueLocal)
        // STACK: nextValue, offset, receiver
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: <empty>
        loadLocal(receiverLocal)
        // STACK: receiver
        loadLocal(nextValueLocal)
        // STACK: nextValue, receiver
        invokeStatic(Injections::onWriteToObjectFieldOrArrayCell)
    }

}

internal class AtomicFieldUpdaterMethodTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : AtomicPrimitiveMethodTransformer(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        // TODO: handle other field updaters
        if (owner != "java/util/concurrent/atomic/AtomicReferenceFieldUpdater") {
            visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        invokeIfInTestingCode(
            original = {
                visitMethodInsn(opcode, owner, name, desc, itf)
            },
            code = {
                when (name) {
                    // TODO: getAndSet should be handled separately
                    "set", "lazySet", "getAndSet" -> {
                        processSetFieldMethod(name, opcode, owner, desc, itf)
                    }
                    "compareAndSet", "weakCompareAndSet" -> {
                        processCompareAndSetFieldMethod(name, opcode, owner, desc, itf)
                    }
                    else -> {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    }
                }
            }
        )
    }

}

internal class VarHandleMethodTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : AtomicPrimitiveMethodTransformer(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (owner != "java/lang/invoke/VarHandle") {
            visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        invokeIfInTestingCode(
            original = {
                visitMethodInsn(opcode, owner, name, desc, itf)
            },
            code = {
                val argumentCount = Type.getArgumentCount(desc)
                when (name) {
                    // TODO: getAndSet should be handled separately
                    "set", "setVolatile", "setRelease", "setOpaque", "getAndSet" -> when (argumentCount) {
                        2 -> processSetFieldMethod(name, opcode, owner, desc, itf)
                        3 -> processSetArrayElementMethod(name, opcode, owner, desc, itf)
                        else -> throw IllegalStateException()
                    }
                    "compareAndSet",  "weakCompareAndSet",
                    "weakCompareAndSetRelease", "weakCompareAndSetAcquire", "weakCompareAndSetPlain",
                    "compareAndExchange", "compareAndExchangeAcquire", "compareAndExchangeRelease" -> when (argumentCount) {
                        3 -> processCompareAndSetFieldMethod(name, opcode, owner, desc, itf)
                        4 -> processCompareAndSetArrayElementMethod(name, opcode, owner, desc, itf)
                        else -> throw IllegalStateException()
                    }
                    else -> {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    }
                }
            }
        )
    }

}

internal class UnsafeMethodTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : AtomicPrimitiveMethodTransformer(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (owner != "sun/misc/Unsafe" && owner != "jdk/internal/misc/Unsafe") {
            visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        invokeIfInTestingCode(
            original = {
                visitMethodInsn(opcode, owner, name, desc, itf)
            },
            code = {
                when (name) {
                    "compareAndSwapObject" -> {
                        processCompareAndSetByOffsetMethod(name, opcode, owner, desc, itf)
                    }
                    "getAndSetObject" -> {
                        processGetAndSetByOffsetMethod(name, opcode, owner, desc, itf)
                    }
                    else -> {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    }
                }
            }
        )
    }

}