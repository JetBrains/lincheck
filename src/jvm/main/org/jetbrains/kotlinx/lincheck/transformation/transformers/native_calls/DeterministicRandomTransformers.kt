/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers.native_calls

import org.objectweb.asm.Type.getType
import org.objectweb.asm.commons.Method
import org.objectweb.asm.commons.GeneratorAdapter
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.Type.BYTE_TYPE
import sun.nio.ch.lincheck.*
import java.lang.reflect.Modifier
import java.util.*

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
        when (owner) {
            "java/util/concurrent/ThreadLocalRandom", "java/util/concurrent/atomic/Striped64",
            "java/util/concurrent/atomic/LongAdder", "java/util/concurrent/atomic/DoubleAdder",
            "java/util/concurrent/atomic/LongAccumulator", "java/util/concurrent/atomic/DoubleAccumulator" -> {
                when (name) {
                    "nextSecondarySeed", "getProbe" -> { // INVOKESTATIC
                        invokeIfInTestingCode(
                            original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                            code = { generateInstrumentedCodeForNextSecondarySeedAndGetProbe(opcode, owner, name, desc, itf) }
                        )
                        return
                    }
                    "advanceProbe" -> { // INVOKEVIRTUAL
                        invokeIfInTestingCode(
                            original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                            code = { generateInstrumentedCodeForAdvanceProbe(opcode, owner, name, desc, itf) }
                        )
                        return
                    }
                }
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
                            generateInstrumentedCodeForRegularRandomMethod(
                                opcode, owner, name, desc, itf, ownerLocal, arguments
                            )
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
    }
}

private val Int.isInstanceMethodOpcode
    get() = when (this) {
        Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL -> true
        else -> false
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
            val randomOwner =
                if (owner.endsWith("RandomGenerator")) "java/util/random/RandomGenerator" else "java/util/Random"
            visitMethodInsn(opcode, randomOwner, name, desc, itf)
        }
    }
}

internal class TrueDeterministicRandomTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : AbstractDeterministicRandomTransformer(fileName, className, methodName, adapter) {
    private fun GeneratorAdapter.wrapMethod(
        opcode: Int,
        owner: String,
        name: String,
        desc: String,
        itf: Boolean,
        receiverLocal: Int?,
        argumentsLocals: IntArray,
    ) {
        val deterministicCall = if (name == "nextBytes" && desc == "([B)V") {
            RandomBytesDeterministicRandomCall(opcode, owner, name, desc, itf)
        } else {
            ensureNoObjectOrArrayTypes(desc, opcode, owner, name)
            SimpleDeterministicCall(opcode, owner, name, desc, itf)
        }
        
        invoke(call = deterministicCall, receiver = receiverLocal, arguments = argumentsLocals)
    }

    private fun ensureNoObjectOrArrayTypes(
        desc: String,
        opcode: Int,
        owner: String,
        name: String
    ) {
        val argumentTypes = Type.getArgumentTypes(desc)
        argumentTypes.firstOrNull { it.sort == Type.OBJECT || it.sort == Type.ARRAY }?.let {
            error("$opcode $owner.$name$desc: argument of type ${it.className} is not supported")
        }
    }

    override fun GeneratorAdapter.generateInstrumentedCodeForNextSecondarySeedAndGetProbe(
        opcode: Int,
        owner: String,
        name: String,
        desc: String,
        itf: Boolean
    ) {
        val arguments = storeArguments(desc)
        val receiver = if (opcode.isInstanceMethodOpcode) newLocal(getType("L$owner;")).also(::storeLocal) else null
        wrapMethod(opcode, owner, name, desc, itf, receiver, arguments)
    }

    override fun GeneratorAdapter.generateInstrumentedCodeForAdvanceProbe(
        opcode: Int,
        owner: String,
        name: String,
        desc: String,
        itf: Boolean
    ) {
        val arguments = storeArguments(desc)
        val receiver = if (opcode.isInstanceMethodOpcode) newLocal(getType("L$owner;")).also(::storeLocal) else null
        wrapMethod(opcode, owner, name, desc, itf, receiver, arguments)
    }

    override fun GeneratorAdapter.generateInstrumentedCodeForRegularRandomMethod(
        opcode: Int,
        owner: String,
        name: String,
        desc: String,
        itf: Boolean,
        receiverLocal: Int,
        argumentsLocals: IntArray,
    ) = wrapMethod(opcode, owner, name, desc, itf, receiverLocal, argumentsLocals)
}

private data class RandomBytesDeterministicRandomCall(
    override val opcode: Int,
    override val owner: String,
    override val name: String,
    override val desc: String,
    override val isInterface: Boolean,
) : DeterministicCall {
    override val stateType: Type = getType(ByteArray::class.java)

    override fun invokeFromState(
        generator: GeneratorAdapter,
        getState: GeneratorBuilder,
        getReceiver: GeneratorBuilder?,
        getArgument: GeneratorAdapter.(Int) -> Unit,
    ) = generator.copyArrayContent(getSource = getState, getDestination = { getArgument(0) })

    override fun invokeSavingState(
        generator: GeneratorAdapter,
        saveState: GeneratorAdapter.(getState: GeneratorBuilder) -> Unit,
        getReceiver: GeneratorBuilder?,
        getArgument: GeneratorAdapter.(Int) -> Unit
    ) = generator.run {
        invokeOriginalCall(this, getReceiver, getArgument)

        val copiedArray = copyArray(BYTE_TYPE, getExistingArray = { getArgument(0) })
        saveState { loadLocal(copiedArray) }
    }
}