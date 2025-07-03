/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.native_calls.io

import org.jetbrains.kotlinx.lincheck.strategy.native_calls.DeterministicMethodDescriptor
import org.jetbrains.kotlinx.lincheck.strategy.native_calls.MethodCallInfo
import org.jetbrains.kotlinx.lincheck.strategy.native_calls.PureDeterministicMethodDescriptor
import org.jetbrains.kotlinx.lincheck.strategy.native_calls.io.Java.Io.Stream
import org.jetbrains.kotlinx.lincheck.strategy.native_calls.io.Java.Io.closeableMethods
import org.jetbrains.lincheck.trace.Types.ObjectType
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
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

internal fun getDeterministicFileMethodDescriptorOrNull(
    receiver: Any?,
    params: Array<Any?>,
    methodCallInfo: MethodCallInfo
): DeterministicMethodDescriptor<*, *>? {
    getDeterministicJavaIoMethodDescriptorOrNull(receiver, params, methodCallInfo)?.let { return it }
    getDeterministicJavaNioFileMethodDescriptorOrNull(receiver, methodCallInfo)?.let { return it }
    getDeterministicJavaNioChannelsMethodDescriptorOrNull(receiver, methodCallInfo)?.let { return it }
    return null
}

private fun <T> notSupportedInLincheck(): PureDeterministicMethodDescriptor<T>.(Any?, Array<Any?>) -> T = { _, _ ->
    throwUnsupportedFileError()
}

internal fun throwUnsupportedFileError(): Nothing = error("File operations are not supported in Lincheck")

private fun ioPure(methodCallInfo: MethodCallInfo) =
    PureDeterministicMethodDescriptor<Any?>(methodCallInfo, notSupportedInLincheck())

private fun getDeterministicJavaIoMethodDescriptorOrNull(
    receiver: Any?,
    params: Array<Any?>,
    methodCallInfo: MethodCallInfo
): DeterministicMethodDescriptor<*, *>? {
    val methodSignature = methodCallInfo.methodSignature

    return when {
        methodSignature in Java.Io.FileSystem.methods && Java.Io.FileSystem.clazz.isInstance(receiver) ->
            ioPure(methodCallInfo)

        receiver is FileInputStream && methodSignature in Stream.Input.File.methods -> when (methodSignature) {
            Stream.Input.readToByteArrayMethod,
            Stream.Input.readToByteArrayWithOffsetMethod,
            Stream.Input.readNBytesToByteArrayWithOffsetMethod -> ByteArrayDeterministicMethodDescriptor(methodCallInfo)
            Stream.Input.transferToMethod -> if (params[0] is FileOutputStream) ioPure(methodCallInfo) else null
            Stream.Input.File.getFileChannelMethod -> Java.Nio.Channels.notSupportedYet()
            else -> ioPure(methodCallInfo)
        }

        receiver is FileOutputStream && methodSignature in Stream.Output.File.methods -> when (methodSignature) {
            Stream.Output.writeMethod, Stream.Output.writeWithOffsetMethod ->
                ByteArrayDeterministicMethodDescriptor(methodCallInfo)

            Stream.Output.File.getFileChannelMethod -> Java.Nio.Channels.notSupportedYet()
            else -> ioPure(methodCallInfo)
        }

        receiver is FileDescriptor -> ioPure(methodCallInfo)
        else -> null
    }
}

private fun getDeterministicJavaNioFileMethodDescriptorOrNull(
    receiver: Any?,
    methodCallInfo: MethodCallInfo
): DeterministicMethodDescriptor<*, *>? {
    val methodSignature = methodCallInfo.methodSignature
    return when {
        receiver is FileSystem && methodSignature in Java.Nio.File.FileSystem.methods -> when (methodSignature) {
            Java.Nio.File.FileSystem.getRootDirectoriesMethod, Java.Nio.File.FileSystem.getFileStoresMethod ->
                DeterministicIterableReturningMethodDescriptor<Any?>(methodCallInfo)
            else -> ioPure(methodCallInfo)
        }

        receiver is Path && methodSignature in Java.Nio.File.Path.methods -> when (methodSignature) {
            Java.Lang.Iterable.iteratorMethod -> DeterministicIteratorMethodDescriptor<Path>(methodCallInfo)
            Java.Lang.Iterable.spliteratorMethod -> DeterministicSpliteratorMethodDescriptor<Path>(methodCallInfo)
            Java.Lang.Iterable.forEachMethod -> null
            else -> ioPure(methodCallInfo)
        }

        methodSignature in Java.Nio.File.FileSystems.methods && methodCallInfo.ownerType == Java.Nio.File.FileSystems.type ->
            ioPure(methodCallInfo)

        receiver is FileSystemProvider && methodSignature in Java.Nio.File.FileSystem.Provider.methods -> when (methodSignature) {
            Java.Nio.File.FileSystem.Provider.directoryStreamMethod -> DeterministicDirectoryStreamMethodDescriptor(methodCallInfo)
            else -> ioPure(methodCallInfo)
        }

        receiver is TrackedDirectoryStream<*> && methodSignature in Java.Nio.File.DirectoryStream.methods -> when (methodSignature) {
            Java.Lang.Iterable.forEachMethod -> DeterministicForEachMethodDescriptor<Any?>(methodCallInfo)
            Java.Lang.Iterable.spliteratorMethod -> DeterministicSpliteratorMethodDescriptor<Any?>(methodCallInfo)
            else -> ioPure(methodCallInfo)
        }

        receiver is AttributeView && methodSignature in Java.Nio.File.AttributeView.methods ||
                receiver is BasicFileAttributes && methodSignature in Java.Nio.File.BasicFileAttributes.methods ||
                receiver is FileStore && methodSignature in Java.Nio.File.FileStore.methods ||
                receiver is UserPrincipalLookupService && methodSignature in Java.Nio.File.UserPrincipalLookupService.methods ||
                receiver is Principal && methodSignature in Java.Nio.File.Principal.methods ||
                receiver is WatchService && methodSignature in Java.Nio.File.WatchService.methods ||
                receiver is WatchKey && methodSignature in Java.Nio.File.WatchKey.methods ||
                receiver is WatchEvent<*> && methodSignature in Java.Nio.File.WatchEvent.methods ||
                receiver is Watchable && methodSignature in Java.Nio.File.Watchable.methods ||
                receiver is WatchEvent.Kind<*> && methodSignature in Java.Nio.File.WatchEventKind.methods
                    -> ioPure(methodCallInfo)

        receiver is TrackedIterator<*> -> when (methodSignature) {
            Java.Util.Iterator.forEachRemainingMethod -> DeterministicForEachMethodDescriptor<Any?>(methodCallInfo)
            else -> ioPure(methodCallInfo)
        }

        receiver is TrackedComparator<*> -> ioPure(methodCallInfo)
        receiver is TrackedIterable<*> -> when (methodSignature) {
            Java.Lang.Iterable.forEachMethod -> DeterministicForEachMethodDescriptor<Any?>(methodCallInfo)
            else -> ioPure(methodCallInfo)
        }
        
        receiver is TrackedSpliterator<*> -> when (methodSignature) {
            Java.Util.Spliterator.tryAdvanceMethod -> DeterministicTryAdvanceSpliteratorMethodDescriptor<Any?>(methodCallInfo)
            Java.Util.Spliterator.forEachRemainingMethod -> DeterministicForEachMethodDescriptor<Any?>(methodCallInfo)
            else -> ioPure(methodCallInfo)
        }
        
        methodSignature in Java.Io.FileCleanable.methods.orEmpty() && Java.Io.FileCleanable.clazz!!.isInstance(receiver) ->
            ioPure(methodCallInfo)

        else -> null
    }
}

private fun getDeterministicJavaNioChannelsMethodDescriptorOrNull(
    receiver: Any?,
    methodCallInfo: MethodCallInfo
): DeterministicMethodDescriptor<*, *>? {
    return when {
        receiver is FileChannel -> when (methodCallInfo.methodSignature) {
            in closeableMethods -> ioPure(methodCallInfo)
            else -> Java.Nio.Channels.notSupportedYet()
        }
        
        methodCallInfo.ownerType == ObjectType("java.nio.channels.FileChannel") -> when {
            methodCallInfo.methodSignature.name == "<init>" -> ioPure(methodCallInfo)
            else -> Java.Nio.Channels.notSupportedYet()
        }
        
        receiver is AsynchronousFileChannel -> when (methodCallInfo.methodSignature) {
            in closeableMethods -> ioPure(methodCallInfo)
            else -> Java.Nio.Channels.notSupportedYet()
        }
        
        methodCallInfo.ownerType == ObjectType("java.nio.channels.AsynchronousFileChannel") -> when {
            methodCallInfo.methodSignature.name == "<init>" -> ioPure(methodCallInfo)
            else -> Java.Nio.Channels.notSupportedYet()
        }
        
        receiver is SeekableByteChannel -> when (methodCallInfo.methodSignature) {
            in closeableMethods -> ioPure(methodCallInfo)
            else -> Java.Nio.Channels.notSupportedYet()
        }
        
        methodCallInfo.ownerType == ObjectType("java.nio.channels.SeekableByteChannel") -> when {
            methodCallInfo.methodSignature.name == "<init>" -> ioPure(methodCallInfo)
            else -> Java.Nio.Channels.notSupportedYet()
        }

        else -> null
    }
}
