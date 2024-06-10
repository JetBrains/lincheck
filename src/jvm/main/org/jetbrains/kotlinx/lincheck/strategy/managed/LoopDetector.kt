/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.primitiveHashCodeOrSystemHashCode
import java.util.ArrayList

/**
 * The LoopDetector class identifies loops, active locks, and live locks by monitoring the frequency of visits to the same code location.
 * It operates under a specific scenario constraint due to its reliance on cache information about loops,
 * determined by thread executions and switches, which is only reusable in a single scenario.
 *
 * The LoopDetector functions in two modes: default and replay mode.
 *
 * In the default mode:
 *
 * In the default mode, LoopDetector has two states.
 * When a spin cycle is not detected yet, LoopDetector ignores method parameters and read/write values and receivers,
 * operating only with switch points code locations.
 * When a spin cycle is detected, LoopDetector requests the replay of the execution and starts tracking
 * the parameters, etc., to detect a spin cycle period properly, taking into account all changes during the spin cycle.
 * When we run into a spin cycle again, LoopDetector tries to find the period using [currentThreadCodeLocationsHistory].
 * Parameters etc. are converted to the negative integer representation using hashcode (see [paramToIntRepresentation]).
 * But sometimes it's not possible, for example, in case of using random. In that case, LoopDetector omits parameters,
 * read/written values, etc., and tries to find the period using only switch points code locations.
 * Once it's found (or not) LoopDetector requests one more replay to avoid side effects, made by this spin cycle.
 *
 * - The LoopDetector tracks code location executions (using [currentThreadCodeLocationsHistory]) performed by threads.
 * The history is stored for the current thread and is cleared during a thread switch.
 * - A map ([currentThreadCodeLocationVisitCountMap]) is maintained to track the number of times a thread visits a certain code location.
 * This map is also cleared during a thread switch.
 * - If a code location is visited more than a defined [hangingDetectionThreshold], it is considered as a spin cycle.
 * The LoopDetector then tries to identify the sequence of actions leading to the spin cycle.
 * Once identified, this sub-interleaving is stored for future avoidance.
 * - A history of executions and switches is maintained to record the sequence of actions and thread switches.
 * - A [loopTrackingCursor] tracks executions and thread switches to facilitate early thread switches.
 * - A counter for operation execution [totalExecutionsCount] across all threads is maintained.
 * This counter increments with each code location visit and is increased by the hangingDetectionThreshold if a spin cycle is detected early.
 * - If the counter exceeds the [ManagedCTestConfiguration.LIVELOCK_EVENTS_THRESHOLD], a total deadlock is assumed.
 * Due to the relative small size of scenarios generated by Lincheck, such a high number of executions indicates a lack of progress in the system.
 *
 * In the replay mode:
 * - The number of allowable events to execute in each thread is determined using saved information from the last interleaving.
 * - For instance, if the [currentInterleavingHistory] is [0: 2], [1: 3], [0: 3], [1: 3], [0: 3], ..., [1: 3], [0: 3] and a deadlock is detected,
 * the cycle is identified as [1: 3], [0: 3].
 * This means 2 executions in thread 0 and 3 executions in both threads 1 and 0 will be allowed.
 * - Execution is halted after the last execution in thread 0 using [ForcibleExecutionFinishError].
 * - The logic for tracking executions and switches in replay mode is implemented in [ReplayModeLoopDetectorHelper].
 *
 * Note: An example of this behavior is detailed in the comments of the code itself.
 */
internal class LoopDetector(
    private val hangingDetectionThreshold: Int
) {
    private var lastExecutedThread = -1 // no last thread

    /**
     * Map, which helps us to determine how many times current thread visits some code location.
     */
    private val currentThreadCodeLocationVisitCountMap = mutableMapOf<Int, Int>()

    /**
     * Is used to find a cycle period inside exact thread execution if it has hung
     */
    private val currentThreadCodeLocationsHistory = mutableListOf<Int>()

    /**
     *  Threads switches and executions history to store sequences lead to loops
     */
    private val currentInterleavingHistory = ArrayList<InterleavingHistoryNode>()

    /**
     * Set of interleaving event sequences lead to loops. (A set of previously detected hangs)
     */
    private val interleavingsLeadToSpinLockSet = InterleavingSequenceTrackableSet()

    /**
     * Helps to determine does current interleaving equal to some saved interleaving leading to spin cycle or not
     */
    private val loopTrackingCursor = interleavingsLeadToSpinLockSet.cursor

    private var totalExecutionsCount = 0

    private val firstThreadSet: Boolean get() = lastExecutedThread != -1

    /**
     * Delegate helper, active in replay (trace collection) mode.
     * It just tracks executions and switches and helps to halt execution or switch in case of spin-lock early.
     */
    private var replayModeLoopDetectorHelper: ReplayModeLoopDetectorHelper? = null

    private val replayModeCurrentCyclePeriod: Int
        get() = replayModeLoopDetectorHelper?.currentCyclePeriod ?: 0

    val replayModeEnabled: Boolean
        get() = replayModeLoopDetectorHelper != null

    /**
     * Indicates if we analyze method parameters to calculate the spin-cycle period.
     */
    private var cycleCalculationPhase: Boolean = false

    /**
     * Indicates that we are in a spin cycle iteration now.
     * Should be called only in replay mode.
     */
    val replayModeCurrentlyInSpinCycle: Boolean get() = replayModeLoopDetectorHelper!!.currentlyInSpinCycle

    fun enableReplayMode(failDueToDeadlockInTheEnd: Boolean) {
        val contextSwitchesBeforeHalt =
            findMaxPrefixLengthWithNoCycleOnSuffix(currentInterleavingHistory)?.let { it.executionsBeforeCycle + it.cyclePeriod }
                ?: currentInterleavingHistory.size
        val spinCycleInterleavingHistory = currentInterleavingHistory.take(contextSwitchesBeforeHalt)
        // Remove references to interleaving tree
        interleavingsLeadToSpinLockSet.clear()
        loopTrackingCursor.clear()

        replayModeLoopDetectorHelper = ReplayModeLoopDetectorHelper(
            interleavingHistory = spinCycleInterleavingHistory,
            failDueToDeadlockInTheEnd = failDueToDeadlockInTheEnd
        )
    }

    fun shouldSwitchInReplayMode(): Boolean {
        return replayModeLoopDetectorHelper!!.run {
            onNextExecution()
            shouldSwitch()
        }
    }

    /**
     * The `Decision` sealed class represents the different decisions
     * that can be made by the loop detector.
     */
    sealed class Decision {
        /**
         * This decision is returned when no livelock is detected,
         * and no further actions are required.
         */
        data object Idle : Decision()
        /**
         * This decision is returned when the execution has generated
         * a total number of events exceeding the predefined threshold.
         */
        data object EventsThresholdReached : Decision()

        /**
         * This decision is returned when the live-lock is detected and replay required to avoid potential side-effects.
         */
        data object LivelockReplayRequired : Decision()

        /**
         * This decision is returned when the live-lock is detected for the first time,
         * and we have to replay the execution analyzing method parameters additionally to
         * calculate the spin cycle period.
         */
        data object LivelockReplayToDetectCycleRequired : Decision()

        /**
         * This decision is returned when global live-lock is detected decisively,
         * and the execution has to be aborted with the failure.
         *
         * @property cyclePeriod The period of the spin-loop cycle.
         */
        data class LivelockFailureDetected(val cyclePeriod: Int) : Decision()

        /**
         * This decision is returned when a single thread enters a live-lock,
         * and a switch to other threads is required to see if they can unblock the execution.
         *
         * @property cyclePeriod The period of the spin-loop cycle.
         */
        data class LivelockThreadSwitch(val cyclePeriod: Int) : Decision()
    }

    /**
     * Updates the internal state of the loop detector to account for
     * the fact that thread [iThread] hits [codeLocation].
     *
     * The loop detector performs checks if the live-lock occurred,
     * and returns its decision.
     *
     * @see LoopDetector.Decision
     */
    fun visitCodeLocation(iThread: Int, codeLocation: Int): Decision {
        replayModeLoopDetectorHelper?.let {
            return if (it.shouldSwitch()) it.detectLivelock() else Decision.Idle
        }
        // Increase the total number of happened operations for live-lock detection
        totalExecutionsCount++
        // Has the thread changed? Reset the counters in this case.
        check(lastExecutedThread == iThread) { "reset expected!" }
        // Ignore coroutine suspension code locations.
        if (codeLocation == COROUTINE_SUSPENSION_CODE_LOCATION) return Decision.Idle
        // Increment the number of times the specified code location is visited.
        val count = currentThreadCodeLocationVisitCountMap.getOrDefault(codeLocation, 0) + 1
        currentThreadCodeLocationVisitCountMap[codeLocation] = count
        currentThreadCodeLocationsHistory += codeLocation
        val detectedFirstTime = count > hangingDetectionThreshold
        val detectedEarly = loopTrackingCursor.isInCycle
        // DetectedFirstTime and detectedEarly can both sometimes be true
        // when we can't find a cycle period and can't switch to another thread.
        // Check whether the count exceeds the maximum number of repetitions for loop/hang detection.
        if (detectedFirstTime && !detectedEarly) {
            if (!cycleCalculationPhase) {
                // Turn on parameters and read/write values and receivers tracking and request one more replay.
                cycleCalculationPhase = true
                return Decision.LivelockReplayToDetectCycleRequired
            }
            registerCycle()
            // Turn off parameters tracking and request one more replay to avoid side effects.
            cycleCalculationPhase = false
            // Enormous operations count considered as total spin lock
            if (totalExecutionsCount > ManagedCTestConfiguration.LIVELOCK_EVENTS_THRESHOLD) {
                return Decision.EventsThresholdReached
            }
            // Replay current interleaving to avoid side effects caused by multiple cycle executions
            return Decision.LivelockReplayRequired
        }
        if (!detectedFirstTime && detectedEarly) {
            totalExecutionsCount += hangingDetectionThreshold
            val lastNode = currentInterleavingHistory.last()
            // spinCyclePeriod may be not 0 only we tried to switch
            // from the current thread but no available threads were available to switch
            if (lastNode.spinCyclePeriod == 0) {
                // transform current node to the state corresponding to early found cycle
                val cyclePeriod = loopTrackingCursor.cyclePeriod
                lastNode.executions -= cyclePeriod
                lastNode.spinCyclePeriod = cyclePeriod
                lastNode.executionHash = loopTrackingCursor.cycleLocationsHash
            }
            // Enormous operations count considered as total spin lock
            if (totalExecutionsCount > ManagedCTestConfiguration.LIVELOCK_EVENTS_THRESHOLD) {
                return Decision.EventsThresholdReached
            }
        }
        val cyclePeriod = replayModeCurrentCyclePeriod
        return if (detectedFirstTime || detectedEarly)
            Decision.LivelockThreadSwitch(cyclePeriod)
        else
            Decision.Idle
    }

    /**
     * Called before regular method calls.
     */
    fun beforeRegularMethodCall(codeLocation: Int, params: Array<Any?>) {
        replayModeLoopDetectorHelper?.let {
            it.onNextExecution()
            return
        }
        currentThreadCodeLocationsHistory += codeLocation
        passParameters(params)
        val lastInterleavingHistoryNode = currentInterleavingHistory.last()
        if (lastInterleavingHistoryNode.cycleOccurred) {
            return /* If we already ran into cycle and haven't switched than no need to track executions */
        }
        lastInterleavingHistoryNode.addExecution(codeLocation)
        loopTrackingCursor.onNextExecutionPoint()
    }

    /**
     * Called after any method calls.
     */
    fun afterRegularMethodCall() {
        val afterMethodCallLocation = 0
        replayModeLoopDetectorHelper?.let {
            it.onNextExecution()
            return
        }
        currentThreadCodeLocationsHistory += afterMethodCallLocation
        val lastInterleavingHistoryNode = currentInterleavingHistory.last()
        if (lastInterleavingHistoryNode.cycleOccurred) {
            return /* If we already ran into cycle and haven't switched than no need to track executions */
        }
        lastInterleavingHistoryNode.addExecution(afterMethodCallLocation)
        loopTrackingCursor.onNextExecutionPoint()
    }

    /**
     * Called when we pass some parameters before method call in the instrumented call.
     * Used only if LoopDetector is in the cycle calculation mode.
     * Otherwise, does nothing.
     */
    fun passParameters(params: Array<Any?>) {
        if (!cycleCalculationPhase) return
        params.forEach { param ->
            currentThreadCodeLocationsHistory += paramToIntRepresentation(param)
        }
    }

    /**
     * Called when we:
     * - Read/write some value.
     * - Use some object as a receiver (receiver is passed as a parameter [obj]).
     * - Use an index to access an array cell (index is passed as a parameter [obj]).
     *
     * Used only if LoopDetector is in the cycle calculation mode.
     * Otherwise, does nothing.
     */
    fun passValue(obj: Any?) {
        if (!cycleCalculationPhase) return
        currentThreadCodeLocationsHistory += paramToIntRepresentation(obj)
    }

    fun onActorStart(iThread: Int) {
        check(iThread == lastExecutedThread)
        // if a thread has reached a new actor, then it means it has made some progress;
        // therefore, we reset the code location counters,
        // so that code location hits from a previous actor do not affect subsequent actors
        currentThreadCodeLocationVisitCountMap.clear()
    }

    fun onThreadSwitch(iThread: Int) {
        lastExecutedThread = iThread
        currentThreadCodeLocationVisitCountMap.clear()
        currentThreadCodeLocationsHistory.clear()
        onNextThreadSwitchPoint(iThread)
    }

    fun onThreadFinish(iThread: Int) {
        check(iThread == lastExecutedThread)
        onNextExecutionPoint(executionIdentity = -iThread)
    }

    private fun onNextThreadSwitchPoint(nextThread: Int) {
        /*
            When we're back to some thread, newSwitchPoint won't be called before the fist
            in current thread part as it was called before switch.
            So, we're tracking that to maintain the number of performed operations correctly.
         */
        if (currentInterleavingHistory.isNotEmpty() && currentInterleavingHistory.last().threadId == nextThread) {
            return
        }
        currentInterleavingHistory.add(
            InterleavingHistoryNode(
                threadId = nextThread,
                executions = 0,
            )
        )
        loopTrackingCursor.onNextSwitchPoint(nextThread)
        replayModeLoopDetectorHelper?.onNextSwitch()
    }

    /**
     * Is called after switch back to a thread.
     * Required because after we switch back to the thread no `visitCodeLocations` will be called before the next
     * execution point, as it was called earlier.
     * But we need to track that this point is going to
     * be executed after the switch, so we pass it after the switch back,
     * but before the instruction is actually executed.
     */
    fun initializeFirstCodeLocationAfterSwitch(codeLocation: Int) {
        replayModeLoopDetectorHelper?.let { helper ->
            helper.onNextExecution()
            return
        }
        onNextExecutionPoint(codeLocation)
        // Increase the total number of happened operations for live-lock detection
        totalExecutionsCount++
        // Increment the number of times the specified code location is visited.
        val count = currentThreadCodeLocationVisitCountMap.getOrDefault(codeLocation, 0) + 1
        currentThreadCodeLocationVisitCountMap[codeLocation] = count
        currentThreadCodeLocationsHistory += codeLocation
    }

    /**
     * Called only when replay mode is disabled.
     */
    fun onNextExecutionPoint(executionIdentity: Int) {
        val lastInterleavingHistoryNode = currentInterleavingHistory.last()
        if (lastInterleavingHistoryNode.cycleOccurred) {
            return /* If we already ran into cycle and haven't switched than no need to track executions */
        }
        lastInterleavingHistoryNode.addExecution(executionIdentity)
        loopTrackingCursor.onNextExecutionPoint()
    }

    private fun registerCycle() {
        val (cycleInfo, switchPointsCodeLocationsHistory) = tryFindCycleWithParamsOrWithout()

        if (cycleInfo == null) {
            val lastNode = currentInterleavingHistory.last()
            val cycleStateLastNode = lastNode.asNodeCorrespondingToCycle(
                executionsBeforeCycle = switchPointsCodeLocationsHistory.size - 1,
                cyclePeriod = 0,
                cycleExecutionsHash = lastNode.executionHash // corresponds to a cycle
            )

            currentInterleavingHistory[currentInterleavingHistory.lastIndex] = cycleStateLastNode
            interleavingsLeadToSpinLockSet.addBranch(currentInterleavingHistory)
            return
        }
        /*
        For nodes, correspond to cycles we re-calculate hash using only code locations related to the cycle,
        because if we run into a DeadLock,
        it's enough to show events before the cycle and first cycle iteration in the current thread.
        For example,
        [threadId = 0, executions = 10],
        [threadId = 1, executions = 5], // 2 executions before cycle and then cycle of 3 executions begins
        [threadId = 0, executions = 3],
        [threadId = 1, executions = 3],
        [threadId = 0, executions = 3],
        ...
        [threadId = 1, executions = 3],
        [threadId = 0, executions = 3]

        In this situation, we have a spin cycle:[threadId = 1, executions = 3], [threadId = 0, executions = 3].
        We want to cut off events suffix to get:
        [threadId = 0, executions = 10],
        [threadId = 1, executions = 5], // 2 executions before cycle, and then cycle begins
        [threadId = 0, executions = 3],

        So we need to [threadId = 1, executions = 5] execution part to have a hash equals to next cycle nodes,
        because we will take only thread executions before cycle and the first cycle iteration.
         */
        var cycleExecutionLocationsHash = switchPointsCodeLocationsHistory[cycleInfo.executionsBeforeCycle]
        for (i in cycleInfo.executionsBeforeCycle + 1 until cycleInfo.executionsBeforeCycle + cycleInfo.cyclePeriod) {
            cycleExecutionLocationsHash = cycleExecutionLocationsHash xor switchPointsCodeLocationsHistory[i]
        }

        val cycleStateLastNode = currentInterleavingHistory.last().asNodeCorrespondingToCycle(
            executionsBeforeCycle = cycleInfo.executionsBeforeCycle,
            cyclePeriod = cycleInfo.cyclePeriod,
            cycleExecutionsHash = cycleExecutionLocationsHash // corresponds to a cycle
        )

        currentInterleavingHistory[currentInterleavingHistory.lastIndex] = cycleStateLastNode
        interleavingsLeadToSpinLockSet.addBranch(currentInterleavingHistory)
    }

    /**
     * Tries to find a spin cycle in [currentThreadCodeLocationsHistory] taking parameters
     * and values into account, or, if it's not possible, without it, using just switch points code locations.
     *
     * @return a pair of a [CycleInfo], corresponding to the spin cycle in a code locations history
     * **without** parameters and values, (i.e. considering only code locations) and a list of visited code
     * locations **without** parameters and values, only potential switch points.
     */
    private fun tryFindCycleWithParamsOrWithout(): Pair<CycleInfo?, List<Int>> {
        // Get the code locations history of potential switch points, without parameters and values.
        // Potential switch point code locations are >= 0.
        val historyWithoutParams = currentThreadCodeLocationsHistory.filter { it >= 0 }
        // Trying to find a cycle with them.
        val cycleInfo = findMaxPrefixLengthWithNoCycleOnSuffix(currentThreadCodeLocationsHistory)
            // If it's not possible - searching for the cycle in the filtered history list - without params and values.
            ?: return findMaxPrefixLengthWithNoCycleOnSuffix(historyWithoutParams) to historyWithoutParams

        // If we found a spin cycle in the code locations history with parameters and values -
        // than we need to calculate how many executions,
        // that are potential switch points, before and during the cycle happened.
        // We need it because in normal mode LoopDetector only uses potential switch points code locations
        // to detect spin locks, so we have to count code locations, that don't represent parameters or values.
        var operationsBefore = 0
        var cyclePeriod = 0

        var operationsBeforeWithParams = 0
        var cyclePeriodWithParams = 0

        var i = 0
        // Count how many potential switch point executions happened before the spin cycle.
        while (operationsBeforeWithParams < cycleInfo.executionsBeforeCycle) {
            // Potential switch point code locations are >= 0.
            if (currentThreadCodeLocationsHistory[i] >= 0) {
                operationsBefore++
            }
            operationsBeforeWithParams++
            i++
        }
        while (cyclePeriodWithParams < cycleInfo.cyclePeriod) {
            // Potential switch point code locations are >= 0.
            if (currentThreadCodeLocationsHistory[i] >= 0) {
                cyclePeriod++
            }
            cyclePeriodWithParams++
            i++
        }

        return CycleInfo(operationsBefore, cyclePeriod) to historyWithoutParams
    }

    /**
     * Is called before each interleaving part processing
     */
    fun beforePart(nextThread: Int) {
        if (!firstThreadSet) {
            setFirstThread(nextThread)
        } else if (lastExecutedThread != nextThread) {
            onThreadSwitch(nextThread)
        }
    }

    /**
     * Is called before each interleaving processing
     */
    fun initialize() {
        lastExecutedThread = -1
    }

    private fun setFirstThread(iThread: Int) {
        lastExecutedThread = iThread // certain last thread
        currentThreadCodeLocationVisitCountMap.clear()
        currentThreadCodeLocationsHistory.clear()
        totalExecutionsCount = 0

        loopTrackingCursor.reset(iThread)
        currentInterleavingHistory.clear()
        currentInterleavingHistory.add(InterleavingHistoryNode(threadId = iThread))
        replayModeLoopDetectorHelper?.initialize()
    }

    /**
     * Prints the interleaving sequences tree set.
     * Utility function to debug spin-locks related internals.
     */
    @Suppress("unused")
    internal fun treeToString() = interleavingsLeadToSpinLockSet.treeToString()

    /**
     * Maps any parameter, receiver or read value to a **negative** integer value.
     * We map in the **negative** number as we want to be able to filter a code locations history list
     * and drop all elements that don't represent potential switch points - all parameter, receiver
     * and value representations produced by this method.
     */
    private fun paramToIntRepresentation(value: Any?): Int {
        var hashCode = primitiveHashCodeOrSystemHashCode(value)
        if (hashCode < 0) return hashCode
        hashCode++

        return if (hashCode > 0) -hashCode else hashCode
    }

}

internal fun LoopDetector.Decision.isLivelockDetected() = when (this) {
    is LoopDetector.Decision.LivelockThreadSwitch,
    is LoopDetector.Decision.LivelockReplayRequired,
    is LoopDetector.Decision.LivelockFailureDetected -> true

    else -> false
}

/**
 * Helper class to halt execution on replay (trace collection phase) and to switch thread early on spin-cycles
 */
private class ReplayModeLoopDetectorHelper(
    private val interleavingHistory: List<InterleavingHistoryNode>,
    /**
     * Should we fail with deadlock failure when all events in the current interleaving are completed
     */
    private val failDueToDeadlockInTheEnd: Boolean,
) {

    /**
     * Cycle period if is occurred in during current thread switch or 0 if no spin-cycle happened
     */
    val currentCyclePeriod: Int get() = interleavingHistory[currentInterleavingNodeIndex].spinCyclePeriod

    private var currentInterleavingNodeIndex = 0

    private var executionsPerformedInCurrentThread = 0

    /**
     * A set of thread, executed at least once during this interleaving.
     *
     * We have to maintain this set to determine how to initialize
     * [executionsPerformedInCurrentThread] after thread switch.
     * When a thread is executed for the first time, [newSwitchPoint]
     * strategy method is called before the first switch point,
     * so number of executions in this thread should start with zero,
     * and it will be incremented after [onNextExecution] call.
     *
     * But when we return to a thread which has already executed its operations, [newSwitchPoint]
     * strategy method won't be called,
     * as we already considered this switch point before we switched from this thread earlier,
     * [onNextExecution] won't be called before the first execution,
     * so we have to start [executionsPerformedInCurrentThread] from 1.
     */
    private val threadsRan = hashSetOf<Int>()

    private val currentHistoryNode: InterleavingHistoryNode get() = interleavingHistory[currentInterleavingNodeIndex]

    /**
     * Returns if we ran in the spin cycle and now are performing executions inside it.
     */
    val currentlyInSpinCycle: Boolean
        get() = currentHistoryNode.cycleOccurred && currentHistoryNode.executions <= executionsPerformedInCurrentThread

    fun initialize() {
        currentInterleavingNodeIndex = 0
        executionsPerformedInCurrentThread = 0
        threadsRan.clear()
    }

    /**
     * Called before next execution in current thread.
     */
    fun onNextExecution() {
        executionsPerformedInCurrentThread++
    }

    /**
     * Called before next thread switch
     */
    fun onNextSwitch() {
        currentInterleavingNodeIndex++
        // See threadsRan field description to understand the following initialization logic
        executionsPerformedInCurrentThread = 0
    }

    /**
     * Called to determine if we should switch.
     *
     * @return true if the switch is required, false otherwise.
     */
    fun shouldSwitch(): Boolean {
        require(currentInterleavingNodeIndex <= interleavingHistory.lastIndex) {
            "Internal error"
        }
        val historyNode = interleavingHistory[currentInterleavingNodeIndex]
        return (executionsPerformedInCurrentThread > historyNode.spinCyclePeriod + historyNode.executions)
    }

    fun detectLivelock(): LoopDetector.Decision {
        val cyclePeriod = interleavingHistory[currentInterleavingNodeIndex].spinCyclePeriod
        if (currentInterleavingNodeIndex == interleavingHistory.lastIndex && failDueToDeadlockInTheEnd) {
            // Fail if we ran into cycle,
            // this cycle node is the last node in the replayed interleaving,
            // and we have to fail at the end of the execution
            // traceCollector.newActiveLockDetected(currentThread, cyclePeriod)
            return LoopDetector.Decision.LivelockFailureDetected(cyclePeriod)
        }
        return if (cyclePeriod != 0)
            LoopDetector.Decision.LivelockThreadSwitch(cyclePeriod)
        else
            LoopDetector.Decision.Idle
    }
}
