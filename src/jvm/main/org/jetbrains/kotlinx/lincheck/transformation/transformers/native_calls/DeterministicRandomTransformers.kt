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

import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
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

/**
 * [DeterministicRandomTransformer] tracks invocations of various random number generation functions, such as [Random.nextInt].
 * 
 * In Lincheck mode it replaces them with corresponding [Injections] methods to prevent non-determinism.
 * 
 * In the trace debugger mode, it ensures deterministic behaviour by recording the results of the first invocations
 * and replaying them during the subsequent calls.
 */
internal class DeterministicRandomTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        when (owner) {
            "java/util/concurrent/ThreadLocalRandom", "java/util/concurrent/atomic/Striped64",
            "java/util/concurrent/atomic/LongAdder", "java/util/concurrent/atomic/DoubleAdder",
            "java/util/concurrent/atomic/LongAccumulator", "java/util/concurrent/atomic/DoubleAccumulator" -> {
                when (name) {
                    "nextSecondarySeed", "getProbe" -> { // INVOKESTATIC
                        invokeIfInTestingCode(
                            original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                            code = {
                                if (isInTraceDebuggerMode) {
                                    invoke(PureDeterministicCall(opcode, owner, name, desc, itf))
                                } else {
                                    invokeStatic(Injections::nextInt)
                                }
                            }
                        )
                        return
                    }
                    "advanceProbe" -> { // INVOKEVIRTUAL
                        invokeIfInTestingCode(
                            original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                            code = {
                                if (isInTraceDebuggerMode) {
                                    invoke(PureDeterministicCall(opcode, owner, name, desc, itf))
                                } else {
                                    pop()
                                    invokeStatic(Injections::nextInt)
                                }
                            }
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
                            if (isInTraceDebuggerMode) {
                                val deterministicCall = if (name == "nextBytes" && desc == "([B)V") {
                                    RandomBytesDeterministicRandomCall(opcode, owner, name, desc, itf)
                                } else {
                                    ensureNoObjectsOrArraysInArgumentTypes(desc, opcode, owner, name)
                                    PureDeterministicCall(opcode, owner, name, desc, itf)
                                }

                                invoke(call = deterministicCall, receiver = ownerLocal, arguments = arguments)
                            } else {
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

    private fun isRandomMethod(opcode: Int, methodName: String, desc: String): Boolean = allRandomMethods.any {
        opcode.isInstanceMethodOpcode && it.name == methodName && it.descriptor == desc
    }

    private fun ensureNoObjectsOrArraysInArgumentTypes(
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
 * Represents a deterministic method call specifically tailored to work with byte arrays in method [Random.nextBytes],
 * ensuring that certain method invocations are performed deterministically.
 *
 * The serializable state of the invocation is a copy of saved random array content.
 * On the first run the content is copied to a new array that is further saved.
 * On subsequent runs, its content is copied back to the array in arguments.
 *
 * @property opcode The operation code for the method call.
 * @property owner The fully qualified class name of the method's owner.
 * @property name The name of the method being invoked.
 * @property desc The method descriptor specifying its signature.
 * @property isInterface Whether the method belongs to an interface.
 * @property stateType The expected type of the state involved in the deterministic call, which is a ByteArray.
 */
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

        val copiedArray = arrayCopy(elementType = BYTE_TYPE, getExistingArray = { getArgument(0) })
        saveState { loadLocal(copiedArray) }
    }
}
