/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.reporting

import kotlin.math.max

/**
 * Create a new trace node and add it to the end of the list.
 */
internal fun <T : TraceNodeOld> MutableList<TraceNodeOld>.createAndAppend(constructor: (lastNode: TraceNodeOld?) -> T): T =
    constructor(lastOrNull()).also { add(it) }

/**
 * @param sectionsFirstNodes a list of first nodes in each scenario section
 */
internal fun traceGraphToRepresentationList(
    sectionsFirstNodes: List<TraceNodeOld>,
    verboseTrace: Boolean
): List<List<TraceEventRepresentation>> =
    sectionsFirstNodes.map { firstNodeInSection ->
        buildList {
            var curNode: TraceNodeOld? = firstNodeInSection
            while (curNode != null) {
                curNode = curNode.addRepresentationTo(this, verboseTrace)
            }
        }
    }

internal sealed class TraceNodeOld(
    private val prefixProvider: PrefixProvider,
    val iThread: Int,
    last: TraceNodeOld?,
    val callDepth: Int // for tree indentation
) {
    protected val prefix get() = prefixProvider.get()
    // `next` edges form an ordered single-directed event list
    var next: TraceNodeOld? = null

    // `lastInternalEvent` helps to skip internal events if an actor or a method call can be compressed
    abstract val lastInternalEvent: TraceNodeOld

    // `lastState` helps to find the last state needed for the compression
    abstract val lastState: String?

    // whether the internal events should be reported
    abstract fun shouldBeExpanded(verboseTrace: Boolean): Boolean

    init {
        last?.let {
            it.next = this
        }
    }

    /**
     * Adds this node representation to the [traceRepresentation] and returns the next node to be processed.
     */
    abstract fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNodeOld?

    protected fun stateEventRepresentation(iThread: Int, stateRepresentation: String) =
        TraceEventRepresentation(iThread, prefix + "STATE: $stateRepresentation")
}

internal class TraceLeafEvent(
    prefix: PrefixProvider,
    iThread: Int,
    last: TraceNodeOld?,
    callDepth: Int,
    internal val event: TracePoint,
    private val lastExecutedEvent: Boolean = false
) : TraceNodeOld(prefix, iThread, last, callDepth) {

    override val lastState: String? =
        if (event is StateRepresentationTracePoint) event.stateRepresentation else null

    override val lastInternalEvent: TraceNodeOld = this

    private val TracePoint.isBlocking: Boolean get() = when (this) {
        is MonitorEnterTracePoint, is WaitTracePoint, is ParkTracePoint -> true
        else -> false
    }

    // virtual trace points are not displayed in the trace
    private val TracePoint.isVirtual: Boolean get() = when (this) {
        is ThreadStartTracePoint, is ThreadJoinTracePoint -> true
        else -> false
    }

    override fun shouldBeExpanded(verboseTrace: Boolean): Boolean {
        return (lastExecutedEvent && event.isBlocking)
                || event is SwitchEventTracePoint
                || event is ObstructionFreedomViolationExecutionAbortTracePoint
                || verboseTrace
    }

    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNodeOld? {
        if (!event.isVirtual) {
            val representation = prefix + event.toString()
            traceRepresentation.add(TraceEventRepresentation(iThread, representation))
        }
        return next
    }
}

internal abstract class TraceInnerNode(prefixProvider: PrefixProvider, iThread: Int, last: TraceNodeOld?, callDepth: Int) :
    TraceNodeOld(prefixProvider, iThread, last, callDepth)
{
    override val lastState: String?
        get() = _internalEvents.map { it.lastState }.lastOrNull { it != null }

    override val lastInternalEvent: TraceNodeOld
        get() = if (_internalEvents.isEmpty()) this else _internalEvents.last().lastInternalEvent

    private val _internalEvents = mutableListOf<TraceNodeOld>()
    internal val internalEvents: List<TraceNodeOld> get() = _internalEvents

    override fun shouldBeExpanded(verboseTrace: Boolean) =
        _internalEvents.any {
            it.shouldBeExpanded(verboseTrace)
        }

    fun addInternalEvent(node: TraceNodeOld) {
        _internalEvents.add(node)
    }
}

internal class CallNodeOld(
    prefixProvider: PrefixProvider,
    iThread: Int,
    last: TraceNodeOld?,
    callDepth: Int,
    internal val call: MethodCallTracePoint
) : TraceInnerNode(prefixProvider, iThread, last, callDepth) {
    // suspended method contents should be reported
    override fun shouldBeExpanded(verboseTrace: Boolean): Boolean {
        return call.wasSuspended || super.shouldBeExpanded(verboseTrace)
    }

    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNodeOld? =
        if (!shouldBeExpanded(verboseTrace)) {
            traceRepresentation.add(TraceEventRepresentation(iThread, prefix + "$call"))
            lastState?.let { traceRepresentation.add(stateEventRepresentation(iThread, it)) }
            lastInternalEvent.next
        } else {
            traceRepresentation.add(TraceEventRepresentation(iThread, prefix + "$call"))
            next
        }
}

internal class ActorNodeOld(
    prefixProvider: PrefixProvider,
    iThread: Int,
    last: TraceNodeOld?,
    callDepth: Int,
    internal val actorRepresentation: String,
    private val resultRepresentation: String?
) : TraceInnerNode(prefixProvider, iThread, last, callDepth) {
    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNodeOld? {
        val actorRepresentation = prefix + actorRepresentation + if (resultRepresentation != null) ": $resultRepresentation" else ""
        traceRepresentation.add(TraceEventRepresentation(iThread, actorRepresentation))
        return if (!shouldBeExpanded(verboseTrace)) {
            lastState?.let { traceRepresentation.add(stateEventRepresentation(iThread, it)) }
            lastInternalEvent.next
        } else {
            next
        }
    }
}

internal class ActorResultNode(
    prefixProvider: PrefixProvider,
    iThread: Int,
    last: TraceNodeOld?,
    callDepth: Int,
    internal val resultRepresentation: String?,
    /**
     * This value presents only if an exception was the actor result.
     */
    internal val exceptionNumberIfExceptionResult: Int?
) : TraceNodeOld(prefixProvider, iThread, last, callDepth) {
    override val lastState: String? = null
    override val lastInternalEvent: TraceNodeOld = this
    override fun shouldBeExpanded(verboseTrace: Boolean): Boolean = false

    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNodeOld? {
        if (resultRepresentation != null)
            traceRepresentation.add(TraceEventRepresentation(iThread, prefix + "result: $resultRepresentation"))
        return next
    }
}

/**
 * Provides the prefix output for the [TraceNodeOld].
 * @see TraceNodePrefixFactory
 */
internal fun interface PrefixProvider {
    fun get(): String
}

/**
 * When we create the trace representation, it may need to add two additional spaces before each line is we have a
 * spin cycle starting in call depth 1.
 *
 * This factory encapsulates the logic of creating [PrefixProvider] for different call depths with
 * spin cycle arrows or without.
 *
 * At the beginning of the trace processing, we can't know definitely should we add
 * extra spaces at the beginning of the each line or not.
 * That's why we return [PrefixProvider] closure that have a reference on this factory field,
 * so when trace nodes are composing output, we definitely know should we add extra spaces or not.
 *
 * Example when extra spaces needed:
 * |   one(): <hung>                                                                                                                          |                                                                                                                                          |
 * |     meaninglessActions2() at RecursiveTwoThreadsSpinLockTest.one(RecursiveSpinLockTest.kt:221)                                           |                                                                                                                                          |
 * |     /* The following events repeat infinitely: */                                                                                        |                                                                                                                                          |
 * | ┌╶> meaninglessActions1() at RecursiveTwoThreadsSpinLockTest.one(RecursiveSpinLockTest.kt:222)                                           |                                                                                                                                          |
 * | |     sharedState2.compareAndSet(false,true): false at RecursiveTwoThreadsSpinLockTest.meaninglessActions1(RecursiveSpinLockTest.kt:242) |                                                                                                                                          |
 * | └╶╶╶╶ switch (reason: active lock detected)                                                                                              |                                                                                                                                          |
 * |
 *
 * Example when no extra spaces needed:
 * | cas2_0(0, 0, 2, 1, 0, 3): <hung>                                                                                                                                             |                                                                                                                                                                            |
 * |   array.cas2(0,0,2,1,0,3,0) at BrokenCas2RecursiveLiveLockTest.cas2_0(RecursiveSpinLockTest.kt:271)                                                                          |                                                                                                                                                                            |
 * |     AtomicArrayWithCAS2$Descriptor.apply$default(Descriptor#3,false,0,false,5,null) at AtomicArrayWithCAS2.cas2(RecursiveSpinLockTest.kt:323)                                |                                                                                                                                                                            |
 * |       Descriptor#3.apply(true,0,false) at AtomicArrayWithCAS2$Descriptor.apply$default(RecursiveSpinLockTest.kt:349)                                                         |                                                                                                                                                                            |
 * |         AtomicArrayWithCAS2$Descriptor.installOrHelp$default(Descriptor#3,true,0,false,4,null) at AtomicArrayWithCAS2$Descriptor.apply(RecursiveSpinLockTest.kt:356)         |                                                                                                                                                                            |
 * |           Descriptor#3.installOrHelp(true,0,false) at AtomicArrayWithCAS2$Descriptor.installOrHelp$default(RecursiveSpinLockTest.kt:368)                                     |                                                                                                                                                                            |
 * |             BrokenCas2RecursiveLiveLockTest#1.array.gate0.READ: 1 at AtomicArrayWithCAS2$Descriptor.installOrHelp(RecursiveSpinLockTest.kt:390)                              |                                                                                                                                                                            |
 * |             /* The following events repeat infinitely: */                                                                                                                    |                                                                                                                                                                            |
 * |         ┌╶> Descriptor#2.apply(false,0,true) at AtomicArrayWithCAS2$Descriptor.installOrHelp(RecursiveSpinLockTest.kt:391)                                                   |                                                                                                                                                                            |
 * |         |     status.READ: SUCCESS at AtomicArrayWithCAS2$Descriptor.apply(RecursiveSpinLockTest.kt:351)                                                                     |                                                                                                                                                                            |
 * |         |     status.compareAndSet(SUCCESS,SUCCESS): true at AtomicArrayWithCAS2$Descriptor.apply(RecursiveSpinLockTest.kt:352)                                              |                                                                                                                                                                            |
 * |         |     installOrHelp(true,0,true) at AtomicArrayWithCAS2$Descriptor.apply(RecursiveSpinLockTest.kt:353)                                                               |                                                                                                                                                                            |
 * |         |       BrokenCas2RecursiveLiveLockTest#1.array.array.READ: AtomicReferenceArray#1 at AtomicArrayWithCAS2$Descriptor.installOrHelp(RecursiveSpinLockTest.kt:373)     |                                                                                                                                                                            |
 * |         |       AtomicReferenceArray#1[0].get(): Descriptor#2 at AtomicArrayWithCAS2$Descriptor.installOrHelp(RecursiveSpinLockTest.kt:373)                                  |                                                                                                                                                                            |
 * |         └╶╶╶╶╶╶ switch (reason: active lock detected)
 *
 */
internal class TraceNodePrefixFactory(nThreads: Int) {

    /**
     * Indicates should we add extra spaces to all the thread lines or not.
     */
    private val extraIndentPerThread = BooleanArray(nThreads) { false }

    /**
     * Tells if the next node is the first node of the spin cycle.
     */
    private var nextNodeIsSpinCycleStart = false

    /**
     * Tells if we're processing spin cycle nodes now.
     */
    private var inSpinCycle = false

    /**
     * Call depth of the first node in the current spin cycle.
     */
    private var arrowDepth: Int = -1

    fun actorNodePrefix(iThread: Int) = PrefixProvider { extraPrefixIfNeeded(iThread) }

    fun actorResultPrefix(iThread: Int, callDepth: Int) =
        PrefixProvider { extraPrefixIfNeeded(iThread) + TRACE_INDENTATION.repeat(callDepth) }

    fun prefix(event: TracePoint, callDepth: Int): PrefixProvider {
        val isCycleEnd = inSpinCycle && (event is ObstructionFreedomViolationExecutionAbortTracePoint || event is SwitchEventTracePoint)
        return prefixForNode(event.iThread, callDepth, isCycleEnd).also {
            nextNodeIsSpinCycleStart = event is SpinCycleStartTracePoint
            if (isCycleEnd) {
                inSpinCycle = false
            }
        }
    }

    fun prefixForCallNode(iThread: Int, callDepth: Int): PrefixProvider {
        return prefixForNode(iThread, callDepth, false)
    }

    private fun prefixForNode(iThread: Int, callDepth: Int, isCycleEnd: Boolean): PrefixProvider {
        if (nextNodeIsSpinCycleStart) {
            inSpinCycle = true
            nextNodeIsSpinCycleStart = false
            val extraPrefixRequired = callDepth == 1
            if (extraPrefixRequired) {
                extraIndentPerThread[iThread] = true
            }
            arrowDepth = callDepth
            val arrowDepth = arrowDepth
            return PrefixProvider {
                val extraPrefix = if (arrowDepth == 1) 0 else extraPrefixLength(iThread)
                TRACE_INDENTATION.repeat(max(0, arrowDepth - 2 + extraPrefix)) + "┌╶> "
            }
        }
        if (isCycleEnd) {
            val arrowDepth = arrowDepth
            return PrefixProvider {
                val extraPrefix = if (arrowDepth == 1) 0 else extraPrefixLength(iThread)
                TRACE_INDENTATION.repeat(max(0, arrowDepth - 2 + extraPrefix)) + "└╶" + "╶╶".repeat(max(0, callDepth - arrowDepth)) + "╶ "
            }
        }
        if (inSpinCycle) {
            val arrowDepth = arrowDepth
            return PrefixProvider {
                val extraPrefix = if (arrowDepth == 1) 0 else extraPrefixLength(iThread)
                TRACE_INDENTATION.repeat(max(0, arrowDepth - 2 + extraPrefix)) + "| " + TRACE_INDENTATION.repeat(max(0, callDepth - arrowDepth + 1))
            }
        }
        return PrefixProvider { extraPrefixIfNeeded(iThread) + TRACE_INDENTATION.repeat(callDepth) }
    }

    private fun extraPrefixIfNeeded(iThread: Int): String = if (extraIndentPerThread[iThread]) "  " else ""
    private fun extraPrefixLength(iThread: Int): Int = if (extraIndentPerThread[iThread]) 1 else 0
}

private const val TRACE_INDENTATION = "  "
    
    