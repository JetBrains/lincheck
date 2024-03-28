/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import sun.nio.ch.lincheck.TestThread
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.ExceptionNumberAndStacktrace
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*
import kotlin.random.*

/**
 * The model checking strategy studies all possible interleavings by increasing the
 * interleaving tree depth -- the number of context switches performed by the strategy.
 *
 * To restrict the number of interleaving to be studied, it is specified in [testCfg].
 * The strategy constructs an interleaving tree, where nodes choose where the next
 * context switch should be performed and to which thread.
 *
 * The strategy does not study the same interleaving twice.
 * The depth of the interleaving tree increases gradually when all possible
 * interleavings of the previous depth are studied. On the current level,
 * the interleavings are studied uniformly, to study as many different ones
 * as possible when the maximal number of interleavings to be studied is lower
 * than the number of all possible interleavings on the current depth level.
 */
internal class ModelCheckingStrategy(
    testCfg: ModelCheckingCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunction: Actor?,
    stateRepresentation: Method?,
    verifier: Verifier,
    val replay: Boolean,
) : ManagedStrategy(testClass, scenario, verifier, validationFunction, stateRepresentation, testCfg) {
    // The number of invocations that the strategy is eligible to use to search for an incorrect execution.
    private val maxInvocations = testCfg.invocationsPerIteration
    // The number of already used invocations.
    private var usedInvocations = 0
    // The maximum number of thread switch choices that strategy should perform
    // (increases when all the interleavings with the current depth are studied).
    private var maxNumberOfSwitches = 0
    // The root of the interleaving tree that chooses the starting thread.
    private var root: InterleavingTreeNode = ThreadChoosingNode((0 until nThreads).toList())
    // This random is used for choosing the next unexplored interleaving node in the tree.
    private val generationRandom = Random(0)
    // The interleaving that will be studied on the next invocation.
    private lateinit var currentInterleaving: Interleaving

    override fun runImpl(): LincheckFailure? {
        currentInterleaving = root.nextInterleaving() ?: return null
        while (usedInvocations < maxInvocations) {
            // run invocation and check its results
            val invocationResult = runInvocation()
            if (suddenInvocationResult is SpinCycleFoundAndReplayRequired) {
                // Restart the current interleaving with
                // the collected knowledge about the detected spin loop.
                currentInterleaving.rollbackAfterSpinCycleFound()
                continue
            }
            usedInvocations++
            checkResult(invocationResult)?.let { failure ->
                runReplayIfPluginEnabled(failure)
                return failure
            }
            // get new unexplored interleaving
            currentInterleaving = root.nextInterleaving() ?: break
        }
        return null
    }

    /**
     * If the plugin enabled and the failure has a trace, passes information about
     * the trace and the failure to the Plugin and run re-run execution to debug it.
     */
    private fun runReplayIfPluginEnabled(failure: LincheckFailure) {
        if (replay && failure.trace != null) {
            // extract trace representation in the appropriate view
            val trace = constructTraceForPlugin(failure, failure.trace)
            // provide all information about the failed test to the debugger
            testFailed(
                failureType = failure.type,
                trace = trace,
                version = lincheckVersion,
                minimalPluginVersion = MINIMAL_PLUGIN_VERSION
            )
            // replay execution while it's needed
            doReplay()
            while (shouldReplayInterleaving()) {
                doReplay()
            }
        }
    }

    override fun shouldInvokeBeforeEvent(): Boolean {
        // We do not check `inIgnoredSection` here because this method is called from instrumented code
        // that should be invoked only outside the ignored section.
        // However, we cannot add `!inIgnoredSection` check here
        // as the instrumented code might call `enterIgnoredSection` just before this call.
        return replay && collectTrace &&
                Thread.currentThread() is TestThread &&
                suddenInvocationResult == null
    }


    /**
     * We provide information about the failure type to the Plugin, but
     * due to difficulties with passing objects like LincheckFailure (as class versions may vary),
     * we use its string representation.
     * The Plugin uses this information to show the failure type to a user.
     */
    private val LincheckFailure.type: String
        get() = when (this) {
            is IncorrectResultsFailure -> "INCORRECT_RESULTS"
            is ObstructionFreedomViolationFailure -> "OBSTRUCTION_FREEDOM_VIOLATION"
            is UnexpectedExceptionFailure -> "UNEXPECTED_EXCEPTION"
            is ValidationFailure -> "VALIDATION_FAILURE"
            is DeadlockOrLivelockFailure -> "DEADLOCK"
        }


    private fun doReplay(): InvocationResult {
        cleanObjectNumeration()
        currentInterleaving = currentInterleaving.copy()
        resetEventIdProvider()
        return runInvocation()
    }

    /**
     * Transforms failure trace to the array of string to pass it to the debugger.
     * (due to difficulties with passing objects like List and TracePoint, as class versions may vary)
     *
     * Each trace point is transformed into the line of type:
     * "type,iThread,callDepth,shouldBeExpanded,eventId,representation".
     *
     * Later, when [testFailed] breakpoint is triggered debugger parses these lines back to trace points.
     *
     * To help the plugin to create execution view, we provide a type for each trace point.
     * Below are the codes of trace point types.
     *
     * | Value                          | Code |
     * |--------------------------------|------|
     * | REGULAR                        | 0    |
     * | ACTOR                          | 1    |
     * | RESULT                         | 2    |
     * | SWITCH                         | 3    |
     * | SPIN_CYCLE_START               | 4    |
     * | SPIN_CYCLE_SWITCH              | 5    |
     * | OBSTRUCTION_FREEDOM_VIOLATION  | 6    |
     */
    private fun constructTraceForPlugin(failure: LincheckFailure, trace: Trace): Array<String> {
        val results = if (failure is IncorrectResultsFailure) failure.results else null
        val nodesList = constructTraceGraph(failure, results, trace, collectExceptionsOrEmpty(failure))
        var sectionIndex = 0
        var node: TraceNode? = nodesList.firstOrNull()
        val representations = mutableListOf<String>()
        while (node != null) {
            when (node) {
                is TraceLeafEvent -> {
                    val event = node.event
                    val eventId = event.eventId
                    val representation = event.toStringImpl(withLocation = false)
                    val type = when (event) {
                        is SwitchEventTracePoint -> {
                            when (event.reason) {
                                SwitchReason.ACTIVE_LOCK -> {
                                    5
                                }
                                else -> 3
                            }
                        }
                        is SpinCycleStartTracePoint -> 4
                        is ObstructionFreedomViolationExecutionAbortTracePoint -> 6
                        else -> 0
                    }

                    if (representation.isNotEmpty()) {
                        representations.add("$type;${node.iThread};${node.callDepth};${node.shouldBeExpanded(false)};${eventId};${representation}")
                    }
                }

                is CallNode -> {
                    val beforeEventId = node.call.eventId
                    val representation = node.call.toStringImpl(withLocation = false)
                    if (representation.isNotEmpty()) {
                        representations.add("0;${node.iThread};${node.callDepth};${node.shouldBeExpanded(false)};${beforeEventId};${representation}")
                    }
                }

                is ActorNode -> {
                    val beforeEventId = -1
                    val representation = node.actorRepresentation
                    if (representation.isNotEmpty()) {
                        representations.add("1;${node.iThread};${node.callDepth};${node.shouldBeExpanded(false)};${beforeEventId};${representation}")
                    }
                }

                is ActorResultNode -> {
                    val beforeEventId = -1
                    val representation = node.resultRepresentation.toString()
                    representations.add("2;${node.iThread};${node.callDepth};${node.shouldBeExpanded(false)};${beforeEventId};${representation}")
                }

                else -> {}
            }

            node = node.next
            if (node == null && sectionIndex != nodesList.lastIndex) {
                node = nodesList[++sectionIndex]
            }
        }
        return representations.toTypedArray()
    }

    private fun collectExceptionsOrEmpty(failure: LincheckFailure): Map<Throwable, ExceptionNumberAndStacktrace> {
        val results = (failure as? IncorrectResultsFailure)?.results ?: return emptyMap()
        return when (val result = collectExceptionStackTraces(results)) {
            is ExceptionStackTracesResult -> result.exceptionStackTraces
            is InternalLincheckBugResult -> emptyMap()
        }
    }

    override fun onNewSwitch(iThread: Int, mustSwitch: Boolean) {
        if (replay && collectTrace) {
            onThreadSwitchesOrActorFinishes()
        }
        if (mustSwitch) {
            // Create new execution position if this is a forced switch.
            // All other execution positions are covered by `shouldSwitch` method,
            // but forced switches do not ask `shouldSwitch`, because they are forced.
            // a choice of this execution position will mean that the next switch is the forced one.
            currentInterleaving.newExecutionPosition(iThread)
        }
    }

    override fun shouldSwitch(iThread: Int): Boolean {
        // Crete a new current position in the same place as where the check is,
        // because the position check and the position increment are dual operations.
        check(iThread == currentThread)
        currentInterleaving.newExecutionPosition(iThread)
        return currentInterleaving.isSwitchPosition()
    }

    override fun initializeInvocation() {
        currentInterleaving.initialize()
        super.initializeInvocation()
    }

    override fun beforePart(part: ExecutionPart) {
        super.beforePart(part)
        val nextThread = when (part) {
            ExecutionPart.INIT -> 0
            ExecutionPart.PARALLEL -> currentInterleaving.chooseThread(0)
            ExecutionPart.POST -> 0
            ExecutionPart.VALIDATION -> 0
        }
        loopDetector.beforePart(nextThread)
        currentThread = nextThread
    }

    override fun chooseThread(iThread: Int): Int =
        currentInterleaving.chooseThread(iThread).also {
           check(it in switchableThreads(iThread)) { """
               Trying to switch the execution to thread $it,
               but only the following threads are eligible to switch: ${switchableThreads(iThread)}
           """.trimIndent() }
        }

    /**
     * An abstract node with an execution choice in the interleaving tree.
     */
    private abstract inner class InterleavingTreeNode {
        private var fractionUnexplored = 1.0
        lateinit var choices: List<Choice>
        var isFullyExplored: Boolean = false
            protected set
        val isInitialized get() = ::choices.isInitialized

        fun nextInterleaving(): Interleaving? {
            if (isFullyExplored) {
                // Increase the maximum number of switches that can be used,
                // because there are no more not covered interleavings
                // with the previous maximum number of switches.
                maxNumberOfSwitches++
                resetExploration()
            }
            // Check if everything is fully explored and there are no possible interleavings with more switches.
            if (isFullyExplored) return null
            return nextInterleaving(InterleavingBuilder())
        }

        abstract fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving

        protected fun resetExploration() {
            if (!isInitialized) {
                // This is a leaf node.
                isFullyExplored = false
                fractionUnexplored = 1.0
                return
            }
            choices.forEach { it.node.resetExploration() }
            updateExplorationStatistics()
        }

        fun finishExploration() {
            isFullyExplored = true
            fractionUnexplored = 0.0
        }

        protected fun updateExplorationStatistics() {
            check(isInitialized) { "An interleaving tree node was not initialized properly. " +
                    "Probably caused by non-deterministic behaviour (WeakHashMap, Object.hashCode, etc)" }
            if (choices.isEmpty()) {
                finishExploration()
                return
            }
            val total = choices.fold(0.0) { acc, choice ->
                acc + choice.node.fractionUnexplored
            }
            fractionUnexplored = total / choices.size
            isFullyExplored = choices.all { it.node.isFullyExplored }
        }

        protected fun chooseUnexploredNode(): Choice {
            if (choices.size == 1) return choices.first()
            // Choose a weighted random child.
            val total = choices.sumByDouble { it.node.fractionUnexplored }
            val random = generationRandom.nextDouble() * total
            var sumWeight = 0.0
            choices.forEach { choice ->
                sumWeight += choice.node.fractionUnexplored
                if (sumWeight >= random)
                    return choice
            }
            // In case of errors because of floating point numbers choose the last unexplored choice.
            return choices.last { !it.node.isFullyExplored }
        }
    }

    /**
     * Represents a choice of a thread that should be next in the execution.
     */
    private inner class ThreadChoosingNode(switchableThreads: List<Int>) : InterleavingTreeNode() {
        init {
            choices = switchableThreads.map { Choice(SwitchChoosingNode(), it) }
        }

        override fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
            val child = chooseUnexploredNode()
            interleavingBuilder.addThreadSwitchChoice(child.value)
            val interleaving = child.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    /**
     * Represents a choice of a position of a thread context switch.
     */
    private inner class SwitchChoosingNode : InterleavingTreeNode() {
        override fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
            val isLeaf = maxNumberOfSwitches == interleavingBuilder.numberOfSwitches
            if (isLeaf) {
                finishExploration()
                if (!isInitialized)
                    interleavingBuilder.addLastNoninitializedNode(this)
                return interleavingBuilder.build()
            }
            val choice = chooseUnexploredNode()
            interleavingBuilder.addSwitchPosition(choice.value)
            val interleaving = choice.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    private inner class Choice(val node: InterleavingTreeNode, val value: Int)

    /**
     * This class specifies an interleaving that is re-producible.
     */
    private inner class Interleaving(
        private val switchPositions: List<Int>,
        private val threadSwitchChoices: List<Int>,
        private var lastNotInitializedNode: SwitchChoosingNode?
    ) {
        private lateinit var interleavingFinishingRandom: Random
        private lateinit var nextThreadToSwitch: Iterator<Int>
        private var lastNotInitializedNodeChoices: MutableList<Choice>? = null
        private var executionPosition: Int = 0

        fun initialize() {
            executionPosition = -1 // the first execution position will be zero
            interleavingFinishingRandom = Random(2) // random with a constant seed
            nextThreadToSwitch = threadSwitchChoices.iterator()
            loopDetector.initialize()
            lastNotInitializedNodeChoices = null
            lastNotInitializedNode?.let {
                // Create a mutable list for the initialization of the not initialized node choices.
                lastNotInitializedNodeChoices = mutableListOf<Choice>().also { choices ->
                    it.choices = choices
                }
                lastNotInitializedNode = null
            }
        }

        fun rollbackAfterSpinCycleFound() {
            lastNotInitializedNodeChoices?.clear()
        }

        fun chooseThread(iThread: Int): Int =
            if (nextThreadToSwitch.hasNext()) {
                // Use the predefined choice.
                nextThreadToSwitch.next()
            } else {
                // There is no predefined choice.
                // This can happen if there were forced thread switches after the last predefined one
                // (e.g., thread end, coroutine suspension, acquiring an already acquired lock or monitor.wait).
                // We use a deterministic random here to choose the next thread.
                lastNotInitializedNodeChoices =
                    null // end of execution position choosing initialization because of new switch
                switchableThreads(iThread).random(interleavingFinishingRandom)
            }

        fun isSwitchPosition() = executionPosition in switchPositions

        /**
         * Creates a new execution position that corresponds to the current switch point.
         * Unlike switch points, the execution position is just a gradually increasing counter
         * which helps to distinguish different switch points.
         */
        fun newExecutionPosition(iThread: Int) {
            executionPosition++
            if (executionPosition > switchPositions.lastOrNull() ?: -1) {
                // Add a new thread choosing node corresponding to the switch at the current execution position.
                lastNotInitializedNodeChoices?.add(Choice(ThreadChoosingNode(switchableThreads(iThread)), executionPosition))
            }
        }

        fun copy() = Interleaving(switchPositions, threadSwitchChoices, lastNotInitializedNode)

    }

    private inner class InterleavingBuilder {
        private val switchPositions = mutableListOf<Int>()
        private val threadSwitchChoices = mutableListOf<Int>()
        private var lastNoninitializedNode: SwitchChoosingNode? = null

        val numberOfSwitches get() = switchPositions.size

        fun addSwitchPosition(switchPosition: Int) {
            switchPositions.add(switchPosition)
        }

        fun addThreadSwitchChoice(iThread: Int) {
            threadSwitchChoices.add(iThread)
        }

        fun addLastNoninitializedNode(lastNoninitializedNode: SwitchChoosingNode) {
            this.lastNoninitializedNode = lastNoninitializedNode
        }

        fun build() = Interleaving(switchPositions, threadSwitchChoices, lastNoninitializedNode)
    }

    companion object {
        /**
         * We provide lincheck version to [testFailed] method to the plugin be able to
         * determine if this version is compatible with the plugin version.
         */
        private val lincheckVersion by lazy { this::class.java.`package`.implementationVersion }
    }
}