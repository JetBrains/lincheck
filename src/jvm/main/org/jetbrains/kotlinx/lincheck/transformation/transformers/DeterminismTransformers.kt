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
import org.objectweb.asm.Type.getType
import org.objectweb.asm.commons.Method
import org.objectweb.asm.commons.GeneratorAdapter
import org.jetbrains.kotlinx.lincheck.transformation.*
import sun.nio.ch.lincheck.*
import java.util.*

/**
 * [DeterministicTimeTransformer] tracks invocations of [System.nanoTime] and [System.currentTimeMillis] methods,
 * and replaces them with stubs to prevent non-determinism.
 */
internal class DeterministicTimeTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {

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
 * [DeterministicRandomTransformer] tracks invocations of various random number generation functions,
 * such as [Random.nextInt], and replaces them with corresponding [Injections] methods to prevent non-determinism.
 */
internal class DeterministicRandomTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

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
                                 * In Java 21 RandomGenerator interface was introduced,
                                 * so sometimes data structures interact with java.util.Random through this interface.
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

    private fun isRandomMethod(methodName: String, desc: String): Boolean = RANDOM_METHODS.any {
        it.name == methodName && it.descriptor == desc
    }

    private companion object {
        private val RANDOM_METHODS = Random::class.java.declaredMethods.map { Method.getMethod(it) }
    }
}

