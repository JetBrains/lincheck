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
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.*
import java.util.stream.Collectors
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredFunctions
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

private inline fun GeneratorAdapter.invokeIfInTestingCode(original: GeneratorAdapter.() -> Unit, code: GeneratorAdapter.() -> Unit) {
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

    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String, interfaces: Array<String>) {
        className = name
        classVersion = version
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitSource(source: String, debug: String?) {
        fileName = source
        super.visitSource(source, debug)
    }

    override fun visitMethod(access: Int, mname: String, desc: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
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
        mv = IgnoreClassInitializationTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = AFUTrackingTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
//        mv = CallStackTraceLoggingTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = WaitNotifyTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = ParkUnparkTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
//        mv = LocalObjectManagingTransformer(mname, GeneratorAdapter(mv, access, mname, desc)) // TODO: implement in code
        if (mname != "<init>" && mname != "<clinit>") // TODO: fix me
            mv = SharedVariableAccessMethodTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = DetermenisticHashCodeTransformer(mname, GeneratorAdapter(mv, access, mname, desc))
        mv = DeterministicTimeTransformer(GeneratorAdapter(mv, access, mname, desc))
        mv = DeterministicRandomTransformer(GeneratorAdapter(mv, access, mname, desc))
        return mv
    }

    private class CoroutineCancellabilitySupportMethodTransformer(mv: MethodVisitor, access: Int, methodName: String?, desc: String?)
        : AdviceAdapter(ASM_API, mv, access, methodName, desc)
    {
        override fun visitMethodInsn(opcodeAndSource: Int, className: String?, methodName: String?, descriptor: String?, isInterface: Boolean) {
            val isGetResult = ("kotlinx/coroutines/CancellableContinuation" == className || "kotlinx/coroutines/CancellableContinuationImpl" == className)
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
    private inner class MonitorEnterAndExitTransformer(mname: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(mname, adapter) {
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
    private inner class SynchronizedMethodTransformer(methodName: String, mv: GeneratorAdapter, access: Int, private val classVersion: Int) : ManagedStrategyMethodVisitor(methodName, mv) {
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
    private inner class IgnoreClassInitializationTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter)  {
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
    private inner class DetermenisticHashCodeTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter)  {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
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
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
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
        private val randomMethods by lazy { Random::class.java.declaredMethods.map { Method.getMethod(it) } }

        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
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
                        val locals = storeParameters(desc)
                        pop()
                        invokeStatic(::deterministicRandom) // TODO better that this is a random class
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
    private inner class ParkUnparkTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
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
    private inner class SharedVariableAccessMethodTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
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
                            push(fieldName)
                            loadNewCodeLocationId()
                            // STACK: owner: Object, owner: Object, fieldName: String, codeLocation: Int
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
                            push(fieldName)
                            loadLocal(valueLocal)
                            box(valueType)
                            loadNewCodeLocationId()
                            // STACK: owner: Object, owner: Object, fieldName: String, value: Object, codeLocation: Int
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
                            val valueLocal = newLocal(getArrayElementType(opcode)) // we cannot use DUP as long/double require DUP2
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
//
//    /**
//     * Adds strategy method invocations before and after method calls.
//     */
//    private inner class CallStackTraceLoggingTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
//        private val isSuspendStateMachine by lazy { isSuspendStateMachine(className) }
//
//        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
//            if (isSuspendStateMachine || isStrategyMethod(owner) || isInternalCoroutineCall(owner, name)) {
//                visitMethodInsn(opcode, owner, name, desc, itf)
//                return
//            }
//            val l1 = adapter.newLabel()
//            val l2 = adapter.newLabel()
//            loadStrategy()
//            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, COLLECT_TRACE_METHOD)
//            adapter.ifZCmp(GT, l1)
//
//            visitMethodInsn(opcode, owner, name, desc, itf)
//            adapter.goTo(l2)
//
//            adapter.visitLabel(l1)
//            val callStart = newLabel()
//            val callEnd = newLabel()
//            val exceptionHandler = newLabel()
//            val skipHandler = newLabel()
//
//            val tracePointLocal = newTracePointLocal()!!
//            beforeMethodCall(opcode, owner, name, desc, tracePointLocal)
//            if (name != "<init>") {
//                // just hope that constructors do not throw exceptions.
//                // we can not handle this case, because uninitialized values are not allowed by jvm
//                visitTryCatchBlock(callStart, callEnd, exceptionHandler, null)
//            }
//            visitLabel(callStart)
//            visitMethodInsn(opcode, owner, name, desc, itf)
//            visitLabel(callEnd)
//            afterMethodCall(Method(name, desc).returnType, tracePointLocal)
//
//            goTo(skipHandler)
//            visitLabel(exceptionHandler)
//            onException(tracePointLocal)
//            invokeAfterMethodCall(tracePointLocal) // notify strategy that the method finished
//            throwException() // throw the exception further
//            visitLabel(skipHandler)
//
//            adapter.visitLabel(l2)
//        }
//
//        // STACK: param_1 param_2 ... param_n
//        private fun beforeMethodCall(opcode: Int, owner: String, methodName: String, desc: String, tracePointLocal: Int) {
//            invokeBeforeMethodCall(methodName, tracePointLocal)
//            captureParameters(opcode, owner, methodName, desc, tracePointLocal)
//            captureOwnerName(opcode, owner, methodName, desc, tracePointLocal)
//        }
//
//        // STACK: returned value (unless void)
//        private fun afterMethodCall(returnType: Type, tracePointLocal: Int) = adapter.run {
//            if (returnType != Type.VOID_TYPE) {
//                val returnedValue = newLocal(returnType)
//                copyLocal(returnedValue)
//                // initialize MethodCallCodePoint return value
//                loadLocal(tracePointLocal)
//                checkCast(METHOD_TRACE_POINT_TYPE)
//                loadLocal(returnedValue)
//                box(returnType)
//                invokeVirtual(METHOD_TRACE_POINT_TYPE, INITIALIZE_RETURNED_VALUE_METHOD)
//            }
//            invokeAfterMethodCall(tracePointLocal)
//        }
//
//        // STACK: exception
//        private fun onException(tracePointLocal: Int) = adapter.run {
//            val exceptionLocal = newLocal(THROWABLE_TYPE)
//            copyLocal(exceptionLocal)
//            // initialize MethodCallCodePoint thrown exception
//            loadLocal(tracePointLocal)
//            checkCast(METHOD_TRACE_POINT_TYPE)
//            loadLocal(exceptionLocal)
//            invokeVirtual(METHOD_TRACE_POINT_TYPE, INITIALIZE_THROWN_EXCEPTION_METHOD)
//        }
//
//        // STACK: param_1 param_2 ... param_n
//        private fun captureParameters(opcode: Int, owner: String, methodName: String, desc: String, tracePointLocal: Int) = adapter.run {
//            val paramTypes = Type.getArgumentTypes(desc)
//            if (paramTypes.isEmpty()) return // nothing to capture
//            val params = copyParameters(paramTypes)
//            val firstLoggedParameter = if (isAFUMethodCall(opcode, owner, methodName, desc)) {
//                // do not log the first object in AFU methods
//                1
//            } else {
//                0
//            }
//            val lastLoggedParameter = if (paramTypes.last().internalName == "kotlin/coroutines/Continuation" && isSuspend(owner, methodName, desc)) {
//                // do not log the last continuation in suspend functions
//                paramTypes.size - 1
//            } else {
//                paramTypes.size
//            }
//            // create array of parameters
//            push(lastLoggedParameter - firstLoggedParameter) // size of the array
//            visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
//            val parameterValuesLocal = newLocal(OBJECT_ARRAY_TYPE)
//            storeLocal(parameterValuesLocal)
//            for (i in firstLoggedParameter until lastLoggedParameter) {
//                loadLocal(parameterValuesLocal)
//                push(i - firstLoggedParameter)
//                loadLocal(params[i])
//                box(paramTypes[i]) // in case it is a primitive type
//                arrayStore(OBJECT_TYPE)
//            }
//            // initialize MethodCallCodePoint parameter values
//            loadLocal(tracePointLocal)
//            checkCast(METHOD_TRACE_POINT_TYPE)
//            loadLocal(parameterValuesLocal)
//            invokeVirtual(METHOD_TRACE_POINT_TYPE, INITIALIZE_PARAMETERS_METHOD)
//        }
//
//        // STACK: owner param_1 param_2 ... param_n
//        private fun captureOwnerName(opcode: Int, owner: String, methodName: String, desc: String, tracePointLocal: Int) = adapter.run {
//            if (!isAFUMethodCall(opcode, owner, methodName, desc)) {
//                // currently object name labels are used only for AFUs
//                return
//            }
//            val afuLocal = newLocal(Type.getType("L$owner;"))
//            // temporarily remove parameters from stack to copy AFU
//            val params = storeParameters(desc)
//            copyLocal(afuLocal)
//            // return parameters to the stack
//            for (param in params)
//                loadLocal(param)
//            // initialize MethodCallCodePoint owner name
//            loadLocal(tracePointLocal)
//            checkCast(METHOD_TRACE_POINT_TYPE)
//            // get afu name
//            loadObjectManager()
//            loadLocal(afuLocal)
//            invokeVirtual(OBJECT_MANAGER_TYPE, GET_OBJECT_NAME)
//            invokeVirtual(METHOD_TRACE_POINT_TYPE, INITIALIZE_OWNER_NAME_METHOD)
//        }
//
//        private fun invokeBeforeMethodCall(methodName: String, tracePointLocal: Int) {
//            loadStrategy()
//            loadCurrentThreadNumber()
//            loadNewCodeLocationAndTracePoint(tracePointLocal, METHOD_TRACE_POINT_TYPE) { iThread, actorId, callStackTrace, ste ->
//                MethodCallTracePoint(iThread, actorId, callStackTrace, methodName, ste)
//            }
//            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_METHOD_CALL_METHOD)
//        }
//
//        private fun invokeAfterMethodCall(tracePointLocal: Int) {
//            loadStrategy()
//            loadCurrentThreadNumber()
//            adapter.loadLocal(tracePointLocal)
//            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, AFTER_METHOD_CALL_METHOD)
//        }
//
//        private fun isInternalCoroutineCall(owner: String, name: String) =
//            owner == "kotlin/coroutines/intrinsics/IntrinsicsKt" && name == "getCOROUTINE_SUSPENDED"
//    }
//
    /**
     * Tracks names of fields for created AFUs and saves them via ObjectManager.
     * CallStackTraceTransformer should be an earlier transformer than this transformer, because
     * this transformer reuse code locations created by CallStackTraceTransformer.
     * TODO: track other atomic constructions
     */
    private inner class AFUTrackingTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, mname: String, desc: String, isInterface: Boolean) = adapter.run {
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
    private inner class WaitNotifyTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
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

//
//    /**
//     * Track local objects for odd switch points elimination.
//     * A local object is an object that can be possible viewed only from one thread.
//     */
//    private inner class LocalObjectManagingTransformer(methodName: String, mv: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, mv) {
//        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
//            val isObjectCreation = opcode == INVOKESPECIAL && name == "<init>" && owner == "java/lang/Object"
//            val isImpossibleToTransformPrimitive = isImpossibleToTransformApiClass(owner.canonicalClassName)
//            val lowerCaseName = name.toLowerCase(Locale.US)
//            val isPrimitiveWrite = isImpossibleToTransformPrimitive && WRITE_KEYWORDS.any { it in lowerCaseName }
//            val isObjectPrimitiveWrite = isPrimitiveWrite && Type.getArgumentTypes(descriptor).lastOrNull()?.descriptor?.isNotPrimitiveType() ?: false
//
//            when {
//                isObjectCreation -> {
//                    adapter.dup() // will be used for onNewLocalObject method
//                    adapter.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
//                    invokeOnNewLocalObject()
//                }
//                isObjectPrimitiveWrite -> {
//                    // the exact list of methods that should be matched here:
//                    // Unsafe.put[Ordered]?Object[Volatile]?
//                    // Unsafe.getAndSet
//                    // Unsafe.compareAndSwapObject
//                    // VarHandle.set[Volatile | Acquire | Opaque]?
//                    // VarHandle.[weak]?CompareAndSet[Plain | Acquire | Release]?
//                    // VarHandle.compareAndExchange[Acquire | Release]?
//                    // VarHandle.getAndSet[Acquire | Release]?
//                    // AtomicReferenceFieldUpdater.compareAndSet
//                    // AtomicReferenceFieldUpdater.[lazy]?Set
//                    // AtomicReferenceFieldUpdater.getAndSet
//
//                    // all this methods have the field owner as the first argument and the written value as the last one
//                    val params = adapter.copyParameters(descriptor)
//                    adapter.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
//                    adapter.loadLocal(params.first())
//                    adapter.loadLocal(params.last())
//                    invokeAddDependency()
//                }
//                else -> adapter.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
//            }
//        }
//
//        override fun visitIntInsn(opcode: Int, operand: Int) {
//            adapter.visitIntInsn(opcode, operand)
//            if (opcode == NEWARRAY) {
//                adapter.dup()
//                invokeOnNewLocalObject()
//            }
//        }
//
//        override fun visitTypeInsn(opcode: Int, type: String) {
//            adapter.visitTypeInsn(opcode, type)
//            if (opcode == ANEWARRAY) {
//                adapter.dup()
//                invokeOnNewLocalObject()
//            }
//        }
//
//        override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
//            val isNotPrimitiveType = desc.isNotPrimitiveType()
//            val isFinalField = isFinalField(owner, name)
//            if (isNotPrimitiveType) {
//                when (opcode) {
//                    PUTSTATIC -> {
//                        adapter.dup()
//                        invokeDeleteLocalObject()
//                    }
//                    PUTFIELD -> {
//                        // we cannot invoke this method for final field, because an object may uninitialized yet
//                        // will add dependency for final fields after <init> ends instead
//                        if (!isFinalField) {
//                            // owner, value
//                            adapter.dup2() // owner, value, owner, value
//                            invokeAddDependency() // owner, value
//                        }
//                    }
//                }
//            }
//            super.visitFieldInsn(opcode, owner, name, desc)
//        }
//
//        override fun visitInsn(opcode: Int) = adapter.run {
//            val value: Int
//            when (opcode) {
//                AASTORE -> {
//                    // array, index, value
//                    value = newLocal(OBJECT_TYPE)
//                    storeLocal(value) // array, index
//                    dup2() // array, index, array, index
//                    pop() // array, index, array
//                    loadLocal(value) // array, index, array, value
//                    invokeAddDependency() // array, index
//                    loadLocal(value) // array, index, value
//                }
//                RETURN -> if (methodName == "<init>") {
//                    // handle all final field added dependencies
//                    val ownerType = Type.getObjectType(className)
//                    for (field in getNonStaticFinalFields(className)) {
//                        if (field.type.isPrimitive) continue
//                        val fieldType = Type.getType(field.type)
//                        loadThis() // owner
//                        loadThis() // owner, owner
//                        getField(ownerType, field.name, fieldType) // owner, value
//                        invokeAddDependency()
//                    }
//                }
//            }
//            adapter.visitInsn(opcode)
//        }
//
//        // STACK: object
//        private fun invokeOnNewLocalObject() {
//            if (eliminateLocalObjects) {
//                val objectLocal: Int = adapter.newLocal(OBJECT_TYPE)
//                adapter.storeLocal(objectLocal)
//                loadObjectManager()
//                adapter.loadLocal(objectLocal)
//                adapter.invokeVirtual(OBJECT_MANAGER_TYPE, NEW_LOCAL_OBJECT_METHOD)
//            } else {
//                adapter.pop()
//            }
//        }
//
//        // STACK: object
//        private fun invokeDeleteLocalObject() {
//            if (eliminateLocalObjects) {
//                val objectLocal: Int = adapter.newLocal(OBJECT_TYPE)
//                adapter.storeLocal(objectLocal)
//                loadObjectManager()
//                adapter.loadLocal(objectLocal)
//                adapter.invokeVirtual(OBJECT_MANAGER_TYPE, DELETE_LOCAL_OBJECT_METHOD)
//            } else {
//                adapter.pop()
//            }
//        }
//
//        // STACK: owner, dependant
//        private fun invokeAddDependency() {
//            if (eliminateLocalObjects) {
//                val ownerLocal: Int = adapter.newLocal(OBJECT_TYPE)
//                val dependantLocal: Int = adapter.newLocal(OBJECT_TYPE)
//                adapter.storeLocal(dependantLocal)
//                adapter.storeLocal(ownerLocal)
//                loadObjectManager()
//                adapter.loadLocal(ownerLocal)
//                adapter.loadLocal(dependantLocal)
//                adapter.invokeVirtual(OBJECT_MANAGER_TYPE, ADD_DEPENDENCY_METHOD)
//            } else {
//                repeat(2) { adapter.pop() }
//            }
//        }
//    }

    private open inner class ManagedStrategyMethodVisitor(protected val methodName: String, val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        private var lineNumber = 0

        protected fun loadNewCodeLocationId() {
            val stackTraceElement = StackTraceElement(className, methodName, fileName, lineNumber)
            val codeLocationId = CodeLocations.newCodeLocation(stackTraceElement)
            adapter.push(codeLocationId)
        }

        /**
         * Generated code is equal to
         * ```
         * strategy.enterIgnoredSection()
         * try {
         *     generated by `block` code
         * } finally {
         *     strategy.leaveIgnoredSection()
         * }
         * ```
         */
        protected fun runInIgnoredSection(block: () -> Unit) = adapter.run {
            val callStart = newLabel()
            val callEnd = newLabel()
            val exceptionHandler = newLabel()
            val skipHandler = newLabel()
            if (name != "<init>") {
                // just hope that constructors do not throw exceptions.
                // we can not handle this case, because uninitialized values are not allowed by jvm
                visitTryCatchBlock(callStart, callEnd, exceptionHandler, null)
            }
            enterIgnoredSection()
            visitLabel(callStart)
            block()
            visitLabel(callEnd)
            leaveIgnoredSection()
            goTo(skipHandler)
            // upon exception leave ignored section
            visitLabel(exceptionHandler)
            leaveIgnoredSection()
            throwException() // throw the exception further
            visitLabel(skipHandler)
        }

        override fun visitLineNumber(line: Int, start: Label) {
            lineNumber = line
            super.visitLineNumber(line, start)
        }
    }
}

/**
 * The counter that helps to assign gradually increasing disjoint ids to different code locations
 */
internal object CodeLocationIdProvider {
    var lastId = -1 // the first id will be zero
        private set
    fun newId() = ++lastId
}

// By default `java.util` interfaces are not transformed, while classes are.
// Here are the exceptions to these rules.
// They were found with the help of the `findAllTransformationProblems` method.
internal val NOT_TRANSFORMED_JAVA_UTIL_CLASSES = setOf(
    "java/util/ServiceLoader", // can not be transformed because of access to `SecurityManager`
    "java/util/concurrent/TimeUnit", // many not transformed interfaces such as `java.util.concurrent.BlockingQueue` use it
    "java/util/OptionalDouble", // used by `java.util.stream.DoubleStream`. Is an immutable collection
    "java/util/OptionalLong",
    "java/util/OptionalInt",
    "java/util/Optional",
    "java/util/Locale", // is an immutable class too
    "java/util/Locale\$Category",
    "java/util/Locale\$FilteringMode",
    "java/util/Currency",
    "java/util/Date",
    "java/util/Calendar",
    "java/util/TimeZone",
    "java/util/DoubleSummaryStatistics", // this class is mutable, but `java.util.stream.DoubleStream` interface better be not transformed
    "java/util/LongSummaryStatistics",
    "java/util/IntSummaryStatistics",
    "java/util/Formatter",
    "java/util/stream/PipelineHelper",
    "java/util/Random", // will be thread safe after `RandomTransformer` transformation
    "java/util/concurrent/ThreadLocalRandom"
).map { it.canonicalClassName }.toSet()

//private val OBJECT_TYPE = Type.getType(Any::class.java)
//private val THROWABLE_TYPE = Type.getType(java.lang.Throwable::class.java)
//private val MANAGED_STATE_HOLDER_TYPE = Type.getType(ManagedStrategyStateHolder::class.java)
//private val MANAGED_STRATEGY_TYPE = Type.getType(ManagedStrategy::class.java)
//private val OBJECT_MANAGER_TYPE = Type.getType(ObjectManager::class.java)
//private val RANDOM_TYPE = Type.getType(Random::class.java)
private val STRING_TYPE = Type.getType(String::class.java)
private val CLASS_TYPE = Type.getType(Class::class.java)
//private val OBJECT_ARRAY_TYPE = Type.getType("[" + OBJECT_TYPE.descriptor)
//private val TRACE_POINT_TYPE = Type.getType(TracePoint::class.java)
//private val WRITE_TRACE_POINT_TYPE = Type.getType(WriteTracePoint::class.java)
//private val READ_TRACE_POINT_TYPE = Type.getType(ReadTracePoint::class.java)
//private val METHOD_TRACE_POINT_TYPE = Type.getType(MethodCallTracePoint::class.java)
//private val MONITORENTER_TRACE_POINT_TYPE = Type.getType(MonitorEnterTracePoint::class.java)
//private val MONITOREXIT_TRACE_POINT_TYPE = Type.getType(MonitorExitTracePoint::class.java)
//private val WAIT_TRACE_POINT_TYPE = Type.getType(WaitTracePoint::class.java)
//private val NOTIFY_TRACE_POINT_TYPE = Type.getType(NotifyTracePoint::class.java)
//private val PARK_TRACE_POINT_TYPE = Type.getType(ParkTracePoint::class.java)
//private val UNPARK_TRACE_POINT_TYPE = Type.getType(UnparkTracePoint::class.java)
//
//private val CURRENT_THREAD_NUMBER_METHOD = Method.getMethod(ManagedStrategy::currentThreadNumber.javaMethod)
//private val BEFORE_SHARED_VARIABLE_READ_METHOD = Method.getMethod(ManagedStrategy::beforeSharedVariableRead.javaMethod)
//private val BEFORE_SHARED_VARIABLE_WRITE_METHOD = Method.getMethod(ManagedStrategy::beforeSharedVariableWrite.javaMethod)
//private val BEFORE_LOCK_ACQUIRE_METHOD = Method.getMethod(ManagedStrategy::beforeLockAcquire.javaMethod)
//private val BEFORE_LOCK_RELEASE_METHOD = Method.getMethod(ManagedStrategy::beforeLockRelease.javaMethod)
//private val BEFORE_WAIT_METHOD = Method.getMethod(ManagedStrategy::beforeWait.javaMethod)
//private val BEFORE_NOTIFY_METHOD = Method.getMethod(ManagedStrategy::beforeNotify.javaMethod)
//private val BEFORE_PARK_METHOD = Method.getMethod(ManagedStrategy::beforePark.javaMethod)
//private val AFTER_UNPARK_METHOD = Method.getMethod(ManagedStrategy::afterUnpark.javaMethod)
//private val ENTER_IGNORED_SECTION_METHOD = Method.getMethod(ManagedStrategy::enterIgnoredSection.javaMethod)
//private val LEAVE_IGNORED_SECTION_METHOD = Method.getMethod(ManagedStrategy::leaveIgnoredSection.javaMethod)
//private val BEFORE_METHOD_CALL_METHOD = Method.getMethod(ManagedStrategy::beforeMethodCall.javaMethod)
//private val AFTER_METHOD_CALL_METHOD = Method.getMethod(ManagedStrategy::afterMethodCall.javaMethod)
//private val MAKE_STATE_REPRESENTATION_METHOD = Method.getMethod(ManagedStrategy::addStateRepresentation.javaMethod)
//private val BEFORE_ATOMIC_METHOD_CALL_METHOD = Method.getMethod(ManagedStrategy::beforeAtomicMethodCall.javaMethod)
//private val CREATE_TRACE_POINT_METHOD = Method.getMethod(ManagedStrategy::createTracePoint.javaMethod)
//private val COLLECT_TRACE_METHOD = Method.getMethod(ManagedStrategy::collectTrace.javaMethod)
//private val NEW_LOCAL_OBJECT_METHOD = Method.getMethod(ObjectManager::newLocalObject.javaMethod)
//private val DELETE_LOCAL_OBJECT_METHOD = Method.getMethod(ObjectManager::deleteLocalObject.javaMethod)
//private val IS_LOCAL_OBJECT_METHOD = Method.getMethod(ObjectManager::isLocalObject.javaMethod)
//private val ADD_DEPENDENCY_METHOD = Method.getMethod(ObjectManager::addDependency.javaMethod)
//private val SET_OBJECT_NAME = Method.getMethod(ObjectManager::setObjectName.javaMethod)
//private val GET_OBJECT_NAME = Method.getMethod(ObjectManager::getObjectName.javaMethod)
private val CLASS_FOR_NAME_METHOD = Method("forName", CLASS_TYPE, arrayOf(STRING_TYPE)) // manual, because there are several forName methods
//private val INITIALIZE_WRITTEN_VALUE_METHOD = Method.getMethod(WriteTracePoint::initializeWrittenValue.javaMethod)
//private val INITIALIZE_READ_VALUE_METHOD = Method.getMethod(ReadTracePoint::initializeReadValue.javaMethod)
//private val INITIALIZE_RETURNED_VALUE_METHOD = Method.getMethod(MethodCallTracePoint::initializeReturnedValue.javaMethod)
//private val INITIALIZE_THROWN_EXCEPTION_METHOD = Method.getMethod(MethodCallTracePoint::initializeThrownException.javaMethod)
//private val INITIALIZE_PARAMETERS_METHOD = Method.getMethod(MethodCallTracePoint::initializeParameters.javaMethod)
//private val INITIALIZE_OWNER_NAME_METHOD = Method.getMethod(MethodCallTracePoint::initializeOwnerName.javaMethod)
private val NEXT_INT_METHOD = Method("nextInt", Type.INT_TYPE, emptyArray<Type>())

private val WRITE_KEYWORDS = listOf("set", "put", "swap", "exchange")

/**
 * Returns array of locals containing given parameters.
 * STACK: param_1 param_2 ... param_n
 * RESULT STACK: (empty)
 */
private fun GeneratorAdapter.storeParameters(paramTypes: Array<Type>): IntArray {
    val locals = IntArray(paramTypes.size)
    // store all arguments
    for (i in paramTypes.indices.reversed()) {
        locals[i] = newLocal(paramTypes[i])
        storeLocal(locals[i], paramTypes[i])
    }
    return locals
}



private fun GeneratorAdapter.storeParameters(methodDescriptor: String) = storeParameters(Type.getArgumentTypes(methodDescriptor))

/**
 * Returns array of locals containing given parameters.
 * STACK: param_1 param_2 ... param_n
 * RESULT STACK: param_1 param_2 ... param_n (the stack is not changed)
 */
private fun GeneratorAdapter.copyParameters(paramTypes: Array<Type>): IntArray {
    val locals = storeParameters(paramTypes)
    loadLocals(locals)
    return locals
}

private fun GeneratorAdapter.copyParameters(methodDescriptor: String): IntArray = copyParameters(Type.getArgumentTypes(methodDescriptor))

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

/**
 * Get non-static final fields that belong to the class. Note that final fields of super classes won't be returned.
 */
private fun getNonStaticFinalFields(ownerInternal: String): List<Field> =
    try {
        val clazz = Class.forName(ownerInternal.canonicalClassName)
        val fields = clazz.declaredFields
        Arrays.stream(fields)
            .filter { field: Field -> field.modifiers and (Modifier.FINAL or Modifier.STATIC) == Modifier.FINAL }
            .collect(Collectors.toList())
    } catch (e: ClassNotFoundException) {
        throw RuntimeException(e)
    }

private fun isFinalField(ownerInternal: String, fieldName: String): Boolean {
    return try {
        val clazz = Class.forName(ownerInternal.canonicalClassName)
        val field = findField(clazz, fieldName) ?: throw NoSuchFieldException("No $fieldName in ${clazz.name}")
        field.modifiers and Modifier.FINAL == Modifier.FINAL
    } catch (e: ClassNotFoundException) {
        throw RuntimeException(e)
    } catch (e: NoSuchFieldException) {
        throw RuntimeException(e)
    }
}

private fun findField(clazz: Class<*>?, fieldName: String): Field? {
    if (clazz == null) return null
    val fields = clazz.declaredFields
    for (field in fields) if (field.name == fieldName) return field
    // No field found in this class.
    // Search in super class first, then in interfaces.
    findField(clazz.superclass, fieldName)?.let { return it }
    clazz.interfaces.forEach { iClass ->
        findField(iClass, fieldName)?.let { return it }
    }
    return null
}

private fun String.isNotPrimitiveType() = startsWith("L") || startsWith("[")

private fun isSuspend(owner: String, methodName: String, descriptor: String): Boolean =
    try {
        Class.forName(owner.canonicalClassName).kotlin.declaredFunctions.any {
            it.isSuspend && it.name == methodName && Method.getMethod(it.javaMethod).descriptor == descriptor
        }
    } catch (e: Throwable) {
        // kotlin reflection is not available for some functions
        false
    }

private fun isSuspendStateMachine(internalClassName: String): Boolean {
    // all named suspend functions extend kotlin.coroutines.jvm.internal.ContinuationImpl
    // it is internal, so check by name
    return Class.forName(internalClassName.canonicalClassName).superclass?.name == "kotlin.coroutines.jvm.internal.ContinuationImpl"
}

private fun isStrategyMethod(className: String) = className.startsWith("org/jetbrains/kotlinx/lincheck/strategy")


// returns true only the method is declared in this class and is not inherited
private fun isClassMethod(owner: String, methodName: String, desc: String): Boolean =
    Class.forName(owner.canonicalClassName).declaredMethods.any {
        val method = Method.getMethod(it)
        method.name == methodName && method.descriptor == desc
    }

//private fun isAFUMethodCall(opcode: Int, owner: String, methodName: String, desc: String) =
//    opcode == INVOKEVIRTUAL && isAFU(owner) && isClassMethod(owner, methodName, desc)

private fun String.isUnsafe() = this == "sun/misc/Unsafe" || this == "jdk/internal/misc/Unsafe"

private const val eliminateLocalObjects = true