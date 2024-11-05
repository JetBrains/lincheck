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

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.Method
import org.objectweb.asm.commons.GeneratorAdapter
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.kotlinx.lincheck.transformation.transformers.DeterministicHashCodeTransformer.Companion.hashCodeFilter
import org.jetbrains.kotlinx.lincheck.transformation.transformers.DeterministicRandomTransformer.Companion.randomInstanceMethodsFilter
import org.jetbrains.kotlinx.lincheck.transformation.transformers.DeterministicRandomTransformer.Companion.restRandomMethodsFilter
import org.jetbrains.kotlinx.lincheck.transformation.transformers.DeterministicTimeTransformer.Companion.timeFilter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import sun.nio.ch.lincheck.*
import java.io.PrintStream
import java.lang.reflect.Modifier
import java.util.*
import kotlin.reflect.KClass

/**
 * [DeterministicHashCodeTransformer] tracks invocations of
 * [Object.hashCode] and [System.identityHashCode] methods, and
 * replaces them with the [Injections.hashCodeDeterministic]
 * and [Injections.identityHashCodeDeterministic] calls.
 *
 * This transformation aims to prevent non-determinism due to the native
 * [hashCode] implementation, which typically returns memory address of the
 * object. There is no guarantee that memory addresses will be the same in
 * different runs.
 */
internal class DeterministicHashCodeTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        when {
            overriddenHashCodeFilter.matchesMethodCall(opcode, owner, name, desc, itf) -> {
                invokeIfInTestingCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    code = {
                        invokeStatic(Injections::hashCodeDeterministic)
                    }
                )
            }

            identityHashCodeFilter.matchesMethodCall(opcode, owner, name, desc, itf) -> {
                invokeIfInTestingCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    code = {
                        invokeStatic(Injections::identityHashCodeDeterministic)
                    }
                )
            }

            else -> {
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
        }
    }

    internal companion object {
        private val overriddenHashCodeFilter = MethodFilter { opcode, _, name, desc, _ ->
            (opcode == INVOKEVIRTUAL || opcode == INVOKEINTERFACE) && name == "hashCode" && desc == "()I"
        }
        private val identityHashCodeFilter = MethodFilter { opcode, owner, name, desc, _ ->
            opcode == INVOKESTATIC && owner == "java/lang/System" && name == "identityHashCode" && desc == "(Ljava/lang/Object;)I"
        }
        private val objectsHashCode = MethodFilter { opcode, owner, name, desc, itf ->
            opcode == INVOKESTATIC && owner == "java/util/Objects" && (name == "hashCode" || name == "hash")
        }
        val hashCodeFilter = overriddenHashCodeFilter + identityHashCodeFilter + objectsHashCode
    }
}

/**
 * [DeterministicTimeTransformer] tracks invocations of [System.nanoTime]
 * and [System.currentTimeMillis] methods, and replaces them with stubs to
 * prevent non-determinism.
 */
internal class DeterministicTimeTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (timeFilter.matchesMethodCall(opcode, owner, name, desc, itf)) {
            invokeIfInTestingCode(
                original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                code = { push(1337L) } // any constant value
            )
            return
        }
        visitMethodInsn(opcode, owner, name, desc, itf)
    }

    internal companion object {
        val timeFilter = MethodFilter { _, owner, name, _, _ ->
            owner == "java/lang/System" && (name == "nanoTime" || name == "currentTimeMillis")
        }
    }
}

/**
 * [DeterministicRandomTransformer] tracks invocations of various random
 * number generation functions, such as [Random.nextInt], and replaces them
 * with corresponding [Injections] methods to prevent non-determinism.
 */
internal class DeterministicRandomTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        when {
            nextSecondarySeedOrGetProbe.matchesMethodCall(opcode, owner, name, desc, itf) -> { // INVOKESTATIC
                invokeIfInTestingCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    code = {
                        invokeStatic(Injections::nextInt)
                    }
                )
            }

            advanceProbe.matchesMethodCall(opcode, owner, name, desc, itf) -> { // INVOKEVIRTUAL
                invokeIfInTestingCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    code = {
                        pop()
                        invokeStatic(Injections::nextInt)
                    }
                )
            }

            nextIntTwoInts.matchesMethodCall(opcode, owner, name, desc, itf) -> {
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
            }

            regularRandomMethodFilter.matchesMethodCall(opcode, owner, name, desc, itf) -> {
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
                                     * In Java 21 RandomGenerator interface was introduced,
                                     * so sometimes data structures interact with java.util.Random through this interface.
                                     */
                                    val randomOwner =
                                        if (owner.endsWith("RandomGenerator")) "java/util/random/RandomGenerator" else "java/util/Random"
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
            }

            else -> visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    internal companion object {
        private val randomGeneratorMethods by lazy {
            Random::class.java.interfaces.singleOrNull { it.simpleName == "RandomGenerator" }?.getDeclaredAsmMethods()
        }
        private val declaredRandomMethods by lazy {
            Random::class.getDeclaredAsmMethods()// + randomGeneratorMethods.orEmpty() + kotlin.random.Random::class.getDeclaredAsmMethods()
        }
        private val randomInstanceMethods by lazy {
            (Random::class.java.declaredMethods + kotlin.random.Random::class.java.declaredMethods)
                .filter { !Modifier.isStatic(it.modifiers) }.map(Method::getMethod) + randomGeneratorMethods.orEmpty()
        }
        private val regularRandomMethodFilter = MethodFilter { _, _, name, desc, _ ->
            declaredRandomMethods.any { it.name == name && it.descriptor == desc }
        }
        internal val randomInstanceMethodsFilter = MethodFilter { _, _, name, desc, _ ->
            randomInstanceMethods.any { it.name == name && it.descriptor == desc }
        }
        private val restRandomClasses = setOf(
            "java/util/concurrent/ThreadLocalRandom",
            "java/util/concurrent/atomic/Striped64",
            "java/util/concurrent/atomic/LongAdder",
            "java/util/concurrent/atomic/DoubleAdder",
            "java/util/concurrent/atomic/LongAccumulator",
            "java/util/concurrent/atomic/DoubleAccumulator",
        )
        private val nextSecondarySeedOrGetProbe = MethodFilter { _, owner, name, _, _ ->
            owner in restRandomClasses && (name == "nextSecondarySeed" || name == "getProbe")
        }
        private val advanceProbe =
            MethodFilter { _, owner, name, _, _ -> owner in restRandomClasses && name == "advanceProbe" }
        private val nextIntTwoInts =
            MethodFilter { _, owner, name, desc, _ -> owner in restRandomClasses && name == "nextInt" && desc == "(II)I" }
        internal val restRandomMethodsFilter = nextSecondarySeedOrGetProbe + advanceProbe + nextIntTwoInts
    }
}


private fun Class<*>.getDeclaredAsmMethods() = declaredMethods.map { Method.getMethod(it) }
private fun KClass<*>.getDeclaredAsmMethods() = java.getDeclaredAsmMethods()

internal fun interface MethodFilter {
    fun matchesMethodCall(opcode: Int, owner: String, name: String, desc: String, itf: Boolean): Boolean
}

internal operator fun MethodFilter.plus(other: MethodFilter) = MethodFilter { opcode, owner, name, desc, itf ->
    this.matchesMethodCall(opcode, owner, name, desc, itf) || other.matchesMethodCall(opcode, owner, name, desc, itf)
}

private interface SpecialArgumentHandler {
    fun executeBeforeFirstRun(generatorAdapter: GeneratorAdapter, load: GeneratorAdapter.() -> Unit) {}
    fun executeAfterFirstRun(generatorAdapter: GeneratorAdapter, load: GeneratorAdapter.() -> Unit) {}
    fun executeBeforeFollowingRun(generatorAdapter: GeneratorAdapter, load: GeneratorAdapter.() -> Unit) {}
    fun executeAfterFollowingRun(generatorAdapter: GeneratorAdapter, load: GeneratorAdapter.() -> Unit) {}
}

private val testFilter = MethodFilter { _, owner, _, _, _ ->
    owner == "org/jetbrains/kotlinx/lincheck_test/traceDebugger/FakeNativeCalls"
}

/**
 * [SubstitutionDeterminismTransformer] tracks invocations of general
 * purpose non-deterministic functions by remembering their first value and
 * then substituting it, such as [Random.nextInt], and replaces them with
 * corresponding [Injections] methods to prevent non-determinism.
 */
internal class SubstitutionDeterminismTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    private val equalsFilter = MethodFilter { opcode, owner, name, desc, _ ->
        (opcode == INVOKEVIRTUAL || opcode == INVOKEINTERFACE) && name == "equals" && desc == "(Ljava/lang/Object;)Z"
                || opcode == INVOKESTATIC && owner == "java/util/Objects" && (name == "equals" || name == "deepEquals") && desc == "(Ljava/lang/Object;Ljava/lang/Object;)Z"
    }
    private val equalsIntrinsicFilter = MethodFilter { opcode, owner, name, desc, _ ->
        opcode == INVOKESTATIC && owner == "kotlin/jvm/internal/Intrinsics" && name == "areEqual" && desc == "(Ljava/lang/Object;Ljava/lang/Object;)Z"
    }
    private val toStringFilter = MethodFilter { opcode, owner, name, desc, _ ->
        (opcode == INVOKEVIRTUAL || opcode == INVOKEINTERFACE) && name == "toString" && desc == "()Ljava/lang/String;" &&
                owner != "java/lang/StringBuilder" && owner != "java/io/ByteArrayOutputStream"
                || opcode == INVOKESTATIC && owner == "java/util/Objects" && (name == "toString" || name == "toIdentityString")
                || opcode == INVOKESTATIC && owner == "java/lang/String" && name == "format"
    }
    private val nonDeterministicUnconditionalFilter =
        timeFilter + hashCodeFilter + restRandomMethodsFilter + testFilter + equalsFilter + equalsIntrinsicFilter + toStringFilter
    private val implicitToStringForLastParameterFunctionsFilter = MethodFilter { opcode, owner, name, desc, _ ->
        opcode == INVOKEVIRTUAL && owner == "java/lang/StringBuilder" && (
                name == "append" && desc == "(Ljava/lang/Object;)Ljava/lang/StringBuilder;" ||
                        name == "insert" && desc == "(ILjava/lang/Object;)Ljava/lang/StringBuilder;")
                // todo printStream
    }

    override fun visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        vararg bootstrapMethodArguments: Any?
    ) = adapter.run {
        invokeIfInTestingCode(
            original = { visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments) },
        ) {
            if (bootstrapMethodHandle.owner == "java/lang/invoke/StringConcatFactory") {
                val returnType = Type.getType(String::class.java)
                executeWithDetermination(
                    valueType = returnType,
                    executeOnFirstRun = {
                        visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
                    },
                    executeOnNextRuns = { popArguments(descriptor); getNextEventResultOrThrow(returnType) },
                )
            } else {
                visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
            }
        }
    }

    override fun visitJumpInsn(opcode: Int, label: Label?) = adapter.run {
        if (opcode == IF_ACMPEQ || opcode == IF_ACMPNE) {
            invokeIfInTestingCode(
                original = { visitJumpInsn(opcode, label) },
                code = {
                    ifStatement(
                        condition = { invokeInIgnoredSection { invokeStatic(Injections::isFirstRun) } },
                        ifClause = {
                            invokeInIgnoredSection {
                                invokeStatic(Injections::nextEventAccumulatorId)
                            }
                            val id = newLocal(LONG_TYPE)
                            storeLocal(id)

                            val trueLabel = newLabel()
                            val endLabel = newLabel()
                            visitJumpInsn(opcode, trueLabel)
                            push(false)
                            box(BOOLEAN_TYPE)
                            loadLocal(id)
                            invokeInIgnoredSection {
                                invokeStatic(Injections::storeEventResult)
                            }
                            visitJumpInsn(GOTO, endLabel)
                            visitLabel(trueLabel)
                            push(true)
                            box(BOOLEAN_TYPE)
                            loadLocal(id)
                            invokeInIgnoredSection {
                                invokeStatic(Injections::storeEventResult)
                            }
                            visitJumpInsn(GOTO, label)
                            visitLabel(endLabel)
                        },
                        elseClause = {
                            pop2()
                            ifStatement(
                                condition = { getNextEventResultOrThrow(BOOLEAN_TYPE) },
                                ifClause = { visitJumpInsn(GOTO, label) },
                            )
                        }
                    )
                }
            )
        } else {
            visitJumpInsn(opcode, label)
        }
    }

    @Suppress("unused")
    private fun GeneratorAdapter.logFromStack(type: Type) = invokeInIgnoredSection {
        if (type == VOID_TYPE) {
            visitLdcInsn("<void>")
        } else {
            box(type)
            dup()
            val onEndIf = newLabel()
            ifNonNull(onEndIf)
            pop()
            visitLdcInsn("<null>")
            visitLabel(onEndIf)
        }
        
        val printStreamType = getType(PrintStream::class.java)
        getStatic(getType(System::class.java), "out", printStreamType)
        swap()
        invokeVirtual(printStreamType, Method.getMethod(PrintStream::class.java.getMethod("println", Any::class.java)))
    }

    @Suppress("unused")
    private fun GeneratorAdapter.logConst(message: Any?) = invokeInIgnoredSection {
        val printStreamType = getType(PrintStream::class.java)
        getStatic(getType(System::class.java), "out", printStreamType)
        visitLdcInsn(message.toString())
        invokeVirtual(printStreamType, Method.getMethod(PrintStream::class.java.getMethod("println", Any::class.java)))
    }

    private fun isMethodInsnStatic(opcode: Int) = when (opcode) {
        INVOKESTATIC, INVOKEDYNAMIC -> true
        INVOKEVIRTUAL, INVOKEINTERFACE, INVOKESPECIAL -> false
        else -> error("Unexpected method insn: $opcode")
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (nonDeterministicUnconditionalFilter.matchesMethodCall(opcode, owner, name, desc, itf)) {
            invokeIfInTestingCode(
                original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                code = { instrumentCode(opcode, owner, name, desc, itf) }
            )
        } else if (
            !isMethodInsnStatic(opcode) && randomInstanceMethodsFilter.matchesMethodCall(opcode, owner, name, desc, itf)
        ) {
            invokeIfInTestingCode(
                original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                code = {
                    val args = storeArguments(desc)
                    dup()
                    ifStatement(
                        condition = {
                            invokeStatic(Injections::isRandom)
                        },
                        ifClause = {
                            loadLocals(args)
                            instrumentCode(opcode, owner, name, desc, itf)
                        },
                        elseClause = {
                            loadLocals(args)
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                    )
                }
            )
        } else if (implicitToStringForLastParameterFunctionsFilter.matchesMethodCall(opcode, owner, name, desc, itf)) {
            invokeIfInTestingCode(
                original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                code = {
                    instrumentCode(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false)
                    visitMethodInsn(opcode, owner, name, desc, itf)
                }
            )
        } else {
            visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }
    
    private fun GeneratorAdapter.instrumentCode(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
//            log("$opcode: $owner.$name$desc")
        val specialArgumentHandlers: Map<Int?, SpecialArgumentHandler> =
            getSpecialArgumentHandlers(getType("L$owner;"), getArgumentTypes(desc))
        val returnType = getReturnType(desc)
        executeWithDetermination(
            valueType = returnType,
            executeOnFirstRun = { visitMethodInsn(opcode, owner, name, desc, itf) },
            executeOnNextRuns = { getNextEventResultOrThrow(returnType) },
            wrapCall = { isFirstRun, execute ->
                if (isFirstRun) {
                    callHandlingArguments(
                        opcode = opcode, desc = desc,
                        specialArgumentHandlers = specialArgumentHandlers,
                        executeBeforeRun = SpecialArgumentHandler::executeBeforeFirstRun,
                        executeAfterRun = SpecialArgumentHandler::executeAfterFirstRun,
                        restoreArgumentsOnStackBeforeCall = true,
                    ) {
                        execute()
                    }
                } else {
                    callHandlingArguments(
                        opcode = opcode, desc = desc,
                        specialArgumentHandlers = specialArgumentHandlers,
                        executeBeforeRun = SpecialArgumentHandler::executeBeforeFollowingRun,
                        executeAfterRun = SpecialArgumentHandler::executeAfterFollowingRun,
                        restoreArgumentsOnStackBeforeCall = false,
                    ) {
                        execute()
                    }
                }
            }
        )
    }

    private fun GeneratorAdapter.callHandlingArguments(
        opcode: Int, desc: String,
        specialArgumentHandlers: Map<Int?, SpecialArgumentHandler>,
        executeBeforeRun: SpecialArgumentHandler.(GeneratorAdapter, load: GeneratorAdapter.() -> Unit) -> Unit,
        executeAfterRun: SpecialArgumentHandler.(GeneratorAdapter, load: GeneratorAdapter.() -> Unit) -> Unit,
        restoreArgumentsOnStackBeforeCall: Boolean,
        code: GeneratorAdapter.() -> Unit,
    ) {
        val isInstanceCall = !isMethodInsnStatic(opcode)
        val receiverSpecialHandler = specialArgumentHandlers[null]

        val argumentVariables = storeArguments(desc)

        fun handleArguments(handle: SpecialArgumentHandler.(GeneratorAdapter, load: GeneratorAdapter.() -> Unit) -> Unit) {
            if (isInstanceCall && receiverSpecialHandler != null) {
                receiverSpecialHandler.handle(this) { loadThis() }
            }
            for ((index, variable) in argumentVariables.withIndex()) {
                val handler = specialArgumentHandlers[index] ?: continue
                handler.handle(this) { loadLocal(variable) }
            }
        }

        handleArguments(executeBeforeRun)

        tryCatchFinally(
            tryBlock = {
                if (restoreArgumentsOnStackBeforeCall) {
                    loadLocals(argumentVariables)
                } else if (isInstanceCall) {
                    pop()
                }
                code()
            },
            finallyBlock = { handleArguments(executeAfterRun) }
        )
    }

    private fun getSpecialArgumentHandlers(
        @Suppress("UNUSED_PARAMETER") ownerType: Type, parameterTypes: Array<Type>
    ): Map<Int?, SpecialArgumentHandler> = buildMap {
        for ((index, type) in parameterTypes.withIndex()) {
            if (type == getType(ByteArray::class.java)) {
                put(index, object : SpecialArgumentHandler {
                    override fun executeAfterFirstRun(
                        generatorAdapter: GeneratorAdapter,
                        load: GeneratorAdapter.() -> Unit
                    ) = generatorAdapter.run {
                        load()
                        invokeInIgnoredSection {
                            invokeStatic(Injections::storeParameterValue)
                        }
                    }

                    override fun executeAfterFollowingRun(
                        generatorAdapter: GeneratorAdapter,
                        load: GeneratorAdapter.() -> Unit
                    ) = generatorAdapter.run {
                        load()
                        invokeInIgnoredSection {
                            invokeStatic(Injections::restoreParameterValue)
                        }
                    }
                })
            }
        }
    }

    private fun GeneratorAdapter.executeWithDeterminationIfInTestingCode(
        valueType: Type,
        executeRegularly: GeneratorAdapter.() -> Unit,
        executeOnFirstRun: GeneratorAdapter.() -> Unit = executeRegularly,
        executeOnNextRuns: GeneratorAdapter.() -> Unit,
        wrapCall: GeneratorAdapter.(isFirstRun: Boolean, execute: GeneratorAdapter.() -> Unit) -> Unit = { _, execute -> execute() }
    ) {
        invokeIfInTestingCode(original = executeRegularly) {
            executeWithDetermination(valueType, executeOnFirstRun, executeOnNextRuns, wrapCall)
        }
    }

    private fun GeneratorAdapter.executeWithDetermination(
        valueType: Type,
        executeOnFirstRun: GeneratorAdapter.() -> Unit,
        executeOnNextRuns: GeneratorAdapter.() -> Unit,
        wrapCall: GeneratorAdapter.(isFirstRun: Boolean, execute: GeneratorAdapter.() -> Unit) -> Unit = { _, execute -> execute() },
    ) {
        ifStatement(
            condition = { invokeInIgnoredSection { invokeStatic(Injections::isFirstRun) } },
            ifClause = {
                wrapCall(true) {
                    val id = newLocal(LONG_TYPE)
                    invokeInIgnoredSection {
                        invokeStatic(Injections::nextEventAccumulatorId)
                    }
                    storeLocal(id)
                    tryCatchFinally(
                        tryBlock = {
                            executeOnFirstRun()
                            dup(valueType)
                            box(valueType)
                            loadLocal(id)
                            invokeInIgnoredSection {
                                invokeStatic(Injections::storeEventResult)
                            }
                        },
                        catchBlock = {
                            dup()
                            loadLocal(id)
                            invokeInIgnoredSection {
                                invokeStatic(Injections::storeEventException)
                            }
                            throwException()
                        },
                    )
                }
            },
            elseClause = {
                wrapCall(false) { executeOnNextRuns() }
            },
        )
    }

    private fun GeneratorAdapter.getNextEventResultOrThrow(valueType: Type) {
        invokeInIgnoredSection {
            invokeStatic(Injections::getNextEventResultOrThrow)
        }
        if (valueType.sort == VOID) {
            pop()
        } else {
            unbox(valueType)
        }
    }
}
