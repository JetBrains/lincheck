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
     * Process methods like *.set(value)
     */
    protected fun processSetFieldMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        // STACK: value, receiver
        val argumentTypes = Type.getArgumentTypes(desc)
        val argumentType = argumentTypes.last()
        val valueLocal = newLocal(OBJECT_TYPE)
        val receiverLocal = newLocal(OBJECT_TYPE)
        // STACK: value, receiver
        box(argumentType)
        storeLocal(valueLocal)
        // STACK: boxedValue, receiver
        storeLocal(receiverLocal)
        // STACK: <empty>
        loadLocal(receiverLocal)
        // STACK: receiver
        loadLocal(valueLocal)
        // STACK: boxedValue, receiver
        unbox(argumentType)
        // STACK: value, receiver
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: <empty>
        loadLocal(receiverLocal)
        // STACK: receiver
        loadLocal(valueLocal)
        // STACK: boxedValue, receiver
        invokeStatic(Injections::onWriteToObjectFieldOrArrayCell)
    }

    /**
     * Process methods like *.set(index, value)
     */
    protected fun processSetArrayElementMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
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
     * Process methods like *.compareAndSet(expected, desired)
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
     * Process methods like *.compareAndSet(expected, desired)
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


