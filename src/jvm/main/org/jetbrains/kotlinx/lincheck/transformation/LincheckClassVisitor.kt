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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import org.objectweb.asm.commons.InstructionAdapter.*
import org.jetbrains.kotlinx.lincheck.transformation.CoroutineInternalCallTracker.isCoroutineInternalClass
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.*
import sun.nio.ch.lincheck.*
import sun.nio.ch.lincheck.Injections.*
import java.util.*

internal class LincheckClassVisitor(
    private val instrumentationMode: InstrumentationMode,
    classVisitor: ClassVisitor
) : ClassVisitor(ASM_API, classVisitor) {
    private val ideaPluginEnabled = ideaPluginEnabled()
    private var classVersion = 0

    private lateinit var fileName: String
    private lateinit var className: String

    override fun visitField(
        access: Int,
        fieldName: String,
        descriptor: String?,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        if (access and ACC_FINAL != 0) {
            FinalFields.addFinalField(className, fieldName)
        } else {
            FinalFields.addMutableField(className, fieldName)
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
        if (instrumentationMode == STRESS) {
            return if (methodName != "<clinit>" && methodName != "<init>") {
                CoroutineCancellabilitySupportMethodTransformer(mv, access, methodName, desc)
            } else {
                mv
            }
        }
        val createAdapter : (MethodVisitor) -> GeneratorAdapter = {
            GeneratorAdapter(it, access, methodName, desc)
        }
        if (methodName == "<clinit>" ||
            // Debugger implicitly evaluates toString for variables rendering
            // We need to disable breakpoints in such a case, as the numeration will break.
            // Breakpoints are disabled as we do not instrument toString and enter an ignored section,
            // so there are no beforeEvents inside.
            ideaPluginEnabled && methodName == "toString" && desc == "()Ljava/lang/String;") {
            mv = WrapMethodInIgnoredSectionTransformer(fileName, className, methodName, createAdapter(mv))
            return mv
        }
        if (methodName == "<init>") {
            mv = ObjectCreationTrackerTransformer(fileName, className, methodName, createAdapter(mv))
            return mv
        }
        if (className.contains("ClassLoader")) {
            if (methodName == "loadClass") {
                mv = WrapMethodInIgnoredSectionTransformer(fileName, className, methodName, createAdapter(mv))
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
            mv = SynchronizedMethodTransformer(fileName, className, methodName, createAdapter(mv), classVersion)
        }
        mv = MethodCallTransformer(fileName, className, methodName, createAdapter(mv))
        mv = MonitorEnterAndExitTransformer(fileName, className, methodName, createAdapter(mv))
        mv = WaitNotifyTransformer(fileName, className, methodName, createAdapter(mv))
        mv = ParkUnparkTransformer(fileName, className, methodName, createAdapter(mv))
        mv = ObjectCreationTrackerTransformer(fileName, className, methodName, createAdapter(mv))
        mv = UnsafeMethodTransformer(fileName, className, methodName, createAdapter(mv))
        mv = AtomicFieldUpdaterMethodTransformer(fileName, className, methodName, createAdapter(mv))
        mv = VarHandleMethodTransformer(fileName, className, methodName, createAdapter(mv))
        mv = run {
            val sv = SharedVariableAccessMethodTransformer(fileName, className, methodName, createAdapter(mv))
            val aa = AnalyzerAdapter(className, access, methodName, desc, sv)
            sv.analyzer = aa
            aa
        }
        mv = DeterministicHashCodeTransformer(fileName, className, methodName, createAdapter(mv))
        mv = DeterministicTimeTransformer(createAdapter(mv))
        mv = DeterministicRandomTransformer(fileName, className, methodName, createAdapter(mv))
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
            val isGetResult = "getResult" == methodName &&
                    ("kotlinx/coroutines/CancellableContinuation" == className || "kotlinx/coroutines/CancellableContinuationImpl" == className)
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
    private inner class MonitorEnterAndExitTransformer(
        fileName: String,
        className: String,
        methodName: String,
        adapter: GeneratorAdapter,
    ) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                MONITORENTER -> {
                    invokeIfInTestingCode(
                        original = { monitorEnter() },
                        code = {
                            loadNewCodeLocationId()
                            invokeStatic(Injections::beforeLock)
                            invokeBeforeEventIfPluginEnabled("lock")
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
                            invokeBeforeEventIfPluginEnabled("unlock")
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
        fileName: String,
        className: String,
        methodName: String,
        adapter: GeneratorAdapter,
        private val classVersion: Int
    ) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
        private val isStatic: Boolean = this.adapter.access and ACC_STATIC != 0
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
                    invokeStatic(Injections::beforeLock)
                    invokeBeforeEventIfPluginEnabled("lock")
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
                    invokeBeforeEventIfPluginEnabled("unlock")
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
                            invokeBeforeEventIfPluginEnabled("unlock")
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
    private inner class WrapMethodInIgnoredSectionTransformer(
        fileName: String,
        className: String,
        methodName: String,
        adapter: GeneratorAdapter,
    ) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
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
    private inner class DeterministicHashCodeTransformer(
        fileName: String,
        className: String,
        methodName: String,
        adapter: GeneratorAdapter,
    ) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (name == "hashCode" && desc == "()I") {
                    invokeIfInTestingCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        code = {
                            invokeStatic(Injections::hashCodeDeterministic)
                        }
                    )
                } else if (owner == "java/lang/System" && name == "identityHashCode" && desc == "(Ljava/lang/Object;)I") {
                    invokeIfInTestingCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        code = {
                            invokeStatic(Injections::identityHashCodeDeterministic)
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
     */
    private inner class DeterministicRandomTransformer(
        fileName: String,
        className: String,
        methodName: String,
        adapter: GeneratorAdapter,
    ) :
        ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (owner == "java/util/concurrent/ThreadLocalRandom" ||
                    owner == "java/util/concurrent/atomic/Striped64" ||
                    owner == "java/util/concurrent/atomic/LongAdder" ||
                    owner == "java/util/concurrent/atomic/DoubleAdder" ||
                    owner == "java/util/concurrent/atomic/LongAccumulator" ||
                    owner == "java/util/concurrent/atomic/DoubleAccumulator"
                ) {
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
                    if (name == "nextInt" && desc == "(II)I") {
                        invokeIfInTestingCode(
                            original = {
                                visitMethodInsn(opcode, owner, name, desc, itf)
                            },
                            code = {
                                val arguments = storeArguments(desc)
                                pop()
                                loadLocals(arguments)
                                invokeStatic(Injections::nextInt2)
                            }
                        )
                        return
                    }
                }
                if (isRandomMethod(name, desc)) {
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
                                        /*
                                        In Java 21 RandomGenerator interface was introduced so sometimes data structures
                                        interact with java.util.Random through this interface.
                                         */
                                        val randomOwner = if (owner.endsWith("RandomGenerator")) "java/util/random/RandomGenerator" else "java/util/Random"
                                        visitMethodInsn(opcode, randomOwner, name, desc, itf)
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

    private companion object {
        private val randomMethods =
            Random::class.java.declaredMethods.map { Method.getMethod(it) }
    }

    /**
     * Adds invocations of ManagedStrategy methods before park and after unpark calls
     */
    private inner class ParkUnparkTransformer(
        fileName: String,
        className: String,
        methodName: String,
        adapter: GeneratorAdapter
    ) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
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
                                invokeBeforeEventIfPluginEnabled("park")
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
                                invokeBeforeEventIfPluginEnabled("unpark")
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

    private inner class ObjectCreationTrackerTransformer(
        fileName: String,
        className: String,
        methodName: String,
        adapter: GeneratorAdapter
    ) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
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
                            invokeStatic(Injections::afterNewObjectCreation)
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
                        invokeStatic(Injections::afterNewObjectCreation)
                    }
                )
            }
        }

        override fun visitTypeInsn(opcode: Int, type: String) = adapter.run {
            if (opcode == NEW) {
                invokeIfInTestingCode(
                    original = {},
                    code = {
                        push(type.canonicalClassName)
                        invokeStatic(Injections::beforeNewObjectCreation)
                    }
                )
            }
            visitTypeInsn(opcode, type)
            if (opcode == ANEWARRAY) {
                invokeIfInTestingCode(
                    original = {},
                    code = {
                        dup()
                        invokeStatic(Injections::afterNewObjectCreation)
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
                    invokeStatic(Injections::afterNewObjectCreation)
                }
            )
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before wait and after notify calls
     */
    private inner class WaitNotifyTransformer(
        fileName: String,
        className: String,
        methodName: String,
        adapter: GeneratorAdapter,
    ) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
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
                                    invokeStatic(Injections::beforeWait)
                                    invokeBeforeEventIfPluginEnabled("wait")
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
                                    invokeStatic(Injections::beforeWait)
                                    invokeBeforeEventIfPluginEnabled("wait 1")
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
                                    invokeStatic(Injections::beforeWait)
                                    invokeBeforeEventIfPluginEnabled("wait 2")
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
                                    invokeBeforeEventIfPluginEnabled("notify")
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
                                    invokeBeforeEventIfPluginEnabled("notifyAll")
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

}

internal open class ManagedStrategyMethodVisitor(
    protected val fileName: String,
    protected val className: String,
    protected val methodName: String,
    val adapter: GeneratorAdapter
) : MethodVisitor(ASM_API, adapter) {

    private val ideaPluginEnabled = ideaPluginEnabled()

    private var lineNumber = 0

    /**
     * Injects `beforeEvent` method invocation if IDEA plugin is enabled.
     *
     * @param type type of the event, needed just for debugging.
     * @param setMethodEventId a flag that identifies that method call event id set is required
     */
    protected fun invokeBeforeEventIfPluginEnabled(type: String, setMethodEventId: Boolean = false) {
        if (ideaPluginEnabled) {
            adapter.invokeBeforeEvent(type, setMethodEventId)
        }
    }

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

internal object CoroutineInternalCallTracker {
    private val coroutineInternalClasses = HashSet<String>()

    init {
        coroutineInternalClasses += "kotlin/coroutines/intrinsics/IntrinsicsKt"
        coroutineInternalClasses += "kotlinx/coroutines/internal/StackTraceRecoveryKt"
    }

    fun isCoroutineInternalClass(internalClassName: String): Boolean = internalClassName in coroutineInternalClasses
}