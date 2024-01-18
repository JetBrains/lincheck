/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer.TransformationMode
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import org.objectweb.asm.commons.InstructionAdapter.*
import sun.nio.ch.lincheck.*
import org.jetbrains.kotlinx.lincheck.transformation.CoroutineInternalCallTracker.isCoroutineInternalClass
import java.util.*

internal class LincheckClassVisitor(
    private val transformationMode: TransformationMode,
    cw: ClassWriter
) : ClassVisitor(ASM_API, cw) {
    private lateinit var className: String
    private var classVersion = 0
    private var fileName: String? = null

    override fun visitField(
        access: Int,
        fieldName: String,
        descriptor: String?,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        if (access and ACC_FINAL != 0) {
            FinalFields.addFinalField(className, fieldName)
        }
        return super.visitField(access, fieldName, descriptor, signature, value)
    }

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
        methodName: String,
        desc: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor {
        var mv = super.visitMethod(access, methodName, desc, signature, exceptions)
        if (access and ACC_NATIVE != 0) return mv
        if (transformationMode == TransformationMode.STRESS) {
            return if (methodName != "<clinit>" && methodName != "<init>") {
                mv = JSRInlinerAdapter(mv, access, methodName, desc, signature, exceptions)
                CoroutineCancellabilitySupportMethodTransformer(mv, access, methodName, desc)
            } else {
                mv
            }
        }
        if (methodName == "<clinit>") {
            mv = WrapMethodInIgnoreSectionTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
            return mv
        }
        if (methodName == "<init>") {
            mv = ObjectCreationTrackerTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
            return mv
        }
        if (className.contains("ClassLoader")) {
            if (methodName == "loadClass") {
                mv = WrapMethodInIgnoreSectionTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
            }
            return mv
        }
        if (isCoroutineInternalClass(className)) {
            return mv
        }
        mv = JSRInlinerAdapter(mv, access, methodName, desc, signature, exceptions)
        mv = TryCatchBlockSorter(mv, access, methodName, desc, signature, exceptions)
        mv = CoroutineCancellabilitySupportMethodTransformer(mv, access, methodName, desc)
        if (access and ACC_SYNCHRONIZED != 0) {
            mv = SynchronizedMethodTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc), classVersion)
        }
        mv = MethodCallTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        mv = MonitorEnterAndExitTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        mv = WaitNotifyTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        mv = ParkUnparkTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        mv = ObjectCreationTrackerTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        mv = run {
            val sv = SharedVariableAccessMethodTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
            val aa = AnalyzerAdapter(className, access, methodName, desc, sv)
            sv.analyzer = aa
            aa
        }
        mv = DeterministicHashCodeTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        mv = DeterministicTimeTransformer(GeneratorAdapter(mv, access, methodName, desc))
        mv = DeterministicRandomTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
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
                invokeStatic(Injections::storeCancellableContinuation)
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
                            invokeStatic(Injections::lock)
                        }
                    )
                }

                MONITOREXIT -> {
                    invokeIfInTestingCode(
                        original = { monitorExit() },
                        code = {
                            loadNewCodeLocationId()
                            invokeStatic(Injections::unlock)
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
        private val classVersion: Int
    ) : ManagedStrategyMethodVisitor(methodName, mv) {
        private val isStatic: Boolean = adapter.access and ACC_STATIC != 0
        private val tryLabel = Label()
        private val catchLabel = Label()

        override fun visitCode() = adapter.run {
            invokeIfInTestingCode(
                original = {},
                code = {
                    loadSynchronizedMethodMonitorOwner()
                    monitorExit()
                }
            )
            visitLabel(tryLabel)
            invokeIfInTestingCode(
                original = {},
                code = {
                    loadSynchronizedMethodMonitorOwner()
                    loadNewCodeLocationId()
                    invokeStatic(Injections::lock)
                }
            )
            visitCode()
        }

        override fun visitMaxs(maxStack: Int, maxLocals: Int) = adapter.run {
            visitLabel(catchLabel)
            invokeIfInTestingCode(
                original = {},
                code = {
                    loadSynchronizedMethodMonitorOwner()
                    loadNewCodeLocationId()
                    invokeStatic(Injections::unlock)
                    loadSynchronizedMethodMonitorOwner()
                    monitorEnter()
                }
            )
            throwException()
            visitTryCatchBlock(tryLabel, catchLabel, catchLabel, null)
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
                            invokeStatic(Injections::unlock)
                            loadSynchronizedMethodMonitorOwner()
                            monitorEnter()
                        }
                    )
                }
            }
            visitInsn(opcode)
        }

        private fun loadSynchronizedMethodMonitorOwner() = adapter.run {
            if (isStatic) {
                val classType = getType("L$className;")
                if (classVersion >= V1_5) {
                    visitLdcInsn(classType)
                } else {
                    visitLdcInsn(classType.className)
                    invokeInIgnoredSection {
                        invokeStatic(CLASS_TYPE, CLASS_FOR_NAME_METHOD)

                    }
                }
            } else {
                loadThis()
            }
        }
    }

    // TODO: doesn't support exceptions
    private inner class WrapMethodInIgnoreSectionTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {
        private var enteredInIgnoredSectionLocal = 0

        override fun visitCode() = adapter.run {
            enteredInIgnoredSectionLocal = newLocal(BOOLEAN_TYPE)
            invokeStatic(Injections::enterIgnoredSection)
            storeLocal(enteredInIgnoredSectionLocal)
            visitCode()
        }

        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                    ifStatement(
                        condition = { loadLocal(enteredInIgnoredSectionLocal) },
                        ifClause = { invokeStatic(Injections::leaveIgnoredSection) },
                        elseClause = {}
                    )
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
    private inner class DeterministicHashCodeTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (owner == "java/lang/Object" && name == "hashCode") {
                    invokeIfInTestingCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        code = {
                            invokeStatic(Injections::deterministicHashCode)
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

    private companion object {
        private val randomMethods = Random::class.java.declaredMethods.map { Method.getMethod(it) }
    }

    /**
     * Makes java.util.Random and all classes that extend it deterministic.
     * In every Random method invocation replaces the owner with Random from ManagedStateHolder.
     * TODO: Kotlin's random support
     */
    private inner class DeterministicRandomTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (owner == "java/util/concurrent/ThreadLocalRandom" || owner == "java/util/concurrent/atomic/Striped64") {
                    if (name == "nextSecondarySeed" || name == "getProbe") { // INVOKESTATIC
                        invokeIfInTestingCode(
                            original = {
                                visitMethodInsn(opcode, owner, name, desc, itf)
                            },
                            code = {
                                invokeStatic(Injections::nextInt)
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
                                invokeStatic(Injections::nextInt)
                            }
                        )
                        return
                    }
                } else if (isRandomMethod(name, desc)) {
                    invokeIfInTestingCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        code = {
                            val arguments = storeArguments(desc)
                            val ownerLocal = newLocal(getType("L$owner;"))
                            storeLocal(ownerLocal)
                            ifStatement(
                                condition = {
                                    loadLocal(ownerLocal)
                                    invokeStatic(Injections::isRandom)
                                },
                                ifClause = {
                                    invokeInIgnoredSection {
                                        invokeStatic(Injections::deterministicRandom)
                                        loadLocals(arguments)
                                        visitMethodInsn(opcode, "java/util/Random", name, desc, itf)
                                    }
                                },
                                elseClause = {
                                    loadLocal(ownerLocal)
                                    loadLocals(arguments)
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                }
                            )
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
                val isPark = isUnsafe(owner) && name == "park"
                val isUnpark = isUnsafe(owner) && name == "unpark"
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
                                invokeStatic(Injections::park)
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
                                invokeStatic(Injections::unpark)
                                pop() // pop Unsafe object
                            }
                        )
                    }

                    else -> {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    }
                }
            }

            private fun isUnsafe(owner: String) = owner == "sun/misc/Unsafe" || owner == "jdk/internal/misc/Unsafe"
    }


    /**
     * Adds invocations of ManagedStrategy methods before reads and writes of shared variables
     */
    private inner class SharedVariableAccessMethodTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {

        lateinit var analyzer: AnalyzerAdapter

        override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, desc: String) = adapter.run {
            if (isCoroutineInternalClass(owner) || isCoroutineStateMachineClass(owner) || FinalFields.isFinalField(owner, fieldName)) {
                visitFieldInsn(opcode, owner, fieldName, desc)
                return
            }
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
                            invokeStatic(Injections::beforeReadFieldStatic)
                            // STACK: owner: Object
                            visitFieldInsn(opcode, owner, fieldName, desc)
                            // STACK: value
                            invokeAfterRead(getType(desc))
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
                            push(owner)
                            push(fieldName)
                            loadNewCodeLocationId()
                            // STACK: owner: Object, owner: Object, className: String, fieldName: String, codeLocation: Int
                            invokeStatic(Injections::beforeReadField)
                            // STACK: owner: Object
                            visitFieldInsn(opcode, owner, fieldName, desc)
                            // STACK: value
                            invokeAfterRead(getType(desc))
                            // STACK: value
                        }
                    )
                }

                PUTSTATIC -> {
                    // STACK: value: Object
                    invokeIfInTestingCode(
                        original = {},
                        code = {
                            val valueType = getType(desc)
                            val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                            storeTopToLocal(valueLocal)
                            // STACK: value: Object
                            push(owner)
                            push(fieldName)
                            loadLocal(valueLocal)
                            box(valueType)
                            loadNewCodeLocationId()
                            // STACK: value: Object, className: String, fieldName: String, value: Object, codeLocation: Int
                            invokeStatic(Injections::beforeWriteFieldStatic)
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
                            val valueType = getType(desc)
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
                            invokeStatic(Injections::beforeWriteField)
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
                            val arrayElementType = getArrayElementType(opcode)
                            dup2()
                            // STACK: array: Array, index: Int, array: Array, index: Int
                            loadNewCodeLocationId()
                            // STACK: array: Array, index: Int, array: Array, index: Int, codeLocation: Int
                            invokeStatic(Injections::beforeReadArray)
                            // STACK: array: Array, index: Int
                            visitInsn(opcode)
                            // STACK: value
                            invokeAfterRead(arrayElementType)
                            // STACK: value
                        }
                    )
                }

                AASTORE, IASTORE, FASTORE, BASTORE, CASTORE, SASTORE, LASTORE, DASTORE -> {
                    invokeIfInTestingCode(
                        original = {},
                        code = {
                            // STACK: array: Array, index: Int, value: Object
                            val arrayElementType = getArrayElementType(opcode)
                            val valueLocal = newLocal(arrayElementType) // we cannot use DUP as long/double require DUP2
                            storeLocal(valueLocal)
                            // STACK: array: Array, index: Int
                            dup2()
                            // STACK: array: Array, index: Int, array: Array, index: Int
                            loadLocal(valueLocal)
                            box(arrayElementType)
                            loadNewCodeLocationId()
                            // STACK: array: Array, index: Int, array: Array, index: Int, value: Object, codeLocation: Int
                            invokeStatic(Injections::beforeWriteArray)
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

        private fun GeneratorAdapter.invokeAfterRead(valueType: Type) {
            // STACK: value
            val resultLocal = newLocal(valueType)
            storeTopToLocal(resultLocal)
            loadLocal(resultLocal)
            // STACK: value, value
            box(valueType)
            invokeStatic(Injections::afterRead)
            // STACK: value
        }

        private fun getArrayElementType(opcode: Int): Type = when (opcode) {
            // Load
            AALOAD -> getArrayAccessTypeFromStack(2) // OBJECT_TYPE
            IALOAD -> INT_TYPE
            FALOAD -> FLOAT_TYPE
            BALOAD -> BOOLEAN_TYPE
            CALOAD -> CHAR_TYPE
            SALOAD -> SHORT_TYPE
            LALOAD -> LONG_TYPE
            DALOAD -> DOUBLE_TYPE
            // Store
            AASTORE -> getArrayAccessTypeFromStack(3) // OBJECT_TYPE
            IASTORE -> INT_TYPE
            FASTORE -> FLOAT_TYPE
            BASTORE -> BOOLEAN_TYPE
            CASTORE -> CHAR_TYPE
            SASTORE -> SHORT_TYPE
            LASTORE -> LONG_TYPE
            DASTORE -> DOUBLE_TYPE
            else -> throw IllegalStateException("Unexpected opcode: $opcode")
        }

        /*
       * Tries to obtain the type of array elements by inspecting the type of the array itself.
       * In order to do this queries the analyzer to get the type of accessed array
       * which should lie on the stack. If the analyzer does not know the type
       * (according to the ASM docs it can happen, for example, when the visited instruction is unreachable)
       * then return null.
       */
        private fun getArrayAccessTypeFromStack(position: Int): Type {
            if (analyzer.stack == null) return OBJECT_TYPE // better than throwing an exception
            val arrayDesc = analyzer.stack[analyzer.stack.size - position]
            check(arrayDesc is String)
            val arrayType = getType(arrayDesc)
            check(arrayType.sort == ARRAY)
            check(arrayType.dimensions > 0)
            return getType(arrayDesc.substring(1))
        }
    }

    private inner class ObjectCreationTrackerTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (name == "<init>" && owner == "java/lang/Object") {
                    invokeIfInTestingCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        code = {
                            val objectLocal = newLocal(OBJECT_TYPE)
                            dup()
                            storeLocal(objectLocal)
                            visitMethodInsn(opcode, owner, name, desc, itf)
                            loadLocal(objectLocal)
                            invokeStatic(Injections::onNewObjectCreation)
                        }
                    )
                } else {
                    visitMethodInsn(opcode, owner, name, desc, itf)
                }
            }

        override fun visitIntInsn(opcode: Int, operand: Int) = adapter.run {
            adapter.visitIntInsn(opcode, operand)
            if (opcode == NEWARRAY) {
                invokeIfInTestingCode(
                    original = {},
                    code = {
                        dup()
                        invokeStatic(Injections::onNewObjectCreation)
                    }
                )
            }
        }

        override fun visitTypeInsn(opcode: Int, type: String) = adapter.run {
            visitTypeInsn(opcode, type)
            if (opcode == ANEWARRAY) {
                invokeIfInTestingCode(
                    original = {},
                    code = {
                        dup()
                        invokeStatic(Injections::onNewObjectCreation)
                    }
                )
            }
        }

        override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) = adapter.run {
            visitMultiANewArrayInsn(descriptor, numDimensions)
            invokeIfInTestingCode(
                original = {},
                code = {
                    dup()
                    invokeStatic(Injections::onNewObjectCreation)
                }
            )
        }
    }

    /**
     * Adds strategy method invocations before and after method calls.
     */
    private inner class MethodCallTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                // TODO: ignore coroutine internals
                // TODO: ignore safe calls
                // TODO: do not ignore <init>?
                if (isCoroutineInternalClass(owner)) {
                    invokeInIgnoredSection {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    }
                    return
                }
                if (name == "<init>" ||
                    owner == "kotlin/jvm/internal/Intrinsics" ||
                    owner == "java/util/Objects" ||
                    owner == "sun/nio/ch/lincheck/Injections" ||
                    owner == "java/lang/StringBuilder" ||
                    owner == "java/util/Locale" ||
                    owner == "java/lang/String" ||
                    owner == "org/slf4j/helpers/Util" ||
                    owner == "java/util/Properties" ||
                    owner == "java/lang/Boolean" ||
                    owner == "java/lang/Integer" ||
                    owner == "java/lang/Long"
                ) {
                    visitMethodInsn(opcode, owner, name, desc, itf)
                    return
                }
                if (owner.startsWith("java/util/concurrent/") && (owner.contains("Atomic")) ||
                    owner == "sun/misc/Unsafe" || owner == "jdk/internal/misc/Unsafe" ||
                    owner == "java/lang/invoke/VarHandle") {
                    val isAtomicUpdaterMethod = owner.endsWith("FieldUpdater")
                    invokeIfInTestingCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        code = {
                            processAtomicMethodCall(desc, opcode, owner, name, itf, isAtomicUpdaterMethod)
                        }
                    )
                    return
                }
                invokeIfInTestingCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    code = {
                        val endLabel = newLabel()
                        val methodCallStartLabel = newLabel()
                        val defaultFlowLabel = newLabel()
                        // STACK [INVOKEVIRTUAL]: owner, arguments
                        // STACK [INVOKESTATIC]: arguments
                        val argumentLocals = storeArguments(desc)
                        // STACK [INVOKEVIRTUAL]: owner
                        // STACK [INVOKESTATIC]: <empty>
                        when (opcode) {
                            INVOKESTATIC -> visitInsn(ACONST_NULL)
                            else -> dup()
                        }
                        // STACK [INVOKEVIRTUAL]: owner, owner
                        // STACK [INVOKESTATIC]:         null
                        push(owner)
                        push(name)
                        // STACK [INVOKEVIRTUAL]: owner, owner, className, methodName
                        // STACK [INVOKESTATIC]:          null, className, methodName
                        invokeStatic(Injections::methodGuaranteeType)
                        // STACK [INVOKEVIRTUAL]: owner, guarantee(0/1/2)
                        // STACK [INVOKESTATIC]:          guarantee(0/1/2)
                        val labelIfNoGuarantee = Label()
                        dup()
                        // STACK [INVOKEVIRTUAL]: owner, guarantee(0/1/2), guarantee(0/1/2)
                        // STACK [INVOKESTATIC]:         guarantee(0/1/2), guarantee(0/1/2)
                        visitInsn(ICONST_0)
                        // STACK [INVOKEVIRTUAL]: owner, guarantee(0/1/2), guarantee(0/1/2), 0
                        // STACK [INVOKESTATIC]:         guarantee(0/1/2), guarantee(0/1/2), 0
                        ifICmp(GeneratorAdapter.EQ, labelIfNoGuarantee)
                        // STACK [INVOKEVIRTUAL]: owner, guarantee(0/1/2)
                        // STACK [INVOKESTATIC]:         guarantee(0/1/2)
                        visitInsn(ICONST_1)
                        val threatAsAtomicOperationLabel = newLabel()
                        val threadAsIgnoredOperationLabel = newLabel()
                        // STACK [INVOKEVIRTUAL]: owner, guarantee(0/1/2), 1
                        // STACK [INVOKESTATIC]:         guarantee(0/1/2), 1
                        ifICmp(GeneratorAdapter.EQ, threadAsIgnoredOperationLabel)
                        goTo(threatAsAtomicOperationLabel)

                        // STACK [INVOKEVIRTUAL]: owner
                        // STACK [INVOKESTATIC]: <empty>
                        visitLabel(threatAsAtomicOperationLabel)
                        loadLocals(argumentLocals)
                        // STACK [INVOKEVIRTUAL]: owner, arguments
                        // STACK [INVOKESTATIC]: arguments
                        processAtomicMethodCall(desc, opcode, owner, name, itf, false)
                        goTo(endLabel)

                        visitLabel(threadAsIgnoredOperationLabel)
                        // STACK [INVOKEVIRTUAL]: owner
                        // STACK [INVOKESTATIC]: <empty>
                        loadLocals(argumentLocals)
                        // STACK [INVOKEVIRTUAL]: owner, arguments
                        // STACK [INVOKESTATIC]: arguments
                        invokeInIgnoredSection {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        }
                        goTo(endLabel)

                        visitLabel(labelIfNoGuarantee)
                        // STACK [INVOKEVIRTUAL]: owner, guarantee(0/1/2)
                        // STACK [INVOKESTATIC]:         guarantee(0/1/2)
                        pop()
                        // STACK [INVOKEVIRTUAL]: owner
                        // STACK [INVOKESTATIC]: <empty>
                        goTo(defaultFlowLabel)

                        visitLabel(defaultFlowLabel)
                        // STACK [INVOKEVIRTUAL]: owner
                        // STACK [INVOKESTATIC]: <empty>
                        when (opcode) {
                            INVOKESTATIC -> visitInsn(ACONST_NULL)
                            else -> dup()
                        }
                        push(owner)
                        push(name)
                        loadNewCodeLocationId()
                        // STACK [INVOKEVIRTUAL]: owner, owner, className, methodName, codeLocation
                        // STACK [INVOKESTATIC]:         null, className, methodName, codeLocation
                        val argumentTypes = getArgumentTypes(desc)
                        when (argumentLocals.size) {
                            0 -> {
                                invokeStatic(Injections::beforeMethodCall0)
                            }

                            1 -> {
                                loadLocalsBoxed(argumentLocals, argumentTypes)
                                invokeStatic(Injections::beforeMethodCall1)
                            }

                            2 -> {
                                loadLocalsBoxed(argumentLocals, argumentTypes)
                                invokeStatic(Injections::beforeMethodCall2)
                            }

                            3 -> {
                                loadLocalsBoxed(argumentLocals, argumentTypes)
                                invokeStatic(Injections::beforeMethodCall3)
                            }

                            4 -> {
                                loadLocalsBoxed(argumentLocals, argumentTypes)
                                invokeStatic(Injections::beforeMethodCall4)
                            }

                            5 -> {
                                loadLocalsBoxed(argumentLocals, argumentTypes)
                                invokeStatic(Injections::beforeMethodCall5)
                            }

                            else -> {
                                push(argumentLocals.size) // size of the array
                                visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
                                // STACK: ..., array
                                for (i in argumentLocals.indices) {
                                    // STACK: ..., array
                                    dup()
                                    // STACK: ..., array, array
                                    push(i)
                                    // STACK: ..., array, array, index
                                    loadLocal(argumentLocals[i])
                                    // STACK: ..., array, array, index, argument[index]
                                    box(argumentTypes[i])
                                    arrayStore(OBJECT_TYPE)
                                    // STACK: ..., array
                                }
                                // STACK: ..., array
                                invokeStatic(Injections::beforeMethodCall)
                            }
                        }
                        // STACK [INVOKEVIRTUAL]: owner, arguments
                        // STACK [INVOKESTATIC]: arguments
                        val methodCallEndLabel = newLabel()
                        val handlerExceptionStartLabel = newLabel()
                        val handlerExceptionEndLabel = newLabel()
                        visitTryCatchBlock(methodCallStartLabel, methodCallEndLabel, handlerExceptionStartLabel, null)
                        visitLabel(methodCallStartLabel)
                        loadLocals(argumentLocals)
                        visitMethodInsn(opcode, owner, name, desc, itf)
                        visitLabel(methodCallEndLabel)
                        // STACK [INVOKEVIRTUAL]: owner, arguments
                        // STACK [INVOKESTATIC]: arguments
                        val resultType = getReturnType(desc)
                        if (resultType == VOID_TYPE) {
                            invokeStatic(Injections::onMethodCallVoidFinishedSuccessfully)
                        } else {
                            val resultLocal = newLocal(resultType)
                            storeLocal(resultLocal)
                            loadLocal(resultLocal)
                            box(resultType)
                            invokeStatic(Injections::onMethodCallFinishedSuccessfully)
                            loadLocal(resultLocal)
                        }
                        // STACK: value
                        goTo(handlerExceptionEndLabel)
                        visitLabel(handlerExceptionStartLabel)
                        dup()
                        invokeStatic(Injections::onMethodCallThrewException)
                        throwException()
                        visitLabel(handlerExceptionEndLabel)
                        // STACK: value
                        visitLabel(endLabel)
                    }
                )
            }

        private fun GeneratorAdapter.processAtomicMethodCall(
            desc: String,
            opcode: Int,
            owner: String,
            name: String,
            itf: Boolean,
            isAtomicUpdaterMethod: Boolean
        ) {
            // STACK [INVOKEVIRTUAL]: owner, arguments
            // STACK [INVOKESTATIC]: arguments
            val argumentLocals = storeArguments(desc)
            // STACK [INVOKEVIRTUAL]: owner
            // STACK [INVOKESTATIC]: <empty>
            if (isAtomicUpdaterMethod) {
                when (opcode) {
                    INVOKESTATIC -> visitInsn(ACONST_NULL)
                    else -> dup()
                }
            } else {
                push(owner)
            }
            // STACK [INVOKEVIRTUAL atomic updater]: owner, owner
            // STACK [INVOKESTATIC atomic updater]: <empty>, null

            // STACK [INVOKEVIRTUAL atomic]: owner, ownerName
            // STACK [INVOKESTATIC atomic]: <empty>, ownerName
            push(name)
            loadNewCodeLocationId()
            // STACK [INVOKEVIRTUAL atomic updater]: owner, owner, methodName, codeLocation
            // STACK [INVOKESTATIC atomic updater]:         null, methodName, codeLocation

            // STACK [INVOKEVIRTUAL atomic]: owner, ownerName, methodName, codeLocation
            // STACK [INVOKESTATIC atomic]:         ownerName, methodName, codeLocation
            val argumentTypes = getArgumentTypes(desc)
            when (argumentLocals.size) {
                0 -> {
                    invokeStatic(if (!isAtomicUpdaterMethod) Injections::beforeAtomicMethodCall0 else Injections::beforeAtomicUpdaterMethodCall0)
                }

                1 -> {
                    loadLocalsBoxed(argumentLocals, argumentTypes)
                    invokeStatic(if (!isAtomicUpdaterMethod) Injections::beforeAtomicMethodCall1 else Injections::beforeAtomicUpdaterMethodCall1)
                }

                2 -> {
                    loadLocalsBoxed(argumentLocals, argumentTypes)
                    invokeStatic(if (!isAtomicUpdaterMethod) Injections::beforeAtomicMethodCall2 else Injections::beforeAtomicUpdaterMethodCall2)
                }

                3 -> {
                    loadLocalsBoxed(argumentLocals, argumentTypes)
                    invokeStatic(if (!isAtomicUpdaterMethod) Injections::beforeAtomicMethodCall3 else Injections::beforeAtomicUpdaterMethodCall3)
                }

                4 -> {
                    loadLocalsBoxed(argumentLocals, argumentTypes)
                    invokeStatic(if (!isAtomicUpdaterMethod) Injections::beforeAtomicMethodCall4 else Injections::beforeAtomicUpdaterMethodCall4)
                }

                5 -> {
                    loadLocalsBoxed(argumentLocals, argumentTypes)
                    invokeStatic(if (!isAtomicUpdaterMethod) Injections::beforeAtomicMethodCall5 else Injections::beforeAtomicUpdaterMethodCall5)
                }

                else -> {
                    push(argumentLocals.size) // size of the array
                    visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
                    // STACK: ..., array
                    for (i in argumentLocals.indices) {
                        // STACK: ..., array
                        dup()
                        // STACK: ..., array, array
                        push(i)
                        // STACK: ..., array, array, index
                        loadLocal(argumentLocals[i])
                        // STACK: ..., array, array, index, argument[index]
                        box(argumentTypes[i])
                        arrayStore(OBJECT_TYPE)
                        // STACK: ..., array
                    }
                    // STACK: ..., array
                    invokeStatic(if (!isAtomicUpdaterMethod) Injections::beforeAtomicMethodCall else Injections::beforeAtomicUpdaterMethodCall)
                }
            }

            // STACK [INVOKEVIRTUAL]: owner, arguments
            // STACK [INVOKESTATIC]: arguments
            loadLocals(argumentLocals)
            invokeInIgnoredSection {
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
            // STACK [INVOKEVIRTUAL]: owner, arguments
            // STACK [INVOKESTATIC]: arguments
            val resultType = getReturnType(desc)
            if (resultType == VOID_TYPE) {
                invokeStatic(Injections::onMethodCallVoidFinishedSuccessfully)
            } else {
                val resultLocal = newLocal(resultType)
                storeLocal(resultLocal)
                loadLocal(resultLocal)
                box(resultType)
                invokeStatic(Injections::onMethodCallFinishedSuccessfully)
                loadLocal(resultLocal)
            }
            // STACK: value
        }
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
                                    loadNewCodeLocationId()
                                    invokeStatic(Injections::wait)
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
                                    loadNewCodeLocationId()
                                    invokeStatic(Injections::waitWithTimeout)
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
                                    loadNewCodeLocationId()
                                    invokeStatic(Injections::waitWithTimeout)
                                }
                            )
                        }

                        isNotify(name, desc) -> {
                            invokeIfInTestingCode(
                                original = {
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                },
                                code = {
                                    loadNewCodeLocationId()
                                    invokeStatic(Injections::notify)
                                }
                            )
                        }

                        isNotifyAll(name, desc) -> {
                            invokeIfInTestingCode(
                                original = {
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                },
                                code = {
                                    loadNewCodeLocationId()
                                    invokeStatic(Injections::notifyAll)
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

private object CoroutineInternalCallTracker {
    private val coroutineInternalClasses = HashSet<String>()

    init {
        coroutineInternalClasses += "kotlinx/coroutines/CancellableContinuationImpl"
        coroutineInternalClasses += "kotlin/coroutines/jvm/internal/ContinuationImpl"
        coroutineInternalClasses += "kotlin/coroutines/intrinsics/IntrinsicsKt"
        coroutineInternalClasses += "kotlinx/coroutines/internal/StackTraceRecoveryKt"
    }

    fun isCoroutineInternalClass(internalClassName: String): Boolean =
        // TODO: should we ignore all coroutine internals?
        internalClassName in coroutineInternalClasses || internalClassName.startsWith("kotlinx/coroutines/internal/")
}
