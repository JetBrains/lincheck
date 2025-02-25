/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util.native_calls

import org.objectweb.asm.commons.Method
import sun.nio.ch.lincheck.InjectedRandom
import sun.nio.ch.lincheck.Injections
import java.lang.reflect.Modifier
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

internal fun getDeterministicRandomMethodDescriptorOrNull(
    methodCallInfo: MethodCallInfo,
): DeterministicMethodDescriptor<*, *>? {
    if (methodCallInfo.isRandomClassProbesMethod()) {
        return PureDeterministicMethodDescriptor<Int>(methodCallInfo) { Injections.nextInt() }
    }

    val currentMethod = Method(methodCallInfo.methodName, methodCallInfo.methodDesc)
    return when {
        methodCallInfo.owner !is Random -> null
        currentMethod !in getPublicOrProtectedClassMethods(methodCallInfo.className) -> null
        methodCallInfo.methodName == "nextBytes" -> {
            require(methodCallInfo.methodDesc == "([B)V") { "nextBytes descriptor is not \"([B)V\"" }
            RandomBytesDeterministicMethodDescriptor(methodCallInfo)
        }
        else -> {
            require('[' !in methodCallInfo.methodDesc) { "DeterministicRandomMethodDescriptor does not support arrays" }
            PureDeterministicMethodDescriptor<Any?>(methodCallInfo) {
                callWithGivenReceiver(Injections.deterministicRandom())
            }
        }
    }
}

private val classMethodsImpl: MutableMap<Class<*>, Set<Method>> = ConcurrentHashMap()
private fun getPublicOrProtectedClassMethods(className: String): Set<Method> =
    getPublicOrProtectedClassMethods(Class.forName(className.replace('/', '.')))
private fun getPublicOrProtectedClassMethods(clazz: Class<*>): Set<Method> =
    classMethodsImpl.getOrPut(clazz) { clazz.getMethodsToReplace().toSet() }

private data class RandomBytesDeterministicMethodDescriptor(
    override val methodCallInfo: MethodCallInfo
): DeterministicMethodDescriptor<ByteArray, Unit>() {
    override fun runInLincheckMode() {
        callWithGivenReceiver(Injections.deterministicRandom())
    }
    
    override fun runFromState(state: ByteArray) {
        state.copyInto(methodCallInfo.params[0] as ByteArray)
    }

    override fun runSavingToState(saver: (ByteArray) -> Unit) {
        val arg = methodCallInfo.params[0] as ByteArray
        originalMethod.invoke(methodCallInfo.owner, arg)
        val state = arg.copyOf()
        saver(state)
    }
}

private fun Class<*>.getMethodsToReplace() = declaredMethods
    .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
    .map { Method.getMethod(it) }


private fun DeterministicMethodDescriptor<*, *>.callWithGivenReceiver(random: InjectedRandom): Any? {
    val method = InjectedRandom::class.java.methods.singleOrNull {
        Method.getMethod(it) == Method(methodCallInfo.methodName, methodCallInfo.methodDesc)
    } ?: error("No method found for $methodCallInfo")
    return method.invoke(random, *methodCallInfo.params.toTypedArray())
}

private fun MethodCallInfo.isRandomClassProbesMethod() =
    className in randomClassWithProbes && methodName in randomClassProbesMethods

private val randomClassWithProbes = setOf(
    "java/util/concurrent/ThreadLocalRandom", "java/util/concurrent/atomic/Striped64",
    "java/util/concurrent/atomic/LongAdder", "java/util/concurrent/atomic/DoubleAdder",
    "java/util/concurrent/atomic/LongAccumulator", "java/util/concurrent/atomic/DoubleAccumulator",
)

private val randomClassProbesMethods = setOf("nextSecondarySeed", "getProbe", "advanceProbe")
