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
import org.jetbrains.kotlinx.lincheck.strategy.native_calls.ReplayableMutableInstance
import sun.nio.ch.lincheck.Injections
import java.nio.file.DirectoryStream
import java.nio.file.Path
import java.util.Spliterator
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.ToDoubleFunction
import java.util.function.ToIntFunction
import java.util.function.ToLongFunction

// It is excessive to track any spliterator, so once we find that spliterator actions need to be recorded,
// we wrap it with one, whose actions we always track.
// Also, we expose found elements after each forEachRemaining.
internal class TrackedSpliterator<T>(val spliterator: Spliterator<T>): Spliterator<T>, ForEachHolder<T>() {
    var lastElement: T? = null

    override fun tryAdvance(action: Consumer<in T>?): Boolean {
        verifyIsNotReplaying()
        return spliterator.tryAdvance {
            lastElement = it
            action?.accept(it)
        }
    }

    override fun forEachRemaining(action: Consumer<in T>?) {
        verifyIsNotReplaying()
        clearRemainingElements()
        spliterator.forEachRemaining {
            addRemainingElement(it)
            action?.accept(it)
        }
    }

    override fun trySplit(): Spliterator<T?>? {
        verifyIsNotReplaying()
        return spliterator.trySplit().let(::TrackedSpliterator)
    }

    override fun estimateSize(): Long {
        verifyIsNotReplaying()
        return spliterator.estimateSize()
    }

    override fun characteristics(): Int {
        verifyIsNotReplaying()
        return spliterator.characteristics()
    }

    override fun getComparator(): Comparator<in T> {
        verifyIsNotReplaying()
        return TrackedComparator(spliterator.comparator)
    }

    override fun getExactSizeIfKnown(): Long {
        verifyIsNotReplaying()
        return spliterator.exactSizeIfKnown
    }

    override fun hasCharacteristics(characteristics: Int): Boolean {
        verifyIsNotReplaying()
        return spliterator.hasCharacteristics(characteristics)
    }
}

// It is excessive to track any comparator, so once we find that comparator actions need to be recorded,
// we wrap it with one, whose actions we always track.
internal class TrackedComparator<T>(private val comparator: Comparator<T>) : Comparator<T>, ReplayableMutableInstance() {
    private fun <T> Comparator<T>.tracked(): Comparator<T> =
        this as? TrackedComparator ?: TrackedComparator(this)

    override fun equals(other: Any?): Boolean {
        verifyIsNotReplaying()
        return comparator.equals(other)
    }

    override fun hashCode(): Int {
        verifyIsNotReplaying()
        return comparator.hashCode()
    }

    override fun compare(o1: T?, o2: T?): Int {
        verifyIsNotReplaying()
        return comparator.compare(o1, o2)
    }

    override fun reversed(): java.util.Comparator<T?> {
        verifyIsNotReplaying()
        return comparator.reversed().tracked()
    }

    override fun thenComparing(other: Comparator<in T?>): java.util.Comparator<T?> {
        verifyIsNotReplaying()
        return comparator.thenComparing(other).tracked()
    }

    override fun <U : Comparable<U>?> thenComparing(keyExtractor: Function<in T, out U>): java.util.Comparator<T> {
        verifyIsNotReplaying()
        return comparator.thenComparing(keyExtractor).tracked()
    }

    override fun thenComparingInt(keyExtractor: ToIntFunction<in T?>): java.util.Comparator<T?> {
        verifyIsNotReplaying()
        return comparator.thenComparingInt(keyExtractor).tracked()
    }

    override fun thenComparingLong(keyExtractor: ToLongFunction<in T?>): java.util.Comparator<T?> {
        verifyIsNotReplaying()
        return comparator.thenComparingLong(keyExtractor).tracked()
    }

    override fun thenComparingDouble(keyExtractor: ToDoubleFunction<in T?>): java.util.Comparator<T?> {
        verifyIsNotReplaying()
        return comparator.thenComparingDouble(keyExtractor).tracked()
    }

    override fun <U : Any?> thenComparing(
        keyExtractor: Function<in T, out U>, keyComparator: java.util.Comparator<in U>
    ): java.util.Comparator<T?> {
        verifyIsNotReplaying()
        return comparator.thenComparing(keyExtractor, keyComparator).tracked()
    }
}

// We expose found elements after each forEach.
internal class TrackedDirectoryStream<T>(private val directoryStream: DirectoryStream<T>) : DirectoryStream<T>, ForEachHolder<T>() {
    override fun iterator(): MutableIterator<T?> {
        verifyIsNotReplaying()
        return TrackedIterator(directoryStream.iterator())
    }

    override fun close() {
        verifyIsNotReplaying()
        directoryStream.close()
    }

    override fun spliterator(): Spliterator<T?> {
        verifyIsNotReplaying()
        return directoryStream.spliterator().let(::TrackedSpliterator)
    }

    override fun forEach(action: Consumer<in T>) {
        verifyIsNotReplaying()
        clearRemainingElements()
        directoryStream.forEach(Consumer {
            addRemainingElement(it)
            action.accept(it)
        })
    }
}

internal data class ByteArrayDeterministicMethodDescriptor<T>(
    override val methodCallInfo: MethodCallInfo
) : DeterministicMethodDescriptor<Result<ByteArrayDeterministicMethodDescriptor.State<T>>, T>() {
    data class State<T>(val changedByteArray: ByteArray, val returned: T)

    override fun runFake(receiver: Any?, params: Array<Any?>): Result<T> = throwUnsupportedFileError()

    override fun replay(receiver: Any?, params: Array<Any?>, state: Result<State<T>>): Result<T> =
        state.map { (savedBytes, returned) ->
            val byteArray = params[0] as ByteArray
            val offset = params.getOrNull(1) as Int? ?: 0
            savedBytes.copyInto(byteArray, offset)
            returned
        }

    override fun saveFirstResult(
        receiver: Any?, params: Array<Any?>, result: Result<T>, saveState: (Result<State<T>>) -> Unit
    ): Result<T> {
        val state = result.map { returned ->
            val byteArray = params[0] as ByteArray
            val offset = params.getOrNull(1) as Int? ?: 0
            val len = params.getOrNull(2) as Int? ?: byteArray.size
            State(byteArray.copyOfRange(fromIndex = offset, toIndex = offset + len), returned)
        }
        saveState(state)
        return result
    }
}

internal class DeterministicIteratorMethodDescriptor<T>(
    override val methodCallInfo: MethodCallInfo,
) : DeterministicMethodDescriptor<Result<TrackedIterator<T>>, Iterator<T>>() {
    override fun runFake(receiver: Any?, params: Array<Any?>): Result<Iterator<T>> = throwUnsupportedFileError()
    
    override fun replay(receiver: Any?, params: Array<Any?>, state: Result<TrackedIterator<T>>): Result<Iterator<T>> =
        state

    override fun saveFirstResult(
        receiver: Any?, params: Array<Any?>, result: Result<Iterator<T>>,
        saveState: (Result<TrackedIterator<T>>) -> Unit
    ): Result<TrackedIterator<T>> {
        val newResult = result.map { it.tracked() }
        saveState(newResult)
        return newResult
    }

    private fun <T> Iterator<T>.tracked() = this as? TrackedIterator<T> ?: TrackedIterator(this)
}

internal class DeterministicSpliteratorMethodDescriptor<T>(
    override val methodCallInfo: MethodCallInfo,
) : DeterministicMethodDescriptor<Result<TrackedSpliterator<T>>, Spliterator<T>>() {
    override fun runFake(receiver: Any?, params: Array<Any?>): Result<Spliterator<T>> = throwUnsupportedFileError()
    
    override fun replay(receiver: Any?, params: Array<Any?>, state: Result<TrackedSpliterator<T>>): Result<Spliterator<T>> =
        state

    override fun saveFirstResult(
        receiver: Any?, params: Array<Any?>, result: Result<Spliterator<T>>,
        saveState: (Result<TrackedSpliterator<T>>) -> Unit
    ): Result<TrackedSpliterator<T>> {
        val newResult = result.map { it.tracked() }
        saveState(newResult)
        return newResult
    }

    private fun <T> Spliterator<T>.tracked() = this as? TrackedSpliterator<T> ?: TrackedSpliterator(this)
}

internal class DeterministicIterableReturningMethodDescriptor<T>(
    override val methodCallInfo: MethodCallInfo,
) : DeterministicMethodDescriptor<Result<TrackedIterable<T>>, Iterable<T>>() {
    override fun runFake(receiver: Any?, params: Array<Any?>): Result<TrackedIterable<T>> = throwUnsupportedFileError()
    
    override fun replay(receiver: Any?, params: Array<Any?>, state: Result<TrackedIterable<T>>): Result<Iterable<T>> =
        state

    override fun saveFirstResult(
        receiver: Any?, params: Array<Any?>, result: Result<Iterable<T>>,
        saveState: (Result<TrackedIterable<T>>) -> Unit
    ): Result<TrackedIterable<T>> {
        val newResult = result.map { it.tracked() }
        saveState(newResult)
        return newResult
    }

    private fun <T> Iterable<T>.tracked() = this as? TrackedIterable<T> ?: TrackedIterable(this)
}

@Suppress("UNCHECKED_CAST")
internal class DeterministicTryAdvanceSpliteratorMethodDescriptor<T>(
    override val methodCallInfo: MethodCallInfo,
) : DeterministicMethodDescriptor<Result<Pair<Boolean, T?>>, Boolean>() {
    override fun runFake(receiver: Any?, params: Array<Any?>): Result<Boolean> = throwUnsupportedFileError()
    
    override fun replay(receiver: Any?, params: Array<Any?>, state: Result<Pair<Boolean, T?>>): Result<Boolean> =
        state.map { (hasNext, state) -> 
            if (hasNext) {
                val consumer = params[0] as Consumer<T?>
                consumer.accept(state)
                true
            } else {
                false
            }
        }
    
    override fun saveFirstResult(
        receiver: Any?, params: Array<Any?>, result: Result<Boolean>,
        saveState: (Result<Pair<Boolean, T?>>) -> Unit
    ): Result<Boolean> {
        receiver as TrackedSpliterator<T>
        val state = result.map { hasNext -> hasNext to receiver.lastElement.takeIf { hasNext } }
        saveState(state)
        return result
    }
}

@Suppress("UNCHECKED_CAST")
internal class DeterministicForEachMethodDescriptor<T>(
    override val methodCallInfo: MethodCallInfo,
) : DeterministicMethodDescriptor<List<T>, Any>() {
    
    override fun runFake(receiver: Any?, params: Array<Any?>): Result<Any> = throwUnsupportedFileError()
    
    override fun replay(receiver: Any?, params: Array<Any?>, state: List<T>): Result<Any> = runCatching {
        state.forEach { (params[0] as Consumer<T>).accept(it) }
        Injections.VOID_RESULT
    }
    
    override fun saveFirstResult(
        receiver: Any?, params: Array<Any?>, result: Result<Any>,
        saveState: (List<T>) -> Unit
    ): Result<Any> {
        saveState((receiver as ForEachHolder<T>).remainingElements.toList())
        return result
    }
}

internal class DeterministicDirectoryStreamMethodDescriptor(
    override val methodCallInfo: MethodCallInfo
): DeterministicMethodDescriptor<Result<TrackedDirectoryStream<Path>>, DirectoryStream<Path>>() {
    override fun runFake(receiver: Any?, params: Array<Any?>): Result<DirectoryStream<Path>> = throwUnsupportedFileError()
    
    override fun replay(receiver: Any?, params: Array<Any?>, state: Result<TrackedDirectoryStream<Path>>): Result<DirectoryStream<Path>> = state
    
    override fun saveFirstResult(
        receiver: Any?, params: Array<Any?>, result: Result<DirectoryStream<Path>>,
        saveState: (Result<TrackedDirectoryStream<Path>>) -> Unit
    ): Result<DirectoryStream<Path>> {
        val newResult = result.map { it.tracked() }
        saveState(newResult)
        return newResult
    }
    
    private fun <T> DirectoryStream<T>.tracked() = this as? TrackedDirectoryStream<T> ?: TrackedDirectoryStream(this)
}
