/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.lincheck.descriptors.MethodSignature
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.descriptors.toMethodSignature
import sun.nio.ch.lincheck.InjectedRandom
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.ResultInterceptor
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.Watchable
import java.nio.file.attribute.AttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import java.security.Principal
import java.util.concurrent.ConcurrentHashMap

internal data class MethodCallInfo(
    val ownerType: Types.ObjectType,
    val methodSignature: MethodSignature,
    val codeLocation: Int,
    val methodId: Int,
)

/**
 * Represents an abstract descriptor for a deterministic method call.
 * [runFake] calls a fake implementation of the method that produces a deterministic result.
 *
 * @param T The return type of the described method call.
 */
internal abstract class DeterministicMethodDescriptor<T> {
    abstract val methodCallInfo: MethodCallInfo
    abstract fun runFake(receiver: Any?, params: Array<Any?>): Result<T>
}

internal fun getDeterministicMethodDescriptorOrNull(
    receiver: Any?, params: Array<Any?>, methodCallInfo: MethodCallInfo
): DeterministicMethodDescriptor<*>? {
    getDeterministicTimeMethodDescriptorOrNull(methodCallInfo)?.let { return it }
    getDeterministicRandomMethodDescriptorOrNull(methodCallInfo)?.let { return it }
    getDeterministicFileMethodDescriptorOrNull(receiver, methodCallInfo)?.let { return it }
    return null
}

internal data class DeterministicMethodCallInterceptorData(
    val deterministicMethodDescriptor: DeterministicMethodDescriptor<*>,
)

internal fun ResultInterceptor.getDeterministicMethodDescriptor(): DeterministicMethodDescriptor<*>? {
    val interceptorData = (eventTrackerData as? DeterministicMethodCallInterceptorData)
    return interceptorData?.deterministicMethodDescriptor
}

/**
 * A deterministic method descriptor whose result can be computed solely from its inputs.
 */
private data class PureDeterministicMethodDescriptor<T>(
    override val methodCallInfo: MethodCallInfo,
    val fakeBehaviour: PureDeterministicMethodDescriptor<T>.(receiver: Any?, params: Array<Any?>) -> T
) : DeterministicMethodDescriptor<T>() {
    override fun runFake(receiver: Any?, params: Array<Any?>): Result<T> =
        runCatching { fakeBehaviour(receiver, params) }
}

private val systemType = Types.ObjectType("java.lang.System")

private fun getDeterministicTimeMethodDescriptorOrNull(
    methodCallInfo: MethodCallInfo
): DeterministicMethodDescriptor<*>? {
    if (methodCallInfo.ownerType != systemType) return null
    val methodName = methodCallInfo.methodSignature.name
    if (methodName != "nanoTime" && methodName != "currentTimeMillis") return null
    return PureDeterministicMethodDescriptor(methodCallInfo) { _, _ -> 1337L /* any constant value */ }
}

private fun getDeterministicRandomMethodDescriptorOrNull(
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
                Types.isPrimitive(it) || it == Types.ArrayType(Types.BYTE_TYPE)
            }) {
                "Only primitive arguments and ByteArrays are supported for default deterministic random: $methodCallInfo"
            }
            PureDeterministicMethodDescriptor(methodCallInfo) { _: Any?, params: Array<Any?> ->
                callWithGivenReceiver(Injections.deterministicRandom(), params)
            }
        }
    }
}

// Other random classes are expected to delegate to this ThreadLocalRandom.
// They may have arbitrary other methods that should not be handled here.
private fun Types.ObjectType.isRandom() = when (className) {
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

private fun Types.ObjectType.isSecureRandom() = className == "java.security.SecureRandom"

private val byteArrayMethodType = Types.MethodType(
    Types.VOID_TYPE,
    Types.ArrayType(Types.BYTE_TYPE),
)

private val secureByteArrayMethodType = Types.MethodType(
    Types.VOID_TYPE,
    Types.ArrayType(Types.BYTE_TYPE),
    Types.ObjectType("java.security.SecureRandomParameters"),
)

private val classMethodsImpl: MutableMap<Class<*>, Set<MethodSignature>> = ConcurrentHashMap()

private fun getPublicOrProtectedClassMethods(objectArgumentType: Types.ObjectType): Set<MethodSignature> =
    getPublicOrProtectedClassMethods(Class.forName(objectArgumentType.className))

private fun getPublicOrProtectedClassMethods(clazz: Class<*>): Set<MethodSignature> =
    classMethodsImpl.getOrPut(clazz) { clazz.getMethodsToReplace().toSet() }

private data class RandomBytesDeterministicMethodDescriptor(
    override val methodCallInfo: MethodCallInfo
) : DeterministicMethodDescriptor<Any>() {
    override fun runFake(receiver: Any?, params: Array<Any?>): Result<Any> = runCatching {
        callWithGivenReceiver(Injections.deterministicRandom(), params)
        Injections.VOID_RESULT
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

// region File I/O

private val javaIoFileSystemClass: Class<*> = Class.forName("java.io.FileSystem")
private val fileCleanableClass: Class<*>? = runCatching { Class.forName("java.io.FileCleanable") }.getOrNull()

/**
 * Returns a descriptor that throws "not supported" for file I/O operations in Lincheck,
 * or `null` if the method call is not a file I/O operation.
 */
private fun getDeterministicFileMethodDescriptorOrNull(
    receiver: Any?,
    methodCallInfo: MethodCallInfo,
): DeterministicMethodDescriptor<*>? {
    if (!isFileOperation(receiver, methodCallInfo)) return null
    return PureDeterministicMethodDescriptor<Any?>(methodCallInfo) { _, _ ->
        error("File operations are not supported in Lincheck")
    }
}

private fun isFileOperation(receiver: Any?, methodCallInfo: MethodCallInfo): Boolean {
    if (receiver == null) {
        return methodCallInfo.ownerType.className in fileRelatedStaticClasses
    }
    return receiver is FileInputStream
        || receiver is FileOutputStream
        || receiver is FileDescriptor
        || receiver is FileSystem
        || receiver is Path
        || receiver is FileSystemProvider
        || receiver is FileChannel
        || receiver is AsynchronousFileChannel
        || receiver is SeekableByteChannel
        || receiver is FileStore
        || receiver is AttributeView
        || receiver is BasicFileAttributes
        || receiver is UserPrincipalLookupService
        || receiver is Principal
        || receiver is WatchService
        || receiver is WatchKey
        || receiver is WatchEvent<*>
        || receiver is WatchEvent.Kind<*>
        || receiver is Watchable
        || javaIoFileSystemClass.isInstance(receiver)
        || fileCleanableClass?.isInstance(receiver) == true
}

private val fileRelatedStaticClasses = setOf(
    "java.nio.file.FileSystems",
    "java.nio.channels.FileChannel",
    "java.nio.channels.AsynchronousFileChannel",
    "java.nio.channels.SeekableByteChannel",
)
