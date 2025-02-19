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

import org.jetbrains.kotlinx.lincheck.strategy.managed.DeterministicCall
import org.jetbrains.kotlinx.lincheck.strategy.managed.invoke
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type.getType
import org.objectweb.asm.commons.Method
import org.objectweb.asm.commons.GeneratorAdapter
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.Opcodes
import sun.nio.ch.lincheck.*
import java.lang.reflect.Modifier
import java.util.*

/**
 * [DeterministicHashCodeTransformer] tracks invocations of [Object.hashCode] and [System.identityHashCode] methods,
 * and replaces them with the [Injections.hashCodeDeterministic] and [Injections.identityHashCodeDeterministic] calls.
 *
 * This transformation aims to prevent non-determinism due to the native [hashCode] implementation,
 * which typically returns memory address of the object.
 * There is no guarantee that memory addresses will be the same in different runs.
 */
internal class DeterministicHashCodeTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        when {
            name == "hashCode" && desc == "()I" -> {
                invokeIfInTestingCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    code = {
                        invokeStatic(Injections::hashCodeDeterministic)
                    }
                )
            }

            owner == "java/lang/System" && name == "identityHashCode" && desc == "(Ljava/lang/Object;)I" -> {
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

}

/**
 * [FakeDeterministicTimeTransformer] tracks invocations of [System.nanoTime] and [System.currentTimeMillis] methods,
 * and replaces them with stubs to prevent non-determinism.
 */
internal class FakeDeterministicTimeTransformer(adapter: GeneratorAdapter) : AbstractDeterministicTimeMethodTransformer(adapter) {
    override fun GeneratorAdapter.generateInstrumentedCode(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
        push(1337L)// any constant value
    }
}

internal class TrueDeterministicTimeTransformer(adapter: GeneratorAdapter) : AbstractDeterministicTimeMethodTransformer(adapter) {
    private class DeterministicTimeCall(private val originalCall: GeneratorAdapter.() -> Unit) : DeterministicCall {
        override fun invokeFromState(
            generator: GeneratorAdapter,
            getState: GeneratorAdapter.() -> Unit,
            getReceiver: (GeneratorAdapter.() -> Unit)?,
            getArgument: GeneratorAdapter.(Int) -> Unit,
        ) = generator.run {
            getState()
        }

        override fun invokeSavingState(
            generator: GeneratorAdapter,
            saveState: GeneratorAdapter.(GeneratorAdapter.() -> Unit) -> Unit,
            getReceiver: (GeneratorAdapter.() -> Unit)?,
            getArgument: GeneratorAdapter.(Int) -> Unit,
        ) = generator.run {
            originalCall()
            val result = newLocal(longType)
            storeLocal(result)
            saveState { loadLocal(result) }
            loadLocal(result)
        }
    }
    
    override fun GeneratorAdapter.generateInstrumentedCode(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
        val deterministicCall = DeterministicTimeCall { visitMethodInsn(opcode, owner, name, desc, itf) }
        invoke(deterministicCall, longType, opcode, owner, desc)
    }
    
    companion object {
        private val longType = getType(Long::class.java)
    }
}

internal abstract class AbstractDeterministicTimeMethodTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
    protected abstract fun GeneratorAdapter.generateInstrumentedCode(opcode: Int, owner: String, name: String, desc: String, itf: Boolean)
    final override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (owner == "java/lang/System" && (name == "nanoTime" || name == "currentTimeMillis")) {
            invokeIfInTestingCode(
                original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                code = { generateInstrumentedCode(opcode, owner, name, desc, itf) }
            )
        } else {
            visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }
}

internal abstract class AbstractDeterministicRandomTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
    protected abstract fun GeneratorAdapter.generateInstrumentedCodeForNextSecondarySeedAndGetProbe(
        opcode: Int, owner: String, name: String, desc: String, itf: Boolean,
    )

    protected abstract fun GeneratorAdapter.generateInstrumentedCodeForAdvanceProbe(
        opcode: Int, owner: String, name: String, desc: String, itf: Boolean,
    )

    protected abstract fun GeneratorAdapter.generateInstrumentedCodeForRegularRandomMethod(
        opcode: Int, owner: String, name: String, desc: String, itf: Boolean,
        receiverLocal: Int,
        argumentsLocals: IntArray,
    )

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
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
                        generateInstrumentedCodeForNextSecondarySeedAndGetProbe(opcode, owner, name, desc, itf)
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
                        generateInstrumentedCodeForAdvanceProbe(opcode, owner, name, desc, itf)
                    }
                )
                return
            }
        }
        if (isRandomMethod(opcode, name, desc)) {
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
                        thenClause = {
                            generateInstrumentedCodeForRegularRandomMethod(opcode, owner, name, desc, itf, ownerLocal, arguments)
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

    private fun isRandomMethod(opcode: Int, methodName: String, desc: String): Boolean = allRandomMethods.any {
        opcode.isInstanceMethodOpcode && it.name == methodName && it.descriptor == desc
    }

    private companion object {
        private val randomGeneratorClass = runCatching { Class.forName("java.util.random.RandomGenerator") }.getOrNull()
        private fun Class<*>.getMethodsToReplace() = declaredMethods
            .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
            .map { Method.getMethod(it) }
        private val randomGeneratorMethods = randomGeneratorClass?.getMethodsToReplace() ?: emptyList()
        private val randomClassMethods = Random::class.java.getMethodsToReplace()
        private val allRandomMethods = randomGeneratorMethods + randomClassMethods
        private val Int.isInstanceMethodOpcode
            get() = when (this) {
                Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL -> true
                else -> false
            }
    }
}

/**
 * [FakeDeterministicRandomTransformer] tracks invocations of various random number generation functions,
 * such as [Random.nextInt], and replaces them with corresponding [Injections] methods to prevent non-determinism.
 */
internal class FakeDeterministicRandomTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : AbstractDeterministicRandomTransformer(fileName, className, methodName, adapter) {
    override fun GeneratorAdapter.generateInstrumentedCodeForNextSecondarySeedAndGetProbe(
        opcode: Int,
        owner: String,
        name: String,
        desc: String,
        itf: Boolean
    ) {
        invokeStatic(Injections::nextInt)
    }

    override fun GeneratorAdapter.generateInstrumentedCodeForAdvanceProbe(
        opcode: Int,
        owner: String,
        name: String,
        desc: String,
        itf: Boolean
    ) {
        pop()
        invokeStatic(Injections::nextInt)
    }

    override fun GeneratorAdapter.generateInstrumentedCodeForRegularRandomMethod(
        opcode: Int,
        owner: String,
        name: String,
        desc: String,
        itf: Boolean,
        receiverLocal: Int,
        argumentsLocals: IntArray,
    ) {
        invokeInIgnoredSection {
            invokeStatic(Injections::deterministicRandom)
            loadLocals(argumentsLocals)
            /*
             * In Java 21 RandomGenerator interface was introduced,
             * so sometimes data structures interact with java.util.Random through this interface.
             */
            val randomOwner = if (owner.endsWith("RandomGenerator")) "java/util/random/RandomGenerator" else "java/util/Random"
            visitMethodInsn(opcode, randomOwner, name, desc, itf)
        }
    }
}
