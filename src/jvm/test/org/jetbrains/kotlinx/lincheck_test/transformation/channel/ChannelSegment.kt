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

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.atomicArrayOfNulls

/**
 * The channel is represented as a list of segments, which simulates an infinite array.
 * Each segment has its own [id], which increases from the beginning.
 *
 * The structure of the segment list is manipulated inside the methods [findSegment]
 * and [tryRemoveSegment] and cannot be changed from the outside.
 */
internal class ChannelSegment<E>(
    private val channel: BufferedChannel<E>,
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

       If the cancelled request is a receiver, the method invokes [BufferedChannel.waitExpandBufferCompletion]
       to guarantee that [BufferedChannel.expandBuffer] has processed all cells before the segment
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