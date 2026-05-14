/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.toFormattedString
import org.jetbrains.lincheck.jvm.agent.transformers.computeAwaitPathBackEdgeSources
import org.junit.Test

/**
 * Tests for await path detection logic.
 *
 * An await path is a path from a loop header to a back edge source that:
 *   - contains at least one shared read (field/array read)
 *   - contains no shared writes, monitor operations, or side-effecting calls
 *     (except Thread.onSpinWait)
 *
 * Each test compiles a Java method from [AwaitLoopCases.java.txt],
 * builds the CFG, computes loop information and await path back-edge sources,
 * and compares the textual result against a golden file.
 */
class AwaitLoopDetectionTests {
    private val tester = JavaControlFlowGraphTester()

    private val javaPath = "analysis/controlflow/AwaitLoopCases.java.txt"
    private val className = "AwaitLoopCases"

    private fun golden(name: String) = "analysis/controlflow/golden/await_loops/$name.txt"

    /**
     * Builds a formatted string containing both loop info and await path detection results.
     */
    private fun test(name: String, desc: String) =
        tester.testMethodCfg(
            javaPath, golden(name),
            className, name, desc
        ) { cfg ->
            val loopInfo = cfg.computeLoopInformation()
            val awaitPathBackEdgeSources = cfg.computeAwaitPathBackEdgeSources(loopInfo)
            buildString {
                appendLine("=== Loop Information ===")
                appendLine(loopInfo.toFormattedString())
                appendLine()
                appendLine("=== Await Path Detection ===")
                if (awaitPathBackEdgeSources.isEmpty()) {
                    appendLine("No await paths detected.")
                } else {
                    for (loopId in awaitPathBackEdgeSources.keys.sorted()) {
                        val loop = loopInfo.getLoopInfo(loopId)!!
                        val awaitPathBackEdgeSourcesForLoop = awaitPathBackEdgeSources[loopId]!!.sorted()
                        appendLine(
                            "Await loop ${loopId + 1}: "
                                    + "header=${loop.header.toBlockName()}, "
                                    + "body=${loop.body.sorted().toBlockNames()}, "
                                    + "cleanBackEdgeSources=${awaitPathBackEdgeSourcesForLoop.toBlockNames()}"
                        )
                    }
                }
            }.trimEnd()
        }

    private fun Int.toBlockName(): String = "B$this"

    private fun Iterable<Int>.toBlockNames(): String =
        joinToString(prefix = "[", postfix = "]") { it.toBlockName() }

    // Case 1: Pure await path: only reads a volatile field
    @Test
    fun awaitFieldRead() = test("awaitFieldRead", "()V")

    // Case 2: No await path: contains a field write
    @Test
    fun nonAwaitFieldWrite() = test("nonAwaitFieldWrite", "()V")

    // Case 3: No await path: contains a method call
    @Test
    fun nonAwaitWithMethodCall() = test("nonAwaitWithMethodCall", "()V")

    // Case 4: No await path: contains monitor enter/exit
    @Test
    fun nonAwaitWithMonitor() = test("nonAwaitWithMonitor", "()V")

    // Case 5: No await path: writes to the local used by the loop header
    @Test
    fun awaitArrayRead() = test("awaitArrayRead", "()I")

    // Case 5b: No await path: writes to the local used by the loop header and changes the array index
    @Test
    fun awaitArrayReadChangingIndex() = test("awaitArrayReadChangingIndex", "()I")

    // Case 6: No await path: writes to an array
    @Test
    fun nonAwaitArrayWrite() = test("nonAwaitArrayWrite", "()V")

    // Case 7: No loops at all
    @Test
    fun noLoop() = test("noLoop", "()I")

    // Case 8: Two loops: first has an await path (reads only), second does not (has write)
    @Test
    fun mixedLoops() = test("mixedLoops", "()V")

    // Case 9: Await path with both field and array reads
    @Test
    fun awaitMixedReads() = test("awaitMixedReads", "()V")

    // Case 10: No await path: reads AND writes (counter++)
    @Test
    fun nonAwaitReadAndWrite() = test("nonAwaitReadAndWrite", "()V")

    // === Path-sensitive test cases ===

    // Case 11: Loop with CAS on one path, read-only spin on another -> await path
    @Test
    fun awaitLoopWithCasOnAlternatePath() = test("awaitLoopWithCasOnAlternatePath", "()I")

    // Case 12: Loop with write on exit path, read-only spin on continue path -> await path
    @Test
    fun awaitLoopWithWriteOnAlternatePath() = test("awaitLoopWithWriteOnAlternatePath", "()V")

    // Case 13: All paths have side effects -> no await path
    @Test
    fun nonAwaitAllPathsDirty() = test("nonAwaitAllPathsDirty", "()V")

    // Case 14: Method call on the spin path -> no await path
    @Test
    fun nonAwaitCallOnSpinPath() = test("nonAwaitCallOnSpinPath", "()V")

    // Case 15: Thread.onSpinWait on spin path (allowed) -> await path
    @Test
    fun awaitLoopWithOnSpinWait() = test("awaitLoopWithOnSpinWait", "()V")

    // Case 16: Two spin paths, one await path, one dirty path -> await path
    @Test
    fun awaitLoopOneCleanOneNot() = test("awaitLoopOneCleanOneNot", "()V")

    // Case 17: Nested loops with await paths in both loops
    @Test
    fun nestedAwaitPaths() = test("nestedAwaitPaths", "()V")

    // Case 18: Nested loop with an inner-loop break
    @Test
    fun nestedLoopWithInnerBreak() = test("nestedLoopWithInnerBreak", "()V")

    // Case 19: Nested loop with an inner-loop continue
    @Test
    fun nestedLoopWithInnerContinue() = test("nestedLoopWithInnerContinue", "()V")

    // Case 20: Nested loop with a continue from the inner loop to the outer loop
    @Test
    fun nestedLoopWithContinueOuter() = test("nestedLoopWithContinueOuter", "()V")

    // Case 21: Nested loop with a break from the inner loop out of the outer loop
    @Test
    fun nestedLoopWithBreakOuter() = test("nestedLoopWithBreakOuter", "()V")
}
