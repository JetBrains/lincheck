/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers

import org.objectweb.asm.Type
import org.objectweb.asm.Type.INT_TYPE
import org.objectweb.asm.Type.LONG_TYPE
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import org.objectweb.asm.commons.GeneratorAdapter
import org.jetbrains.kotlinx.lincheck.transformation.*
import sun.nio.ch.lincheck.EventTracker
import sun.nio.ch.lincheck.Injections

/**
 * [ReflectiveSharedMemoryAccessTransformer] tracks shared memory accesses performed via `AtomicFieldUpdater` (AFU) API,
 * injecting invocations of corresponding [EventTracker] methods.
 *
 * In particular, [EventTracker.afterReflectiveSetter] method is injected to track changes
 * in the objects' graph topology caused by a write to a field through AFU.
 */
internal class AtomicFieldUpdaterMethodTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ReflectiveSharedMemoryAccessTransformer(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
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

/**
 * [VarHandleMethodTransformer] tracks shared memory accesses performed via `VarHandle` (VH) API,
 * injecting invocations of corresponding [EventTracker] methods.
 *
 * In particular, [EventTracker.afterReflectiveSetter] method is injected to track changes
 * in the object graph topology caused by a write to a field (or array element) through VH.
 */
internal class VarHandleMethodTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ReflectiveSharedMemoryAccessTransformer(fileName, className, methodName, adapter) {

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
                    "set", "setVolatile", "setRelease", "setOpaque",
                    "getAndSet", "getAndSetAcquire", "getAndSetRelease" -> when (argumentCount) {
                        1 -> processSetStaticFieldMethod(name, opcode, owner, desc, itf)
                        2 -> processSetFieldMethod(name, opcode, owner, desc, itf)
                        3 -> processSetArrayElementMethod(name, opcode, owner, desc, itf)
                        else -> throw IllegalStateException()
                    }
                    "compareAndSet", "weakCompareAndSet",
                    "weakCompareAndSetRelease", "weakCompareAndSetAcquire", "weakCompareAndSetPlain",
                    "compareAndExchange", "compareAndExchangeAcquire", "compareAndExchangeRelease" -> when (argumentCount) {
                        2 -> processCompareAndSetStaticFieldMethod(name, opcode, owner, desc, itf)
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

/**
 * [ReflectiveSharedMemoryAccessTransformer] tracks shared memory accesses performed via `Unsafe` API,
 * injecting invocations of corresponding [EventTracker] methods.
 *
 * In particular, [EventTracker.afterReflectiveSetter] method is injected to track changes
 * in the objects' graph topology caused by a write to a field (or array element) through Unsafe.
 */
internal class UnsafeMethodTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ReflectiveSharedMemoryAccessTransformer(fileName, className, methodName, adapter) {

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
                    "compareAndSwapObject",
                    "compareAndSetReference", "weakCompareAndSetReference",
                    "weakCompareAndSetReferenceRelease", "weakCompareAndSetReferenceAcquire", "weakCompareAndSetReferencePlain",
                    "compareAndExchangeReference", "compareAndExchangeReferenceAcquire", "compareAndExchangeReferenceRelease" -> {
                        processCompareAndSetByOffsetMethod(name, opcode, owner, desc, itf)
                    }
                    "putObject", "putObjectVolatile", "getAndSetObject",
                    "putReference", "putReferenceVolatile", "putReferenceRelease", "putReferenceOpaque",
                    "getAndSetReference", "getAndSetReferenceAcquire", "getAndSetReferenceRelease" -> {
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

internal open class ReflectiveSharedMemoryAccessTransformer(
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
        val boxedTypes = arrayOf(OBJECT_TYPE, OBJECT_TYPE)
        val argumentLocals = copyLocals(
            valueTypes = argumentTypes,
            localTypes = boxedTypes,
        )
        // STACK: receiver, value
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: <empty>
        loadLocals(argumentLocals, boxedTypes)
        // STACK: receiver, value
        invokeStatic(Injections::afterReflectiveSetter)
    }

    /**
     * Process methods like *.set(value)
     */
    protected fun processSetStaticFieldMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        // STACK: value
        val argumentTypes = Type.getArgumentTypes(desc)
        val boxedTypes = arrayOf(OBJECT_TYPE)
        val argumentLocals = copyLocals(
            valueTypes = argumentTypes,
            localTypes = boxedTypes,
        )
        // STACK: value
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: <empty>
        pushNull()
        loadLocals(argumentLocals, boxedTypes)
        // STACK: null, value
        invokeStatic(Injections::afterReflectiveSetter)
    }

    /**
     * Process methods like *.set(receiver, index, value)
     */
    protected fun processSetArrayElementMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        // STACK: receiver, index, value
        val argumentTypes = Type.getArgumentTypes(desc)
        val argumentLocals = copyLocals(
            valueTypes = argumentTypes,
            localTypes = arrayOf(OBJECT_TYPE, INT_TYPE, OBJECT_TYPE),
        )
        // STACK: receiver, index, value
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: <empty>
        loadLocal(argumentLocals[0])
        loadLocal(argumentLocals[2])
        // STACK: receiver, value
        invokeStatic(Injections::afterReflectiveSetter)
    }

    /**
     * Process methods like *.compareAndSet(receiver, expectedValue, desiredValue)
     */
    protected fun processCompareAndSetFieldMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        // STACK: receiver, expectedValue, newValue
        val argumentTypes = Type.getArgumentTypes(desc)
        val argumentLocals = copyLocals(
            valueTypes = argumentTypes,
            localTypes = arrayOf(OBJECT_TYPE, OBJECT_TYPE, OBJECT_TYPE),
        )
        // STACK: receiver, expectedValue, newValue
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: cas-result
        loadLocal(argumentLocals[0])
        loadLocal(argumentLocals[2])
        // STACK: cas-result, receiver, newValue
        invokeStatic(Injections::afterReflectiveSetter)
        // STACK: cas-result
    }

    /**
     * Process methods like *.compareAndSet(expectedValue, desiredValue)
     */
    protected fun processCompareAndSetStaticFieldMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        // STACK: expectedValue, newValue
        val argumentTypes = Type.getArgumentTypes(desc)
        val argumentLocals = copyLocals(
            valueTypes = argumentTypes,
            localTypes = arrayOf(OBJECT_TYPE, OBJECT_TYPE),
        )
        // STACK: expectedValue, newValue
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: cas-result
        pushNull()
        loadLocal(argumentLocals[1])
        // STACK: cas-result, null, newValue
        invokeStatic(Injections::afterReflectiveSetter)
        // STACK: cas-result
    }

    /**
     * Process methods like *.compareAndSet(receiver, index, expectedValue, desiredValue)
     */
    protected fun processCompareAndSetArrayElementMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        // STACK: receiver, index, expectedValue, newValue
        val argumentTypes = Type.getArgumentTypes(desc)
        val argumentLocals = copyLocals(
            valueTypes = argumentTypes,
            localTypes = arrayOf(OBJECT_TYPE, INT_TYPE, OBJECT_TYPE, OBJECT_TYPE),
        )
        // STACK: receiver, index, expectedValue, newValue
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: casResult
        loadLocal(argumentLocals[0])
        loadLocal(argumentLocals[3])
        // STACK: cas-result, receiver, newValue
        invokeStatic(Injections::afterReflectiveSetter)
        // STACK: cas-result
    }

    /**
     * Process methods like *.compareAndSet(receiver, offset, expectedValue, newValue)
     */
    protected fun processCompareAndSetByOffsetMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        // STACK: receiver, offset, expectedValue, newValue
        val argumentTypes = Type.getArgumentTypes(desc)
        val argumentLocals = copyLocals(
            valueTypes = argumentTypes,
            localTypes = arrayOf(OBJECT_TYPE, LONG_TYPE, OBJECT_TYPE, OBJECT_TYPE),
        )
        // STACK: receiver, offset, expectedValue, newValue
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: casResult
        loadLocal(argumentLocals[0])
        loadLocal(argumentLocals[3])
        // STACK: cas-result, receiver, nextValue
        invokeStatic(Injections::afterReflectiveSetter)
        // STACK: cas-result
    }

    /**
     * Process methods like *.getAndSet(receiver, offset, newValue)
     */
    protected fun processGetAndSetByOffsetMethod(name: String, opcode: Int, owner: String, desc: String, itf: Boolean) = adapter.run {
        // STACK: receiver, offset, newValue
        val argumentTypes = Type.getArgumentTypes(desc)
        val argumentLocals = copyLocals(
            valueTypes = argumentTypes,
            localTypes = arrayOf(OBJECT_TYPE, LONG_TYPE, OBJECT_TYPE),
        )
        // STACK: receiver, offset, newValue
        visitMethodInsn(opcode, owner, name, desc, itf)
        // STACK: oldValue
        loadLocal(argumentLocals[0])
        loadLocal(argumentLocals[2])
        // STACK: oldValue, receiver, newValue
        invokeStatic(Injections::afterReflectiveSetter)
        // STACK: oldValue
    }

}