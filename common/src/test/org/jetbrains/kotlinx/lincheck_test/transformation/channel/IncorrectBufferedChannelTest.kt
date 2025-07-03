/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.transformation.channel

import kotlinx.atomicfu.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Checks that [bug with not transformed ClassLoader-s](https://github.com/JetBrains/lincheck/issues/412) is resolved.
 * [IncorrectBufferedChannel] is intentionally provided by Daria Shutina.
 */
class IncorrectBufferedChannelTest {

    private val c = IncorrectBufferedChannel<Int>(1)

    @Operation(blocking = true)
    suspend fun send(elem: Int): Any = try {
        c.send(elem)
    } catch (e: NumberedCancellationException) {
        e.testResult
    }

    @Operation(blocking = true, cancellableOnSuspension = false)
    suspend fun receive(): Any = try {
        c.receive()
    } catch (e: NumberedCancellationException) {
        e.testResult
    }

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .iterations(1)
        .sequentialSpecification(SequentialBufferedChannel::class.java)
        .addCustomScenario {
            parallel {
                thread {
                    actor(::send, 3)
                    actor(::send, 2)
                    actor(::send, 1)
                }
                thread {
                    actor(::receive)
                    actor(::send, 3)
                    actor(::receive)
                }
                thread {
                    actor(::receive)
                    actor(::send, 3)
                    actor(::send, 3)
                }
            }
        }
        .check(this::class)
}

private class NumberedCancellationException(number: Int) : CancellationException() {
    val testResult = "Closed($number)"
}

// Sequential specification for a buffered channel
@Suppress("unused")
class SequentialBufferedChannel {
    private val capacity: Long = 1
    private val senders = ArrayList<Pair<CancellableContinuation<Unit>, Int>>()
    private val buffer = ArrayList<Int>()
    private val receivers = ArrayList<CancellableContinuation<Int>>()

    suspend fun send(x: Int) {
        if (resumeFirstReceiver(x)) return
        if (tryBufferElem(x)) return
        suspendCancellableCoroutine { cont -> senders.add(cont to x) }
    }

    private fun tryBufferElem(elem: Int): Boolean {
        if (buffer.size < capacity) {
            buffer.add(elem)
            return true
        }
        return false
    }

    private fun resumeFirstReceiver(elem: Int): Boolean {
        while (receivers.isNotEmpty()) {
            val r = receivers.removeFirst()
            if (r.resume(elem)) return true
        }
        return false
    }

    suspend fun receive(): Int {
        return getBufferedElem()
            ?: resumeFirstSender()
            ?: suspendCancellableCoroutine { cont -> receivers.add(cont) }
    }

    private fun getBufferedElem(): Int? {
        val elem = buffer.removeFirstOrNull()?.also {
            // The element is retrieved from the buffer, resume one sender and save its element in the buffer
            resumeFirstSender()?.also { buffer.add(it) }
        }
        return elem
    }

    private fun resumeFirstSender(): Int? {
        while (senders.isNotEmpty()) {
            val (sender, elem) = senders.removeFirst()
            if (sender.resume(Unit)) return elem
        }
        return null
    }
}

@OptIn(InternalCoroutinesApi::class)
private fun <T> CancellableContinuation<T>.resume(res: T): Boolean {
    val token = tryResume(res) ?: return false
    completeResume(token)
    return true
}

/**
 * Incorrect implementation of the BufferedChannel, intentionally provided by Daria Shutina.
 */
private class IncorrectBufferedChannel<E>(capacity: Long) : Channel<E> {
    /**
    The counters show the total amount of senders and receivers ever performed. They are
    incremented in the beginning of the corresponding operation, thus acquiring a unique
    (for the operation type) cell to process.
     */
    private val sendersCounter = atomic(0L)
    private val receiversCounter = atomic(0L)

    /**
    This counter indicates the end of the channel buffer. Its value increases every time
    when a `receive` request is completed.
     */
    private val bufferEnd = atomic(capacity)

    /**
    This is the counter of completed [expandBuffer] invocations. It is used for maintaining
    the guarantee that [expandBuffer] is invoked on every cell.
    Initially, its value is equal to the buffer capacity.
     */
    private val completedExpandBuffers = atomic(capacity)

    /**
    These channel pointers indicate segments where values of [sendersCounter], [receiversCounter]
    and [bufferEnd] are currently located.
     */
    private val sendSegment: AtomicRef<ChannelSegment<E>>
    private val receiveSegment: AtomicRef<ChannelSegment<E>>
    private val bufferEndSegment: AtomicRef<ChannelSegment<E>>

    init {
        val firstSegment = ChannelSegment(id = 0, prevSegment = null, channel = this)
        sendSegment = atomic(firstSegment)
        receiveSegment = atomic(firstSegment)
        bufferEndSegment = atomic(firstSegment.findSegment(bufferEnd.value / SEGMENT_SIZE))
    }

    override suspend fun send(elem: E) {
        // Read the segment reference before the counter increment; the order is crucial,
        // otherwise there is a chance the required segment will not be found
        var segment = sendSegment.value
        while (true) {
            // Obtain the global index for this sender right before
            // incrementing the `senders` counter.
            val s = sendersCounter.getAndIncrement()
            // Count the required segment id and the cell index in it.
            val id = s / SEGMENT_SIZE
            val index = (s % SEGMENT_SIZE).toInt()
            // Try to find the required segment if the initially obtained
            // one (in the beginning of this function) has lower id.
            if (segment.id != id) {
                // Find the required segment.
                segment = findSegmentSend(id, segment) ?:
                        // The required segment has not been found, since it was full of cancelled
                        // cells and, therefore, physically removed. Restart the sender.
                        continue
            }
            // Place the element in the cell.
            segment.setElement(index, elem)
            // Update the cell according to the algorithm. If the cell was poisoned or
            // stores an interrupted receiver, clean the cell and restart the sender.
            if (updateCellOnSend(s, segment, index)) return
            segment.cleanElement(index)
        }
    }

    private suspend fun updateCellOnSend(
        /* The global index of the cell. */
        s: Long,
        /* The working cell is specified by the segment and the index in it. */
        segment: ChannelSegment<E>,
        index: Int
    ): Boolean {
        while (true) {
            val state = segment.getState(index)
            val b = bufferEnd.value
            val r = receiversCounter.value
            when {
                // Empty and either the cell is in the buffer or a receiver is coming => buffer
                state == null && (s < r || s < b) || state == CellState.IN_BUFFER -> {
                    if (segment.casState(index, state, CellState.BUFFERED)) return true
                }
                // Empty, the cell is not in the buffer and no receiver is coming => suspend
                state == null && s >= b && s >= r -> {
                    if (trySuspendRequest(segment, index, isSender = true)) return true
                }
                // The cell was poisoned by a receiver => restart the sender
                state == CellState.POISONED -> return false
                // Cancelled receiver => restart the sender
                state == CellState.INTERRUPTED_RCV -> return false
                // Suspended receiver in the cell => try to resume it
                else -> {
                    val receiver = (state as? Coroutine)?.cont ?: (state as CoroutineEB).cont
                    if (tryResumeRequest(receiver)) {
                        segment.setState(index, CellState.DONE_RCV)
                        return true
                    } else {
                        // The resumption has failed, since the receiver was cancelled.
                        // Clean the cell and wait until `expandBuffer()`-s invoked on the cells
                        // before the current one finish.
                        segment.onCancellation(index = index, isSender = false)
                        return false
                    }
                }
            }
        }
    }

    override suspend fun receive(): E {
        // Read the segment reference before the counter increment; the order is crucial,
        // otherwise there is a chance the required segment will not be found
        var segment = receiveSegment.value
        while (true) {
            // Obtain the global index for this receiver right before
            // incrementing the `receivers` counter.
            val r = receiversCounter.getAndIncrement()
            // Count the required segment id and the cell index in it.
            val id = r / SEGMENT_SIZE
            val index = (r % SEGMENT_SIZE).toInt()
            // Try to find the required segment if the initially obtained
            // one (in the beginning of this function) has lower id.
            if (segment.id != id) {
                // Find the required segment.
                segment = findSegmentReceive(id, segment) ?:
                        // The required segment has not been found, since it was full of cancelled
                        // cells and, therefore, physically removed. Restart the receiver.
                        continue
            }
            // Update the cell according to the algorithm. If the rendezvous happened,
            // the received value is returned, then the cell is cleaned to avoid memory
            // leaks. Otherwise, the receiver restarts.
            if (updateCellOnReceive(r, segment, index)) {
                return segment.retrieveElement(index)
            }
        }
    }

    private suspend fun updateCellOnReceive(
        /* The global index of the cell. */
        r: Long,
        /* The working cell is specified by the segment and the index in it. */
        segment: ChannelSegment<E>,
        index: Int
    ): Boolean {
        while (true) {
            val state = segment.getState(index)
            val s = sendersCounter.value
            when {
                // The cell is empty and no sender is coming => suspend
                (state == null || state == CellState.IN_BUFFER) && r >= s -> {
                    if (trySuspendRequest(segment, index, isSender = false)) return true
                }
                // The cell is empty but a sender is coming => poison & restart
                (state == null || state == CellState.IN_BUFFER) && r < s -> {
                    if (segment.casState(index, state, CellState.POISONED)) {
                        expandBuffer()
                        return false
                    }
                }
                // Buffered element => finish
                state == CellState.BUFFERED -> {
                    segment.setState(index, CellState.DONE_RCV).also { expandBuffer() }
                    return true
                }
                // Cancelled sender => restart
                state == CellState.INTERRUPTED_SEND -> return false
                // `expandBuffer()` is resuming the sender => wait
                state == CellState.RESUMING_BY_EB -> continue
                // Suspended sender in the cell => try to resume it
                else -> {
                    // To synchronize with expandBuffer(), the algorithm first moves the cell to an
                    // intermediate `RESUMING_BY_RCV` state, updating it to either `DONE_RCV` (on success)
                    // or `INTERRUPTED_SEND` (on failure).
                    if (segment.casState(index, state, CellState.RESUMING_BY_RCV)) {
                        // Has a concurrent `expandBuffer()` delegated its completion?
                        val helpExpandBuffer = state is CoroutineEB
                        // Extract the sender's coroutine and try to resume it
                        val sender = (state as? Coroutine)?.cont ?: (state as CoroutineEB).cont
                        if (tryResumeRequest(sender)) {
                            // The sender was resumed successfully. Update the cell state, expand the buffer and finish.
                            // In case a concurrent `expandBuffer()` has delegated its completion, the procedure should
                            // finish, as the sender is resumed. Thus, no further action is required.
                            segment.setState(index, CellState.DONE_RCV).also { expandBuffer() }
                            return true
                        } else {
                            // The resumption has failed. Update the cell state and restart the receiver.
                            // In case a concurrent `expandBuffer()` has delegated its completion, the procedure should
                            // skip this cell, so `expandBuffer()` should be called once again.
                            segment.onCancellation(index = index, isSender = true)
                            if (helpExpandBuffer) expandBuffer()
                            return false
                        }
                    }
                }
            }
        }
    }

    /**
    This method suspends a request. If the suspended coroutine is successfully placed in the
    cell, the method returns true. Otherwise, the coroutine is resumed and the method returns
    false, thus restarting the request.

    If the request subject to suspension is a receiver, [expandBuffer] is invoked before the
    coroutine falls asleep.
     */
    private suspend fun trySuspendRequest(segment: ChannelSegment<E>, index: Int, isSender: Boolean): Boolean =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                segment.onCancellation(index, isSender)
            }
            if (!segment.casState(index, null, Coroutine(cont))) {
                // The cell is occupied by the opposite request. Resume the coroutine.
                cont.tryResumeRequest(false)
            } else {
                // The coroutine was successfully placed in the cell. If the suspended
                // request is a receiver, invoke `expandBuffer()`.
                if (!isSender) expandBuffer()
            }
        }

    /**
    This method resumes a suspended request. It returns true if the request was successfully
    resumed and false otherwise.
     */
    private fun tryResumeRequest(cont: CancellableContinuation<Boolean>) = cont.tryResumeRequest(true)

    /**
    Responsible for resuming a coroutine. The given [value] is the one that should be returned
    in the suspension point. If the coroutine is successfully resumed, the method returns true,
    otherwise it returns false.
     */
    @OptIn(InternalCoroutinesApi::class)
    private fun CancellableContinuation<Boolean>.tryResumeRequest(value: Boolean): Boolean {
        val token = tryResume(value)
        return if (token != null) {
            completeResume(token)
            true
        } else {
            false
        }
    }

    /**
    This method finds the segment which contains non-interrupted cells and which id >= the
    requested [id]. In case the required segment has not been created yet, this method attempts
    to add it to the underlying linked list. Finally, it updates [sendSegment] to the found
    segment if its [ChannelSegment.id] is greater than the one of the already stored segment.

    In case the requested segment is already removed, the method returns `null`.
     */
    private fun findSegmentSend(id: Long, startFrom: ChannelSegment<E>): ChannelSegment<E>? {
        val segment = sendSegment.findSegmentAndMoveForward(id, startFrom)
        return if (segment.id > id) {
            // The required segment has been removed; `segment` is the first
            // segment with `id` not lower than the required one.
            // Skip the sequence of interrupted cells by updating [sendersCounter].
            sendersCounter.updateCounterIfLower(segment.id * SEGMENT_SIZE)
            // As the required segment is already removed, return `null`.
            null
        } else {
            // The required segment has been found, return it.
            segment
        }
    }

    /**
    This method finds the segment which contains non-interrupted cells and which id >= the
    requested [id]. In case the required segment has not been created yet, this method attempts
    to add it to the underlying linked list. Finally, it updates [receiveSegment] to the found
    segment if its [ChannelSegment.id] is greater than the one of the already stored segment.

    In case the requested segment is already removed, the method returns `null`.
     */
    private fun findSegmentReceive(id: Long, startFrom: ChannelSegment<E>): ChannelSegment<E>? {
        val segment = receiveSegment.findSegmentAndMoveForward(id, startFrom)
        return if (segment.id > id) {
            // The required segment has been removed; `segment` is the first
            // segment with `id` not lower than the required one.
            // Skip the sequence of interrupted cells by updating [receiversCounter].
            receiversCounter.updateCounterIfLower(segment.id * SEGMENT_SIZE)
            // As the required segment is already removed, return `null`.
            null
        } else {
            // The required segment has been found, return it.
            segment
        }
    }

    /**
    Updates the counter ([sendersCounter] or [receiversCounter]) if its value is lower than
    the specified one. The method is used to efficiently skip a sequence of cancelled cells
    in [findSegmentSend] and [findSegmentReceive].
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun AtomicLong.updateCounterIfLower(value: Long): Unit =
        loop { curCounter ->
            if (curCounter >= value) return
            if (compareAndSet(curCounter, value)) return
        }

    /**
    This method returns the first segment which contains non-interrupted cells and which
    id >= the required [id]. In case the required segment has not been created yet, the
    method creates new segments and adds them to the underlying linked list.
    After the desired segment is found and the `AtomicRef` pointer is successfully moved
    to it, the segment is returned by the method.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun <E> AtomicRef<ChannelSegment<E>>.findSegmentAndMoveForward(
        id: Long,
        startFrom: ChannelSegment<E>
    ): ChannelSegment<E> {
        while (true) {
            val dest = startFrom.findSegment(id)
            // Try to update `value` and restart if the found segment is logically removed
            if (moveForward(dest)) return dest
        }
    }

    /**
    This method helps moving the `AtomicRef` pointer forward.
    If the pointer is being moved to the segment which is logically removed, the method
    returns false, thus forcing [findSegmentAndMoveForward] method to restart.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun <E> AtomicRef<ChannelSegment<E>>.moveForward(to: ChannelSegment<E>): Boolean = loop { cur ->
        if (cur.id >= to.id) {
            // No need to update the pointer, it was already updated by another request.
            return true
        }
        if (to.isRemoved) {
            // Trying to move pointer to the segment which is logically removed.
            // Restart [AtomicRef<S>.findAndMoveForward].
            return false
        }
        if (compareAndSet(cur, to)) {
            // The segment was successfully moved.
            cur.tryRemoveSegment()
            return true
        }
    }

    /**
    This method is responsible for updating the [bufferEnd] counter. It is called after
    `receive()` successfully performs its synchronization, either retrieving the first
    element, or storing its coroutine for suspension.
     */
    private fun expandBuffer() {
        // Read the current segment of the `expandBuffer()` procedure.
        var segment = bufferEndSegment.value
        // Try to expand the buffer until succeed.
        while (true) {
            // Increment the logical end of the buffer.
            // The `b`-th cell is going to be added to the buffer.
            val b = bufferEnd.getAndIncrement()
            val id = b / SEGMENT_SIZE
            // After that, read the current `senders` counter. In case its value is lower than `b`,
            // the `send(e)` invocation that will work with this `b`-th cell will detect that the
            // cell is already a part of the buffer when comparing with the `bufferEnd` counter.
            if (b >= sendersCounter.value) {
                // The cell is not covered by send() request. Increment the number of
                // completed `expandBuffer()`-s and finish.
                incCompletedExpandBufferAttempts()
                return
            }
            // Is `bufferEndSegment` outdated or is the segment with the required id already removed?
            // Find the required segment, creating new ones if needed.
            if (segment.id != id) {
                segment = findSegmentBufferEnd(id, segment, b) ?:
                        // The required segment has been removed, restart `expandBuffer()`.
                        continue
            }
            // Try to add the cell to the logical buffer, updating the cell state
            // according to the algorithm.
            val index = (b % SEGMENT_SIZE).toInt()
            if (updateCellOnExpandBuffer(segment, index, b)) {
                // The cell has been added to the logical buffer.
                // Increment the number of completed `expandBuffer()`-s and finish.
                incCompletedExpandBufferAttempts()
                return
            } else {
                // The cell has not been added to the buffer. Increment the number of
                // completed `expandBuffer()` attempts and restart.
                incCompletedExpandBufferAttempts()
                continue
            }
        }
    }

    private fun findSegmentBufferEnd(id: Long, startFrom: ChannelSegment<E>, currentBufferEndCounter: Long)
            : ChannelSegment<E>? {
        val segment = bufferEndSegment.findSegmentAndMoveForward(id, startFrom)
        return if (segment.id > id) {
            // The required segment has been removed; `segment` is the first
            // segment with `id` not lower than the required one.
            // Skip the sequence of interrupted cells by updating [bufferEnd] counter.
            if (bufferEnd.compareAndSet(currentBufferEndCounter + 1, segment.id * SEGMENT_SIZE)) {
                incCompletedExpandBufferAttempts(segment.id * SEGMENT_SIZE - currentBufferEndCounter)
            } else {
                incCompletedExpandBufferAttempts()
            }
            // As the required segment is already removed, return `null`.
            null
        } else {
            // The required segment has been found, return it.
            segment
        }
    }

    /**
    This method returns true if [expandBuffer] should finish and false if [expandBuffer] should restart.
     */
    private fun updateCellOnExpandBuffer(segment: ChannelSegment<E>, index: Int, b: Long): Boolean {
        while (true) {
            when (val state = segment.getState(index)) {
                // The cell is empty => mark the cell as "in the buffer" and finish
                null -> if (segment.casState(index, null, CellState.IN_BUFFER)) return true
                // A suspended coroutine, sender or receiver
                is Coroutine -> {
                    if (b >= receiversCounter.value) {
                        // Suspended sender, since the cell is not covered by a receiver. Try to resume it.
                        if (segment.casState(index, state, CellState.RESUMING_BY_EB)) {
                            return if (tryResumeRequest(state.cont)) {
                                segment.setState(index, CellState.BUFFERED)
                                true
                            } else {
                                segment.onCancellation(index = index, isSender = true)
                                false
                            }
                        }
                    }
                    // Cannot distinguish the coroutine, add `EB` marker to it
                    if (segment.casState(index, state, CoroutineEB(state.cont))) return true
                }
                // The element is already buffered => finish
                CellState.BUFFERED -> return true
                // The rendezvous happened => finish, expandBuffer() was invoked before the receiver was suspended
                CellState.DONE_RCV -> return true
                // The sender was interrupted => restart
                CellState.INTERRUPTED_SEND -> return false
                // The receiver was interrupted => finish
                CellState.INTERRUPTED_RCV -> return true
                // Poisoned cell => finish, receive() is in charge
                CellState.POISONED -> return true
                // A receiver is resuming the sender => wait in a spin loop until it changes
                // the state to either `DONE_RCV` or `INTERRUPTED_SEND`
                CellState.RESUMING_BY_RCV -> continue
                else -> error("Unexpected cell state: $state")
            }
        }
    }

    /**
    Increases the amount of completed [expandBuffer] invocations.
     */
    private fun incCompletedExpandBufferAttempts(nAttempts: Long = 1) {
        completedExpandBuffers.addAndGet(nAttempts)
    }

    /**
    Waits in a spin-loop until the [expandBuffer] call that should process the [globalIndex]-th
    cell is completed. Essentially, it waits until the numbers of started ([bufferEnd]) and
    completed ([completedExpandBuffers]) [expandBuffer] attempts coincide and become equal or
    greater than [globalIndex].
     */
    internal fun waitExpandBufferCompletion(globalIndex: Long) {
        // Wait in an infinite loop until the number of started buffer expansion calls
        // become not lower than the cell index.
        @Suppress("ControlFlowWithEmptyBody")
        while (bufferEnd.value <= globalIndex) {}
        // Now it is guaranteed that the `expandBuffer()` call that should process the
        // required cell has been started. Wait in an infinite loop until the numbers of
        // started and completed buffer expansion calls coincide.
        while (true) {
            val b = bufferEnd.value
            val completedEB = completedExpandBuffers.value
            if (b == completedEB && b == bufferEnd.value) return
        }
    }
}

/**
A waiter that stores a suspended coroutine. The process of resumption does not depend on
which suspended request (a sender or a receiver) is stored in the cell.
 */
internal data class Coroutine(val cont: CancellableContinuation<Boolean>)

/**
A waiter that stores a suspended coroutine with the `EB` marker. The marker is added when [expandBuffer]
cannot distinguish whether the coroutine stored in the cell is a suspended sender or receiver. Thus, the
[expandBuffer] completion is delegated to a request of the opposite type which will come to the cell
in the future.
 */
internal data class CoroutineEB(val cont: CancellableContinuation<Boolean>)

interface Channel<E> {
    suspend fun send(elem: E)
    suspend fun receive(): E
}


/**
 * The channel is represented as a list of segments, which simulates an infinite array.
 * Each segment has its own [id], which increases from the beginning.
 *
 * The structure of the segment list is manipulated inside the methods [findSegment]
 * and [tryRemoveSegment] and cannot be changed from the outside.
 */
private class ChannelSegment<E>(
    private val channel: IncorrectBufferedChannel<E>,
    val id: Long,
    prevSegment: ChannelSegment<E>?,
) {
    private val next: AtomicRef<ChannelSegment<E>?> = atomic(null)
    private val prev: AtomicRef<ChannelSegment<E>?> = atomic(prevSegment)

    /**
    This counter shows how many cells are marked interrupted in the segment. If the value
    is equal to [SEGMENT_SIZE], it means all cells were interrupted and the segment should be
    physically removed.
     */
    private val interruptedCellsCounter = atomic(0)
    private val interruptedCells: Int get() = interruptedCellsCounter.value

    /**
    Represents an array of slots, the amount of slots is equal to [SEGMENT_SIZE].
    Each slot consists of 2 registers: a state and an element.
     */
    private val data = atomicArrayOfNulls<Any?>(SEGMENT_SIZE * 2)

    // ######################################
    // # Manipulation with the State Fields #
    // ######################################

    internal fun getState(index: Int): Any? = data[index * 2 + 1].value

    internal fun setState(index: Int, value: Any) { data[index * 2 + 1].lazySet(value) }

    internal fun getAndSetState(index: Int, value: Any) = data[index * 2 + 1].getAndSet(value)

    internal fun casState(index: Int, from: Any?, to: Any) = data[index * 2 + 1].compareAndSet(from, to)

    internal fun isStateInterrupted(index: Int) =
        getState(index) == CellState.INTERRUPTED_SEND || getState(index) == CellState.INTERRUPTED_RCV

    // ########################################
    // # Manipulation with the Element Fields #
    // ########################################

    internal fun setElement(index: Int, value: E) { data[index * 2].value = value }

    @Suppress("UNCHECKED_CAST")
    internal fun getElement(index: Int): E? = data[index * 2].value as E?

    internal fun retrieveElement(index: Int): E = getElement(index)!!.also { cleanElement(index) }

    internal fun cleanElement(index: Int) { data[index * 2].lazySet(null) }

    // ###################################################
    // # Manipulation with the segment's neighbour links #
    // ###################################################

    internal fun getNext(): ChannelSegment<E>? = next.value

    private fun casNext(from: ChannelSegment<E>?, to: ChannelSegment<E>?) = next.compareAndSet(from, to)

    internal fun getPrev(): ChannelSegment<E>? = prev.value

    private fun casPrev(from: ChannelSegment<E>?, to: ChannelSegment<E>?) = prev.compareAndSet(from, to)

    // ########################
    // # Cancellation Support #
    // ########################

    /**
    This method is invoked on the cancellation of the coroutine's continuation. When the
    coroutine is cancelled, the cell's state is marked interrupted, its element is set to `null`
    in order to avoid memory leaks and the segment's counter of interrupted cells is increased.

    If the cancelled request is a receiver, the method invokes [IncorrectBufferedChannel.waitExpandBufferCompletion]
    to guarantee that [IncorrectBufferedChannel.expandBuffer] has processed all cells before the segment
    is physically removed.
     */
    internal fun onCancellation(index: Int, isSender: Boolean) {
        val stateOnCancellation = if (isSender) CellState.INTERRUPTED_SEND else CellState.INTERRUPTED_RCV
        if (getAndSetState(index, stateOnCancellation) != stateOnCancellation) {
            // The cell is marked interrupted. Clean the cell to avoid memory leaks.
            cleanElement(index)
            // If the cancelled request is a receiver, wait until `expandBuffer()`-s
            // invoked on the cells before the current one finish.
            if (!isSender) channel.waitExpandBufferCompletion(id * SEGMENT_SIZE + index)
            // Increase the number of interrupted cells and remove the segment physically
            // in case it becomes logically removed.
            increaseInterruptedCellsCounter()
            tryRemoveSegment()
        } else {
            // The cell's state has already been set to INTERRUPTED, no further actions
            // are needed. Finish the [onCancellation] invocation.
            return
        }
    }

    private fun increaseInterruptedCellsCounter() {
        val updatedValue = interruptedCellsCounter.incrementAndGet()
        check(updatedValue <= SEGMENT_SIZE) {
            "Segment $this: some cells were interrupted more than once (counter=$updatedValue, SEGMENT_SIZE=$SEGMENT_SIZE)."
        }
    }

    // ###################################################
    // # Manipulation with the structure of segment list #
    // ###################################################

    /**
    This value shows whether the segment is logically removed. It returns true if all cells
    in the segment were marked interrupted.
     */
    internal val isRemoved: Boolean get() = interruptedCells == SEGMENT_SIZE

    /**
    This method looks for a segment with id equal to or greater than the requested [destSegmentId].
    If there are segments which are logically removed, they are skipped.
     */
    internal fun findSegment(destSegmentId: Long): ChannelSegment<E> {
        var curSegment = this
        while (curSegment.isRemoved || curSegment.id < destSegmentId) {
            val nextSegment = ChannelSegment(id = curSegment.id + 1, prevSegment = curSegment, channel = channel)
            if (curSegment.casNext(null, nextSegment)) {
                // The tail was updated. Check if the old tail should be removed.
                curSegment.tryRemoveSegment()
            }
            curSegment = curSegment.getNext()!!
        }
        return curSegment
    }

    /**
    This method is responsible for removing the segment from the segment list. First, it
    checks if all cells in the segment were interrupted. Then, in case it is true, it removes
    the segment physically by updating the neighbours' [prev] and [next] links.
     */
    internal fun tryRemoveSegment() {
        if (!isRemoved) {
            // There are non-interrupted cells, no need to remove the segment.
            return
        }
        if (getNext() == null) {
            // The tail segment cannot be removed, otherwise it is not guaranteed that each segment has a unique id.
            return
        }
        // TODO remove segment physically
//        // Find the closest non-removed segments on the left and on the right
//        val prev = aliveSegmentLeft()
//        val next = aliveSegmentRight()
//
//        // Update the links
//        prev?.casNext(this, next)
//        next.casPrev(this, prev)
//
//        next.tryRemoveSegment()
//        prev?.tryRemoveSegment()
    }

    /**
    This method is used to find the closest alive segment on the left from `this` segment.
    If such a segment does not exist, `null` is returned.
     */
    private fun aliveSegmentLeft(): ChannelSegment<E>? {
        var cur = getPrev()
        while (cur != null && cur.isRemoved) {
            cur = cur.getPrev()
        }
        return cur
    }

    /**
    This method is used to find the closest alive segment on the right from `this` segment.
    The tail segment is returned, if the end of the segment list is reached.
     */
    private fun aliveSegmentRight(): ChannelSegment<E> {
        var cur = getNext()
        while (cur!!.isRemoved && cur.getNext() != null) {
            cur = cur.getNext()
        }
        return cur
    }

    // #####################################
    // # Validation of the segment's state #
    // #####################################

    override fun toString(): String = "ChannelSegment(id=$id)"

    internal fun validate() {
        var interruptedCells = 0

        for (index in 0 until SEGMENT_SIZE) {
            // Check that there are no memory leaks
            cellValidate(index)
            // Count the actual amount of interrupted cells
            if (isStateInterrupted(index)) interruptedCells++
        }

        // Check that the value of the segment's counter is correct
        check(interruptedCells == this.interruptedCells) { "Segment $this: the segment's counter (${this.interruptedCells}) and the amount of interrupted cells ($interruptedCells) are different." }

        // Check that the segment's state is correct
        when (interruptedCells.compareTo(SEGMENT_SIZE)) {
            -1 -> check(!isRemoved) { "Segment $this: there are non-interrupted cells, but the segment is logically removed." }
            0 -> {
                check(isRemoved) { "Segment $this: all cells were interrupted, but the segment is not logically removed." }
                // Check that the state of each cell is INTERRUPTED
                for (index in 0 until SEGMENT_SIZE) {
                    check(isStateInterrupted(index)) { "Segment $this: the segment is logically removed, but the cell $index is not marked INTERRUPTED." }
                }
            }
            1 -> error("Segment $this: the amount of interrupted cells ($interruptedCells) is greater than SEGMENT_SIZE ($SEGMENT_SIZE).")
        }
    }

    private fun cellValidate(index: Int) {
        when(val state = getState(index)) {
            CellState.IN_BUFFER, CellState.DONE_RCV, CellState.POISONED, CellState.INTERRUPTED_RCV, CellState.INTERRUPTED_SEND -> {
                check(getElement(index) == null) { "Segment $this: state is $state, but the element is not null in cell $index." }
            }
            null, CellState.BUFFERED -> {}
            is Coroutine, is CoroutineEB -> {}
            CellState.RESUMING_BY_RCV, CellState.RESUMING_BY_EB -> error("Segment $this: state is $state, but should be BUFFERED or INTERRUPTED_SEND.")
            else -> error("Unexpected state $state in $this.")
        }
    }
}

enum class CellState {
    /* The cell is in the buffer and the sender should not suspend */
    IN_BUFFER,
    /* The cell stores a buffered element. When a sender comes to a cell which is not covered
       by a receiver yet, it buffers the element and leaves the cell without suspending. */
    BUFFERED,
    /* The sender resumed the suspended receiver and a rendezvous happened */
    DONE_RCV,
    /* When a receiver comes to the cell that is already covered by a sender, but the cell is
       still empty, it breaks the cell by changing its state to `POISONED`. */
    POISONED,
    /* A coroutine was cancelled while waiting for the opposite request. */
    INTERRUPTED_SEND, INTERRUPTED_RCV,
    /* Specifies which entity resumes the sender (a coming receiver or `expandBuffer()`) */
    RESUMING_BY_RCV, RESUMING_BY_EB
}

const val SEGMENT_SIZE = 1