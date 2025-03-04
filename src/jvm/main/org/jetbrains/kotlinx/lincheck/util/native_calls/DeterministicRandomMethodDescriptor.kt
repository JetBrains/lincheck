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

import sun.nio.ch.lincheck.InjectedRandom
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.JavaResult
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal fun getDeterministicRandomMethodDescriptorOrNull(
    methodCallInfo: MethodCallInfo,
): DeterministicMethodDescriptor<*>? {
    if (methodCallInfo.isRandomClassProbesMethod()) {
        return PureDeterministicMethodDescriptor(methodCallInfo) { _, _ -> Injections.nextInt() }
    }
    if (methodCallInfo.ownerType.isSecureRandom() && methodCallInfo.methodSignature.isSecureRandomMethodToSkip()) {
        return null
    }

    val currentMethodType = methodCallInfo.methodSignature.methodType
    return when {
        !methodCallInfo.ownerType.isRandom() -> null
        methodCallInfo.methodSignature !in getPublicOrProtectedClassMethods(methodCallInfo.ownerType) -> null
        methodCallInfo.methodSignature.name == "nextBytes" -> {
            require(currentMethodType == byteArrayMethodType || currentMethodType == secureByteArrayMethodType) {
                "nextBytes descriptor is not $byteArrayMethodType and $secureByteArrayMethodType: $methodCallInfo"
            }
            RandomBytesDeterministicMethodDescriptor(methodCallInfo)
        }

        else -> {
            require(currentMethodType.argumentTypes.all {
                it is ArgumentType.Primitive || it == ArgumentType.Array(ArgumentType.Primitive.Byte)
            }) {
                "Only primitive arguments and ByteArrays are supported for default deterministic random: $methodCallInfo"
            }
            PureDeterministicMethodDescriptor(methodCallInfo) { _: Any?, params: Array<Any?>, ->
                callWithGivenReceiver(Injections.deterministicRandom(), params)
            }
        }
    }
}

// Other random classes are expected to delegate to this ThreadLocalRandom.
// They may have arbitrary other methods that should not be handled here.
private fun ArgumentType.Object.isRandom() = when (className) {
    "java.util.Random", "java.util.random.RandomGenerator", "java.util.concurrent.ThreadLocalRandom",
    "java.security.SecureRandom" -> true

    else -> false
}

/**
 * These methods aren't sources of non-determinism, and some of them return non-serializable values
 */
private fun MethodSignature.isSecureRandomMethodToSkip() = when (name) {
    "getAlgorithm", "getInstance", "getInstanceStrong", "getProvider", "getParameters", "reseed", "toString" -> true
    else -> false
}

private fun ArgumentType.Object.isSecureRandom() = className == "java.security.SecureRandom"

private val byteArrayMethodType = MethodType(
    argumentTypes = listOf(ArgumentType.Array(ArgumentType.Primitive.Byte)),
    returnType = Type.Void,
)

private val secureByteArrayMethodType = MethodType(
    argumentTypes = listOf(
        ArgumentType.Array(ArgumentType.Primitive.Byte),
        ArgumentType.Object("java.security.SecureRandomParameters")
    ),
    returnType = Type.Void,
)

private val classMethodsImpl: MutableMap<Class<*>, Set<MethodSignature>> = ConcurrentHashMap()

private fun getPublicOrProtectedClassMethods(objectArgumentType: ArgumentType.Object): Set<MethodSignature> =
    getPublicOrProtectedClassMethods(Class.forName(objectArgumentType.className))

private fun getPublicOrProtectedClassMethods(clazz: Class<*>): Set<MethodSignature> =
    classMethodsImpl.getOrPut(clazz) { clazz.getMethodsToReplace().toSet() }

private data class RandomBytesDeterministicMethodDescriptor(
    override val methodCallInfo: MethodCallInfo
) : DeterministicMethodDescriptor<JavaResult>() {
    override fun runFake(receiver: Any?, params: Array<Any?>): JavaResult = JavaResult.fromCallable {
        callWithGivenReceiver(Injections.deterministicRandom(), params)
    }

    override fun replay(receiver: Any?, params: Array<Any?>, state: JavaResult): JavaResult = state.map { safeState ->
        val argument = params[0] as ByteArray
        val savedBytes = safeState as ByteArray
        savedBytes.copyInto(argument)
    }

    override fun saveFirstResult(
        receiver: Any?,
        params: Array<Any?>,
        result: JavaResult,
        saveState: (JavaResult) -> Unit
    ) {
        val state = result.map {
            val argument = params[0] as ByteArray
            argument.copyOf()
        }
        saveState(state)
    }
}

private fun Class<*>.getMethodsToReplace(): List<MethodSignature> = (declaredMethods + methods)
    .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
    .map { it.toMethodSignature() }


private fun DeterministicMethodDescriptor<*>.callWithGivenReceiver(random: InjectedRandom, params: Array<Any?>): Any? {
    val method = InjectedRandom::class.java.methods
        .singleOrNull { it.toMethodSignature() == methodCallInfo.methodSignature }
        ?: error("No method found for $methodCallInfo")
    return method.invoke(random, *params)
}

private fun MethodCallInfo.isRandomClassProbesMethod() =
    ownerType.className in randomClassWithProbes && methodSignature.name in randomClassProbesMethods

private val randomClassWithProbes = setOf(
    "java.util.concurrent.ThreadLocalRandom", "java.util.concurrent.atomic.Striped64",
    "java.util.concurrent.atomic.LongAdder", "java.util.concurrent.atomic.DoubleAdder",
    "java.util.concurrent.atomic.LongAccumulator", "java.util.concurrent.atomic.DoubleAccumulator",
)

private val randomClassProbesMethods = setOf("nextSecondarySeed", "getProbe", "advanceProbe")
