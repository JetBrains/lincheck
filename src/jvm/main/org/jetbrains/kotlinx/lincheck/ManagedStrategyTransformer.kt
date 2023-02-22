/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */
package org.jetbrains.kotlinx.lincheck

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.commons.*
import org.objectweb.asm.commons.GeneratorAdapter.GT
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.CodeLocations
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.Injections.beforeReadArrayElement
import sun.nio.ch.lincheck.Injections.beforeReadField
import sun.nio.ch.lincheck.Injections.beforeReadFieldStatic
import sun.nio.ch.lincheck.Injections.beforeWriteArrayElement
import sun.nio.ch.lincheck.Injections.beforeWriteField
import sun.nio.ch.lincheck.Injections.beforeWriteFieldStatic
import sun.nio.ch.lincheck.Injections.deterministicHashCode
import sun.nio.ch.lincheck.Injections.deterministicRandom
import sun.nio.ch.lincheck.Injections.enterIgnoredSection
import sun.nio.ch.lincheck.Injections.inTestingCode
import sun.nio.ch.lincheck.Injections.leaveIgnoredSection
import sun.nio.ch.lincheck.Injections.lock
import sun.nio.ch.lincheck.Injections.nextInt
import sun.nio.ch.lincheck.Injections.notify
import sun.nio.ch.lincheck.Injections.notifyAll
import sun.nio.ch.lincheck.Injections.onNewAtomicFieldUpdater
import sun.nio.ch.lincheck.Injections.park
import sun.nio.ch.lincheck.Injections.storeCancellableContinuation
import sun.nio.ch.lincheck.Injections.unlock
import sun.nio.ch.lincheck.Injections.unpark
import sun.nio.ch.lincheck.Injections.wait
import sun.nio.ch.lincheck.Injections.waitWithTimeout
import java.util.*
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

private fun GeneratorAdapter.invokeStatic(function: KFunction<*>) {
    function.javaMethod!!.let {
        invokeStatic(Type.getType(it.declaringClass), Method.getMethod(it))
    }
}

private fun GeneratorAdapter.invokeVirtual(function: KFunction<*>) {
    function.javaMethod!!.let {
        invokeVirtual(Type.getType(it.declaringClass), Method.getMethod(it))
    }
}

private inline fun GeneratorAdapter.invokeIfInTestingCode(
    original: GeneratorAdapter.() -> Unit,
    code: GeneratorAdapter.() -> Unit
) {
    val codeStart = newLabel()
    val end = newLabel()
    invokeStatic(::inTestingCode)
    ifZCmp(GT, codeStart)
    original()
    goTo(end)
    visitLabel(codeStart)
    code()
    visitLabel(end)
}

/**
 * This transformer inserts [ManagedStrategy] methods invocations.
 */
internal class ManagedStrategyTransformer(
    cv: ClassVisitor?
) : ClassVisitor(ASM_API, cv) {
    private lateinit var className: String
    private var classVersion = 0
    private var fileName: String? = null

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String,
        interfaces: Array<String>
    ) {
        className = name
        classVersion = version
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitSource(source: String, debug: String?) {
        fileName = source
        super.visitSource(source, debug)
    }

    override fun visitMethod(
        access: Int,
        mname: String,
        desc: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor {
        if (access and ACC_NATIVE != 0)
            return super.visitMethod(access, mname, desc, signature, exceptions)
        var mv = super.visitMethod(access, mname, desc, signature, exceptions)
        mv = JSRInlinerAdapter(mv, access, mname, desc, signature, exceptions)
        mv = TryCatchBlockSorter(mv, access, mname, desc, signature, exceptions)
        mv = CoroutineCancellabilitySupportMethodTransformer(mv, access, mname, desc)
        mv = MonitorEnterAndExitTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        if (access and ACC_SYNCHRONIZED != 0) {
            mv = SynchronizedMethodTransformer(mname, GeneratorAdapter(mv, access, mname, desc), access, classVersion)
        }
        mv = IgnoreClassInitializationTransformer(
            mname,
            GeneratorAdapter(mv, access, mname, desc)
        ) // TODO: implement in code instead
        mv = AFUTrackingTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = CallStackTraceLoggingTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = WaitNotifyTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = ParkUnparkTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
//        mv = LocalObjectManagingTransformer(mname, GeneratorAdapter(mv, access, mname, desc)) // TODO: implement in code instead
        if (mname != "<init>" && mname != "<clinit>") // TODO: fix me
            mv = SharedVariableAccessMethodTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = DetermenisticHashCodeTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = DeterministicTimeTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = DeterministicRandomTransformer(GeneratorAdapter(mv, access, mname, desc))
        return mv
    }

    private class CoroutineCancellabilitySupportMethodTransformer(
        mv: MethodVisitor,
        access: Int,
        methodName: String?,
        desc: String?
    ) : AdviceAdapter(ASM_API, mv, access, methodName, desc) {
        override fun visitMethodInsn(
            opcodeAndSource: Int,
            className: String?,
            methodName: String?,
            descriptor: String?,
            isInterface: Boolean
        ) {
            val isGetResult =
                ("kotlinx/coroutines/CancellableContinuation" == className || "kotlinx/coroutines/CancellableContinuationImpl" == className)
                        && "getResult" == methodName
            if (isGetResult) {
                dup()
                invokeStatic(::storeCancellableContinuation)
            }
            super.visitMethodInsn(opcodeAndSource, className, methodName, descriptor, isInterface)
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before monitorenter and monitorexit instructions
     */
    private inner class MonitorEnterAndExitTransformer(mname: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(mname, adapter) {
        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                MONITORENTER -> {
                    invokeIfInTestingCode(
                        original = { monitorEnter() },
                        code = {
                            loadNewCodeLocationId()
                            invokeStatic(::lock)
                        }
                    )
                }

                MONITOREXIT -> {
                    invokeIfInTestingCode(
                        original = { monitorExit() },
                        code = {
                            loadNewCodeLocationId()
                            invokeStatic(::unlock)
                        }
                    )
                }

                else -> visitInsn(opcode)
            }
        }
    }

    /**
     * Replace "method(...) {...}" with "method(...) {synchronized(this) {...} }"
     */
    private inner class SynchronizedMethodTransformer(
        methodName: String,
        mv: GeneratorAdapter,
        access: Int,
        private val classVersion: Int
    ) : ManagedStrategyMethodVisitor(methodName, mv) {
        private val isStatic: Boolean = access and ACC_STATIC != 0
        private val tryLabel = Label()
        private val catchLabel = Label()

        override fun visitCode() = adapter.run {
            invokeIfInTestingCode(
                original = {},
                code = {
                    monitorExit()
                    loadThis()
                    loadNewCodeLocationId()
                    invokeStatic(::lock)
                    visitLabel(tryLabel)
                }
            )
            visitCode()
        }

        override fun visitMaxs(maxStack: Int, maxLocals: Int) = adapter.run {
            invokeIfInTestingCode(
                original = {},
                code = {
                    visitLabel(catchLabel)
                    loadSynchronizedMethodMonitorOwner()
                    loadNewCodeLocationId()
                    invokeStatic(::unlock)
                    monitorEnter()
                    throwException()
                    visitTryCatchBlock(tryLabel, catchLabel, catchLabel, null)
                }
            )
            visitMaxs(maxStack, maxLocals)
        }

        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                    invokeIfInTestingCode(
                        original = {},
                        code = {
                            loadSynchronizedMethodMonitorOwner()
                            loadNewCodeLocationId()
                            invokeStatic(::unlock)
                            monitorEnter()
                        }
                    )
                }
            }
            visitInsn(opcode)
        }

        private fun loadSynchronizedMethodMonitorOwner() = adapter.run {
            if (isStatic) {
                val classType = Type.getType("L$className;")
                if (classVersion >= V1_5) {
                    visitLdcInsn(classType)
                } else {
                    visitLdcInsn(classType.className)
                    invokeStatic(CLASS_TYPE, CLASS_FOR_NAME_METHOD)
                }
            } else {
                loadThis()
            }
        }
    }

    /**
     * Makes all <clinit> sections ignored, because managed execution in <clinit> can lead to a deadlock.
     * SharedVariableAccessMethodTransformer should be earlier than this transformer not to create switch points before
     * beforeIgnoredSectionEntering invocations.
     */
    private inner class IgnoreClassInitializationTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {
        private val isClassInitializationMethod = methodName == "<clinit>"

        override fun visitCode() = adapter.run {
            if (isClassInitializationMethod) {
                invokeStatic(::enterIgnoredSection)
            }
            visitCode()
        }

        override fun visitInsn(opcode: Int) = adapter.run {
            if (isClassInitializationMethod) {
                when (opcode) {
                    ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                        invokeStatic(::leaveIgnoredSection)
                    }
                }
            }
            visitInsn(opcode)
        }
    }

    /**
     * Replaces Object.hashCode and Any.hashCode invocations with just zero.
     * This transformer prevents non-determinism due to the native hashCode implementation,
     * which typically returns memory address of the object. There is no guarantee that
     * memory addresses will be the same in different runs.
     */
    private inner class DetermenisticHashCodeTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (owner == "java/lang/Object" && name == "hashCode") {
                    invokeIfInTestingCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        code = {
                            invokeStatic(::deterministicHashCode)
                        }
                    )
                } else {
                    visitMethodInsn(opcode, owner, name, desc, itf)
                }
            }
    }

    /**
     * Replaces `System.nanoTime` and `System.currentTimeMillis` with stubs to prevent non-determinism
     */
    private class DeterministicTimeTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (owner == "java/lang/System" && (name == "nanoTime" || name == "currentTimeMillis")) {
                    invokeIfInTestingCode(
                        original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                        code = { push(1337L) } // any constant value
                    )
                    return
                }
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
    }

    /**
     * Makes java.util.Random and all classes that extend it deterministic.
     * In every Random method invocation replaces the owner with Random from ManagedStateHolder.
     * TODO: Kotlin's random support
     */
    private class DeterministicRandomTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        companion object {
            private val randomMethods = Random::class.java.declaredMethods.map { Method.getMethod(it) }
        }

        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (owner == "java/util/concurrent/ThreadLocalRandom" || owner == "java/util/concurrent/atomic/Striped64") {
                    if (name == "nextSecondarySeed" || name == "getProbe") { // INVOKESTATIC
                        invokeIfInTestingCode(
                            original = {
                                visitMethodInsn(opcode, owner, name, desc, itf)
                            },
                            code = {
                                invokeStatic(::nextInt)
                            }
                        )
                        return
                    }
                    if (name == "advanceProbe") { // INVOKEVIRTUAL
                        invokeIfInTestingCode(
                            original = {
                                visitMethodInsn(opcode, owner, name, desc, itf)
                            },
                            code = {
                                pop()
                                invokeStatic(::nextInt)
                            }
                        )
                        return
                    }
                }
                if (opcode == INVOKEVIRTUAL && isRandomMethod(name, desc)) {
                    invokeIfInTestingCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        code = {
                            val locals = storeArguments(desc)
                            pop()
                            invokeStatic(::deterministicRandom) // TODO check that this is a random class
                            loadLocals(locals)
                            visitMethodInsn(opcode, "java/util/Random", name, desc, itf)
                        }
                    )
                    return
                }
                visitMethodInsn(opcode, owner, name, desc, itf)
            }

        private fun isRandomMethod(methodName: String, desc: String): Boolean = randomMethods.any {
            it.name == methodName && it.descriptor == desc
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before park and after unpark calls
     */
    private inner class ParkUnparkTransformer(methodName: String, mv: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                val isPark = owner.isUnsafe() && name == "park"
                val isUnpark = owner.isUnsafe() && name == "unpark"
                when {
                    isPark -> {
                        invokeIfInTestingCode(
                            original = {
                                visitMethodInsn(opcode, owner, name, desc, itf)
                            },
                            code = {
                                pop2() // time
                                pop() // isAbsolute
                                pop() // Unsafe
                                loadNewCodeLocationId()
                                invokeStatic(::park)
                            }
                        )
                    }

                    isUnpark -> {
                        invokeIfInTestingCode(
                            original = {
                                visitMethodInsn(opcode, owner, name, desc, itf)
                            },
                            code = {
                                loadNewCodeLocationId()
                                invokeStatic(::unpark)
                            }
                        )
                    }

                    else -> {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    }
                }
            }
    }


    /**
     * Adds invocations of ManagedStrategy methods before reads and writes of shared variables
     */
    private inner class SharedVariableAccessMethodTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, desc: String) = adapter.run {
            // TODO do not analyze final fields and coroutine internals
//            if (isFinalField(owner, fieldName)) {
//                visitFieldInsn(opcode, owner, fieldName, desc)
//                return
//            }

            when (opcode) {
                GETSTATIC -> {
                    invokeIfInTestingCode(
                        original = {
                            visitFieldInsn(opcode, owner, fieldName, desc)
                        },
                        code = {
                            // STACK: <empty>
                            push(owner)
                            push(fieldName)
                            loadNewCodeLocationId()
                            // STACK: className: String, fieldName: String, codeLocation: Int
                            invokeStatic(::beforeReadFieldStatic)
                            // STACK: owner: Object
                            visitFieldInsn(opcode, owner, fieldName, desc)
                            // STACK: value
                            invokeReadValue(Type.getType(desc))
                            // STACK: value
                        }
                    )
                }

                GETFIELD -> {
                    invokeIfInTestingCode(
                        original = {
                            visitFieldInsn(opcode, owner, fieldName, desc)
                        },
                        code = {
                            // STACK: owner: Object
                            dup()
                            // STACK: owner: Object, owner: Object
                            push(className)
                            push(fieldName)
                            loadNewCodeLocationId()
                            // STACK: owner: Object, owner: Object, fieldName: String, fieldName: String, codeLocation: Int
                            invokeStatic(::beforeReadField)
                            // STACK: owner: Object
                            visitFieldInsn(opcode, owner, fieldName, desc)
                            // STACK: value
                            invokeReadValue(Type.getType(desc))
                            // STACK: value
                        }
                    )
                }

                PUTSTATIC -> {
                    // STACK: value: Object
                    invokeIfInTestingCode(
                        original = {},
                        code = {
                            val valueType = Type.getType(desc)
                            val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                            storeLocal(valueLocal)
                            loadLocal(valueLocal)
                            // STACK: value: Object
                            push(owner)
                            push(fieldName)
                            loadLocal(valueLocal)
                            box(valueType)
                            loadNewCodeLocationId()
                            // STACK: value: Object, className: String, fieldName: String, value: Object, codeLocation: Int
                            invokeStatic(::beforeWriteFieldStatic)
                            // STACK: value: Object
                        }
                    )
                    // STACK: value: Object
                    visitFieldInsn(opcode, owner, fieldName, desc)
                }

                PUTFIELD -> {
                    // STACK: owner: Object, value: Object
                    invokeIfInTestingCode(
                        original = {},
                        code = {
                            val valueType = Type.getType(desc)
                            val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                            storeLocal(valueLocal)
                            // STACK: owner: Object
                            dup()
                            // STACK: owner: Object, owner: Object
                            push(className)
                            push(fieldName)
                            loadLocal(valueLocal)
                            box(valueType)
                            loadNewCodeLocationId()
                            // STACK: owner: Object, owner: Object, fieldName: String, fieldName: String, value: Object, codeLocation: Int
                            invokeStatic(::beforeWriteField)
                            // STACK: owner: Object
                            loadLocal(valueLocal)
                            // STACK: owner: Object, value: Object
                        }
                    )
                    // STACK: owner: Object, value: Object
                    visitFieldInsn(opcode, owner, fieldName, desc)
                }

                else -> {
                    // All opcodes are covered above. However, in case a new one is added, Lincheck should not fail.
                    visitFieldInsn(opcode, owner, fieldName, desc)
                }
            }
        }

        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                AALOAD, LALOAD, FALOAD, DALOAD, IALOAD, BALOAD, CALOAD, SALOAD -> {
                    invokeIfInTestingCode(
                        original = {
                            visitInsn(opcode)
                        },
                        code = {
                            // STACK: array: Array, index: Int
                            dup()
                            // STACK: array: Array, index: Int, array: Array, index: Int
                            loadNewCodeLocationId()
                            // STACK: array: Array, index: Int, array: Array, index: Int, codeLocation: Int
                            invokeStatic(::beforeReadArrayElement)
                            // STACK: array: Array, index: Int
                            visitInsn(opcode)
                            // STACK: value
                            invokeReadValue(getArrayElementType(opcode))
                            // STACK: value
                        }
                    )
                }

                AASTORE, IASTORE, FASTORE, BASTORE, CASTORE, SASTORE, LASTORE, DASTORE -> {
                    invokeIfInTestingCode(
                        original = {},
                        code = {
                            // STACK: array: Array, index: Int, value: Object
                            val valueLocal =
                                newLocal(getArrayElementType(opcode)) // we cannot use DUP as long/double require DUP2
                            storeLocal(valueLocal)
                            // STACK: array: Array, index: Int
                            dup2()
                            // STACK: array: Array, index: Int, array: Array, index: Int
                            loadLocal(valueLocal)
                            loadNewCodeLocationId()
                            // STACK: array: Array, index: Int, array: Array, index: Int, value: Object, codeLocation: Int
                            invokeStatic(::beforeWriteArrayElement)
                            // STACK: array: Array, index: Int
                            loadLocal(valueLocal)
                            // STACK: array: Array, index: Int, value: Object
                        }
                    )
                    visitInsn(opcode)
                }

                else -> {
                    visitInsn(opcode)
                }
            }
        }

        private fun GeneratorAdapter.invokeReadValue(type: Type) {
            // STACK: value
            val resultLocal = newLocal(type)
            storeLocal(resultLocal)
            loadLocal(resultLocal)
            loadLocal(resultLocal)
            // STACK: value, value
            box(type)
            invokeStatic(Injections::onReadValue)
            // STACK: value
        }

        private fun getArrayElementType(opcode: Int): Type = when (opcode) {
            // Load
            AALOAD -> OBJECT_TYPE
            IALOAD -> Type.INT_TYPE
            FALOAD -> Type.FLOAT_TYPE
            BALOAD -> Type.BOOLEAN_TYPE
            CALOAD -> Type.CHAR_TYPE
            SALOAD -> Type.SHORT_TYPE
            LALOAD -> Type.LONG_TYPE
            DALOAD -> Type.DOUBLE_TYPE
            // Store
            AASTORE -> OBJECT_TYPE
            IASTORE -> Type.INT_TYPE
            FASTORE -> Type.FLOAT_TYPE
            BASTORE -> Type.BOOLEAN_TYPE
            CASTORE -> Type.CHAR_TYPE
            SASTORE -> Type.SHORT_TYPE
            LASTORE -> Type.LONG_TYPE
            DASTORE -> Type.DOUBLE_TYPE
            else -> throw IllegalStateException("Unexpected opcode: $opcode")
        }
    }

    /**
     * Adds strategy method invocations before and after method calls.
     */
    private inner class CallStackTraceLoggingTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
            // TODO: ignore coroutine internals
            if (isInternalCoroutineCall(owner, name)) {
                visitMethodInsn(opcode, owner, name, desc, itf)
                return
            }
            invokeIfInTestingCode(
                original = {
                    visitMethodInsn(opcode, owner, name, desc, itf)
                },
                code = {
                    // STACK [INVOKEVIRTUAL]: owner, arguments
                    // STACK [INVOKESTATIC]: arguments
//                    val argumentLocals = storeArguments(desc)
                    // STACK [INVOKEVIRTUAL]: owner
                    // STACK [INVOKESTATIC]: <empty>
//                    when (opcode) {
//                        INVOKEVIRTUAL -> dup()
//                        INVOKESTATIC -> visitInsn(ACONST_NULL)
//                    }
                    // STACK [INVOKEVIRTUAL]: owner, owner
                    // STACK [INVOKESTATIC]: <empty>, null
//                    push(className)
//                    push(methodName)
//                    loadNewCodeLocationId()
                    // STACK [INVOKEVIRTUAL]: owner, owner, className, methodName, codeLocation
                    // STACK [INVOKESTATIC]: <empty>, null, className, methodName, codeLocation
//                    when (argumentLocals.size) {
//                        0 -> {
//                            invokeStatic(::beforeMethodCall0)
//                        }
//
//                        1 -> {
//                            loadLocals(argumentLocals)
//                            invokeStatic(::beforeMethodCall1)
//                        }
//
//                        2 -> {
//                            loadLocals(argumentLocals)
//                            invokeStatic(::beforeMethodCall2)
//                        }
//
//                        3 -> {
//                            loadLocals(argumentLocals)
//                            invokeStatic(::beforeMethodCall3)
//                        }
//
//                        4 -> {
//                            loadLocals(argumentLocals)
//                            invokeStatic(::beforeMethodCall4)
//                        }
//
//                        5 -> {
//                            loadLocals(argumentLocals)
//                            invokeStatic(::beforeMethodCall5)
//                        }
//
//                        else -> {
//                            push(argumentLocals.size) // size of the array
//                            visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
//                            // STACK: ..., array
//                            val argumentTypes = Type.getArgumentTypes(desc)
//                            for (i in argumentLocals.indices) {
//                                // STACK: ..., array
//                                dup()
//                                // STACK: ..., array, array
//                                push(i)
//                                // STACK: ..., array, array, index
//                                loadLocal(argumentLocals[i])
//                                // STACK: ..., array, array, index, argument[index]
//                                box(argumentTypes[i])
//                                arrayStore(OBJECT_TYPE)
//                                // STACK: ..., array
//                            }
//                            // STACK: ..., array
//                            invokeStatic(::beforeMethodCall)
//                        }
//                    }
                    // STACK [INVOKEVIRTUAL]: owner, arguments
                    // STACK [INVOKESTATIC]: arguments
                    val methodCallStartLabel = newLabel()
                    val methodCallEndLabel = newLabel()
                    val handlerExceptionStartLabel = newLabel()
                    val handlerExceptionEndLabel = newLabel()
                    visitTryCatchBlock(methodCallStartLabel, methodCallEndLabel, handlerExceptionStartLabel, null)
                    visitLabel(methodCallStartLabel)
                    visitMethodInsn(opcode, owner, name, desc, itf)
                    visitLabel(methodCallEndLabel)
                    // STACK [INVOKEVIRTUAL]: owner, arguments
                    // STACK [INVOKESTATIC]: arguments
                    val resultType = Type.getReturnType(desc)
                    val resultLocal = newLocal(resultType)
                    storeLocal(resultLocal)
                    loadLocal(resultLocal)
                    loadLocal(resultLocal)
                    // STACK: result, result
                    box(resultType)
                    invokeStatic(Injections::onMethodCallFinishedSuccessfully)
                    // STACK: value
                    goTo(handlerExceptionEndLabel)
                    visitLabel(handlerExceptionStartLabel)
                    dup()
                    invokeStatic(Injections::onMethodCallThrewException)
                    throwException()
                    visitLabel(handlerExceptionEndLabel)
                    // STACK: value
                }
            )
        }

        private fun isInternalCoroutineCall(owner: String, name: String) =
            owner == "kotlin/coroutines/intrinsics/IntrinsicsKt" && name == "getCOROUTINE_SUSPENDED"
    }

    /**
     * Tracks names of fields for created AFUs and saves them via ObjectManager.
     * CallStackTraceTransformer should be an earlier transformer than this transformer, because
     * this transformer reuse code locations created by CallStackTraceTransformer.
     * TODO: track other atomic constructions
     */
    private inner class AFUTrackingTransformer(methodName: String, mv: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, mname: String, desc: String, isInterface: Boolean) =
            adapter.run {
                val isNewAtomicFieldUpdater = opcode == INVOKESTATIC && mname == "newUpdater" && owner.isAFU
                if (isNewAtomicFieldUpdater) {
                    val nameLocal = newLocal(STRING_TYPE)
                    storeTopToLocal(nameLocal) // name is the last parameter
                    visitMethodInsn(opcode, owner, mname, desc, isInterface)
                    dup() // copy AFU
                    loadLocal(nameLocal)
                    invokeStatic(::onNewAtomicFieldUpdater)
                } else {
                    visitMethodInsn(opcode, owner, mname, desc, isInterface)
                }
            }

        private val String.isAFU get() = startsWith("java/util/concurrent/atomic/Atomic") && endsWith("FieldUpdater")
    }


    /**
     * Adds invocations of ManagedStrategy methods before wait and after notify calls
     */
    private inner class WaitNotifyTransformer(methodName: String, mv: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (opcode == INVOKEVIRTUAL) {
                    when {
                        isWait0(name, desc) -> {
                            invokeIfInTestingCode(
                                original = {
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                },
                                code = {
                                    invokeStatic(::wait)
                                }
                            )
                        }

                        isWait1(name, desc) -> {
                            invokeIfInTestingCode(
                                original = {
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                },
                                code = {
                                    pop2() // timeMillis
                                    invokeStatic(::waitWithTimeout)
                                }
                            )
                        }

                        isWait2(name, desc) -> {
                            invokeIfInTestingCode(
                                original = {
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                },
                                code = {
                                    pop() // timeNanos
                                    pop2() // timeMillis
                                    invokeStatic(::waitWithTimeout)
                                }
                            )
                        }

                        isNotify(name, desc) -> {
                            invokeIfInTestingCode(
                                original = {
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                },
                                code = {
                                    invokeStatic(::notify)
                                }
                            )
                        }

                        isNotifyAll(name, desc) -> {
                            invokeIfInTestingCode(
                                original = {
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                },
                                code = {
                                    invokeStatic(::notifyAll)
                                }
                            )
                        }

                        else -> {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        }
                    }
                } else {
                    visitMethodInsn(opcode, owner, name, desc, itf)
                }
            }

        private fun isWait(opcode: Int, name: String, desc: String): Boolean {
            if (opcode == INVOKEVIRTUAL && name == "wait") {
                when (desc) {
                    "()V", "(J)V", "(JI)V" -> return true
                }
            }
            return false
        }

        private fun isWait0(mname: String, desc: String) = mname == "wait" && desc == "()V"
        private fun isWait1(mname: String, desc: String) = mname == "wait" && desc == "(J)V"
        private fun isWait2(mname: String, desc: String) = mname == "wait" && desc == "(JI)V"

        private fun isNotify(mname: String, desc: String) = mname == "notify" && desc == "()V"
        private fun isNotifyAll(mname: String, desc: String) = mname == "notifyAll" && desc == "()V"
    }

    private open inner class ManagedStrategyMethodVisitor(
        protected val methodName: String,
        val adapter: GeneratorAdapter
    ) : MethodVisitor(ASM_API, adapter) {
        private var lineNumber = 0

        protected fun loadNewCodeLocationId() {
            val stackTraceElement = StackTraceElement(className, methodName, fileName, lineNumber)
            val codeLocationId = CodeLocations.newCodeLocation(stackTraceElement)
            adapter.push(codeLocationId)
        }

        override fun visitLineNumber(line: Int, start: Label) {
            lineNumber = line
            super.visitLineNumber(line, start)
        }
    }
}

private val STRING_TYPE = Type.getType(String::class.java)
private val CLASS_TYPE = Type.getType(Class::class.java)
private val CLASS_FOR_NAME_METHOD =
    Method("forName", CLASS_TYPE, arrayOf(STRING_TYPE)) // manual, because there are several forName methods

/**
 * Returns array of locals containing given arguments.
 * STACK: param_1 param_2 ... param_n
 * RESULT STACK: (empty)
 */
private fun GeneratorAdapter.storeArguments(methodDescriptor: String): IntArray {
    val argumentTypes = Type.getArgumentTypes(methodDescriptor)
    val locals = IntArray(argumentTypes.size)
    // store all arguments
    for (i in argumentTypes.indices.reversed()) {
        locals[i] = newLocal(argumentTypes[i])
        storeLocal(locals[i], argumentTypes[i])
    }
    return locals
}

private fun GeneratorAdapter.loadLocals(locals: IntArray) {
    for (local in locals)
        loadLocal(local)
}

/**
 * Saves the top value on the stack without changing stack.
 */
private fun GeneratorAdapter.storeTopToLocal(local: Int) {
    // NB: We cannot use DUP here, as long and double require DUP2
    storeLocal(local)
    loadLocal(local)
}

private fun String.isUnsafe() = this == "sun/misc/Unsafe" || this == "jdk/internal/misc/Unsafe"
