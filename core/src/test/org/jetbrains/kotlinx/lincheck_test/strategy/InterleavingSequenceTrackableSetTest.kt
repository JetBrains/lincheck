/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("SameParameterValue")

package org.jetbrains.kotlinx.lincheck_test.strategy

import org.jetbrains.kotlinx.lincheck.strategy.managed.InterleavingSequenceTrackableSet
import org.jetbrains.kotlinx.lincheck.strategy.managed.InterleavingHistoryNode
import org.jetbrains.kotlinx.lincheck.strategy.managed.findMaxPrefixLengthWithNoCycleOnSuffix
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InterleavingSequenceTrackableSetTest {

    private var set = InterleavingSequenceTrackableSet()
    private var cursor = set.cursor

    @Before
    fun setup() {
        set = InterleavingSequenceTrackableSet()
        cursor = set.cursor
    }

    @Test
    fun `should add branch to leaf`() {
        addBranch(
            listOf(
                node(0, 5),
                node(1, 1, 1)
            )
        )
        addBranch(
            listOf(
                node(0, 5),
                node(1, 1, 1),
                node(2, 1, 3)
            )
        )
        walkChainFromStart(
            steps(0, 5),
            steps(1, 2, true)
        )
        walkChainFromStart(
            steps(0, 5),
            steps(1, 2, true),
            steps(2, 4, true)
        )
    }

    @Test
    fun `should split leaf node`() {
        addBranch(
            listOf(
                node(1, 3),
                node(0, 3, 1)
            )
        )
        addBranch(
            listOf(
                node(1, 3),
                node(0, 2),
                node(1, 3, 1)
            )
        )

        walkChainFromStart(
            steps(1, 3),
            steps(0, 4, true)
        )
        walkChainFromStart(
            steps(1, 3),
            steps(0, 2),
            steps(1, 4, true)
        )
    }

    @Test
    fun `should merge branches`() {
        addBranch(
            listOf(
                node(1, 47),
                node(0, 0, 1)
            )
        )
        addBranch(
            listOf(
                node(1, 2),
                node(0, 0, 2),
            )
        )
        addBranch(
            listOf(
                node(1, 31),
                node(0, 0, 3),
            )
        )

        walkChainFromStart(
            steps(1, 47),
            steps(0, 1, true)
        )
        walkChainFromStart(
            steps(1, 2),
            steps(0, 2, true)
        )
        walkChainFromStart(
            steps(1, 31),
            steps(0, 3, true)
        )
    }

    @Test
    fun `should add new transition to existing node`() {
        addBranch(
            listOf(
                node(1, 4),
                node(2, 3),
                node(3, 4),
                node(1, 0, 1),
            )
        )
        addBranch(
            listOf(
                node(1, 4),
                node(2, 3),
                node(3, 4),
                node(4, 3),
                node(2, 0, 4),
            )
        )


        walkChainFromStart(
            steps(1, 4),
            steps(2, 3),
            steps(3, 4),
            steps(1, 1, true)
        )

        walkChainFromStart(
            steps(1, 4),
            steps(2, 3),
            steps(3, 4),
            steps(4, 3),
            steps(2, 4, true),
        )
    }

    @Test
    fun `should split node and add new transition`() {
        addBranch(
            listOf(
                node(1, 4),
                node(2, 3),
                node(3, 4),
                node(1, 0, 1),
            )
        )
        addBranch(
            listOf(
                node(1, 4),
                node(2, 3),
                node(3, 2),
                node(4, 3),
                node(2, 0, 1),
            )
        )


        walkChainFromStart(
            steps(1, 4),
            steps(2, 3),
            steps(3, 4),
            steps(1, 1, true)
        )

        walkChainFromStart(
            steps(1, 4),
            steps(2, 3),
            steps(3, 2),
            steps(4, 3),
            steps(2, 1, true),
        )
    }

    @Test
    fun `should continue cycle branch`() {
        addBranch(
            listOf(
                node(1, 4),
                node(2, 3),
                node(3, 2),
                node(1, 0, 1),
            )
        )
        addBranch(
            listOf(
                node(1, 4),
                node(2, 3),
                node(3, 2),
                node(1, 1),
                node(3, 3),
                node(2, 0, 1),
            )
        )


        walkChainFromStart(
            steps(1, 4),
            steps(2, 3),
            steps(3, 2),
            steps(1, 1, true)
        )

        walkChainFromStart(
            steps(1, 4),
            steps(2, 3),
            steps(3, 2),
            steps(1, 1, true),
            steps(3, 3),
            steps(2, 1, true),
        )
    }

    @Test
    fun `should find cycle after one add`() {
        addBranch(
            listOf(
                node(1, 4),
                node(2, 3),
                node(3, 2),
                node(1, 5, 1),
            )
        )
        resetCursor(1)


        walkChainFromStart(
            steps(1, 4),
            steps(2, 3),
            steps(3, 2),
            steps(1, 6, true)
        )

        repeat(10) { onNewExecutionAndAssertCycle(true) }
    }


    @Test
    fun `should not find cycle`() {
        set.addBranch(
            listOf(
                node(1, 4),
                node(2, 3),
                node(3, 2),
                node(1, 0, 8),
            )
        )

        cursor.reset(4)

        repeat(4) { onNewExecutionAndAssertCycle(false) }
        onNewThreadSwitchAndAssertCycle(2, false)

        repeat(3) { onNewExecutionAndAssertCycle(false) }
        onNewThreadSwitchAndAssertCycle(3, false)

        repeat(2) { onNewExecutionAndAssertCycle(false) }
        onNewThreadSwitchAndAssertCycle(1, false)

        repeat(10) { onNewExecutionAndAssertCycle(false) }
    }

    @Test
    fun `should reset cursor to cycle leaf after cycle added`() {
        set.addBranch(
            listOf(
                node(1, 4),
                node(2, 3),
                node(3, 2),
                node(1, 0, 3),
            )
        )

        assertTrue(cursor.isInCycle)
    }

    private fun onNewThreadSwitchAndAssertCycle(
        nextThreadId: Int,
        cyclePresent: Boolean
    ) {
        cursor.onNextSwitchPoint(nextThreadId)
        assertEquals(cyclePresent, cursor.isInCycle)
    }

    private fun resetCursor(threadId: Int) {
        cursor.reset(threadId)
    }

    private data class ThreadToOperations(
        val threadId: Int,
        val operations: Int,
        val cycle: Boolean
    )

    private fun steps(threadId: Int, operations: Int, cycle: Boolean = false) =
        ThreadToOperations(threadId, operations, cycle)

    private fun walkChainFromStart(vararg steps: ThreadToOperations) {
        var lastThreadId: Int
        steps.first().let { firstStep ->
            cursor.reset(firstStep.threadId)

            repeat(firstStep.operations - 1) { onNewExecutionAndAssertCycle(false) }
            if (firstStep.operations > 0) {
                onNewExecutionAndAssertCycle(firstStep.cycle)
            } else {
                assertEquals(firstStep.cycle, cursor.isInCycle)
            }
            lastThreadId = firstStep.threadId
        }

        steps.drop(1).forEach { step ->
            val threadId = step.threadId
            if (threadId != lastThreadId) {
                cursor.onNextSwitchPoint(threadId)
                lastThreadId = threadId
            }
            repeat(step.operations - 1) { onNewExecutionAndAssertCycle(false) }
            if (step.operations > 0) {
                onNewExecutionAndAssertCycle(step.cycle)
            } else {
                assertEquals(step.cycle, cursor.isInCycle)
            }
        }
    }

    private fun onNewExecutionAndAssertCycle(cyclePresent: Boolean) {
        cursor.onNextExecutionPoint()
        assertEquals(cyclePresent, cursor.isInCycle)
    }

    private fun node(threadId: Int, count: Int, spinCyclePeriod: Int = 0) =
        InterleavingHistoryNode(threadId = threadId, executions = count, spinCyclePeriod = spinCyclePeriod)

    private fun addBranch(branch: List<InterleavingHistoryNode>) {
        set.addBranch(branch)
        println()
    }

}

class CycleDetectionTests {

    @Test
    fun `should select the longest cycle when all elements are the same`() = assertPrefixAndFistCycleOccurrence(
        elements = listOf(1, 1, 1, 1, 1, 1, 1),
        expected = listOf(1)
    )

    @Test
    fun `should find the longest cycle from the start`() = assertPrefixAndFistCycleOccurrence(
        elements = listOf(1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 2, 3),
        expected = listOf(1, 2, 3)
    )

    @Test
    fun `should find the longest cycle when many of them present (even count from the end)`() =
        assertPrefixAndFistCycleOccurrence(
            elements = listOf(1, 2, 3, 4, 5, 3, 4, 5, 3, 4, 5, 3, 4, 5),
            expected = listOf(1, 2, 3, 4, 5)
        )

    @Test
    fun `should find the longest cycle when many of them present (odd count from the end)`() =
        assertPrefixAndFistCycleOccurrence(
            elements = listOf(1, 2, 3, 4, 5, 3, 4, 5, 3, 4, 5, 3, 4, 5, 3, 4, 5),
            expected = listOf(1, 2, 3, 4, 5)
        )

    @Test
    fun `should find medium cycle`() = assertPrefixAndFistCycleOccurrence(
        elements = listOf(1, 2, 3, 4, 5, 3, 4, 5, 3, 4, 5),
        expected = listOf(1, 2, 3, 4, 5)
    )

    @Test
    fun `should find small cycle`() = assertPrefixAndFistCycleOccurrence(
        elements = listOf(1, 2, 3, 4, 5, 4, 5, 4, 5),
        expected = listOf(1, 2, 3, 4, 5)
    )

    @Test
    fun `should find the longest cycle`() = assertPrefixAndFistCycleOccurrence(
        elements = listOf(5, 3, 2, 1, 2, 1, 3, 2, 1, 2, 1),
        expected = listOf(5, 3, 2, 1, 2, 1)
    )

    @Test
    fun `should find the larges cycle when many of them present on suffix`() = assertPrefixAndFistCycleOccurrence(
        elements = listOf(5, 6, 3, 2, 1, 1, 2, 1, 1, 2, 1, 1, 3, 2, 1, 1, 2, 1, 1, 2, 1, 1),
        expected = listOf(5, 6, 3, 2, 1, 1, 2, 1, 1, 2, 1, 1)
    )

    @Test
    fun `should return all collection size when there is no cycle`() {
        val elements = listOf(1, 2, 3, 4, 5)
        assertNull(findMaxPrefixLengthWithNoCycleOnSuffix(elements))
    }

    private fun assertPrefixAndFistCycleOccurrence(elements: List<Int>, expected: List<Int>) {
        val cycleLength = findMaxPrefixLengthWithNoCycleOnSuffix(elements)!!
        val actualCycle = elements.take(cycleLength.cyclePeriod + cycleLength.executionsBeforeCycle)

        assertEquals(expected, actualCycle)
    }
}
