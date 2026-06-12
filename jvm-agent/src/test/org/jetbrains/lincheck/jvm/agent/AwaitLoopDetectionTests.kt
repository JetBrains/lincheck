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
import org.jetbrains.lincheck.util.isJdk8
import org.junit.Assume.assumeFalse
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

    // Cases that call `Thread.onSpinWait()` (available since JDK 9) live in a
    // separate source file, so the rest of the cases can still be compiled on JDK 8.
    private val onSpinWaitJavaPath = "analysis/controlflow/AwaitLoopCasesOnSpinWait.java.txt"
    private val onSpinWaitClassName = "AwaitLoopCasesOnSpinWait"

    private fun golden(name: String) = "analysis/controlflow/golden/await_loops/$name.txt"

    /**
     * Builds a formatted string containing both loop info and await path detection results.
     */
    private fun test(name: String, desc: String) =
        test(javaPath, className, name, desc)

    /**
     * Variant for cases that call `Thread.onSpinWait()`: muted on JDK 8, where the method is unavailable.
     */
    private fun testOnSpinWait(name: String, desc: String) {
        assumeFalse("Thread.onSpinWait() is unavailable on JDK 8", isJdk8)
        test(onSpinWaitJavaPath, onSpinWaitClassName, name, desc)
    }

    private fun test(javaPath: String, className: String, name: String, desc: String) =
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

    // Pure await path: only reads a volatile field
    @Test
    fun awaitFieldRead() = test("awaitFieldRead", "()V")

    // No await path: contains a field write
    @Test
    fun nonAwaitFieldWrite() = test("nonAwaitFieldWrite", "()V")

    // No await path: contains a method call
    @Test
    fun nonAwaitWithMethodCall() = test("nonAwaitWithMethodCall", "()V")

    // No await path: contains monitor enter/exit
    @Test
    fun nonAwaitWithMonitor() = test("nonAwaitWithMonitor", "()V")

    // No await path: writes to the local used by the loop header
    @Test
    fun awaitArrayRead() = test("awaitArrayRead", "()I")

    // No await path: writes to the local used by the loop header and changes the array index
    @Test
    fun awaitArrayReadChangingIndex() = test("awaitArrayReadChangingIndex", "()I")

    // No await path: writes to an array
    @Test
    fun nonAwaitArrayWrite() = test("nonAwaitArrayWrite", "()V")

    // No loops at all
    @Test
    fun noLoop() = test("noLoop", "()I")

    // Two loops: first has an await path (reads only), second does not (has write)
    @Test
    fun mixedLoops() = test("mixedLoops", "()V")

    // Await path with both field and array reads
    @Test
    fun awaitMixedReads() = test("awaitMixedReads", "()V")

    // No await path: reads AND writes (counter++)
    @Test
    fun nonAwaitReadAndWrite() = test("nonAwaitReadAndWrite", "()V")

    // === Path-sensitive test cases ===

    // Loop with CAS on one path, read-only spin on another -> await path
    @Test
    fun awaitLoopWithCasOnAlternatePath() = test("awaitLoopWithCasOnAlternatePath", "()I")

    // Loop with write on exit path, read-only spin on continue path -> await path
    @Test
    fun awaitLoopWithWriteOnAlternatePath() = test("awaitLoopWithWriteOnAlternatePath", "()V")

    // All paths have side effects -> no await path
    @Test
    fun nonAwaitAllPathsDirty() = test("nonAwaitAllPathsDirty", "()V")

    // Method call on the spin path -> no await path
    @Test
    fun nonAwaitCallOnSpinPath() = test("nonAwaitCallOnSpinPath", "()V")

    // Thread.onSpinWait on spin path (allowed) -> await path
    @Test
    fun awaitLoopWithOnSpinWait() = testOnSpinWait("awaitLoopWithOnSpinWait", "()V")

    // Two spin paths, one await path, one dirty path -> await path
    @Test
    fun awaitLoopOneCleanOneNot() = test("awaitLoopOneCleanOneNot", "()V")

    // Same continue back-edge reached by clean and dirty paths -> no await path
    @Test
    fun nonAwaitContinueBackEdgeWithDirtyPath() = testOnSpinWait("nonAwaitContinueBackEdgeWithDirtyPath", "()V")

    // Nested loops with await paths in both loops
    @Test
    fun nestedAwaitPaths() = testOnSpinWait("nestedAwaitPaths", "()V")

    // Nested loop with an inner-loop break
    @Test
    fun nestedLoopWithInnerBreak() = testOnSpinWait("nestedLoopWithInnerBreak", "()V")

    // Nested loop with an inner-loop continue
    @Test
    fun nestedLoopWithInnerContinue() = testOnSpinWait("nestedLoopWithInnerContinue", "()V")

    // Nested loop with a continue from the inner loop to the outer loop
    @Test
    fun nestedLoopWithContinueOuter() = testOnSpinWait("nestedLoopWithContinueOuter", "()V")

    // Nested loop with a break from the inner loop out of the outer loop
    @Test
    fun nestedLoopWithBreakOuter() = testOnSpinWait("nestedLoopWithBreakOuter", "()V")

    // Nested loop where the outer loop has a side effect after the inner loop
    @Test
    fun nestedLoopWithOuterSideEffectAfterInnerLoop() = test("nestedLoopWithOuterSideEffectAfterInnerLoop", "()V")

    // Nested loop where the outer loop has a side effect before the inner loop
    @Test
    fun nestedLoopWithOuterSideEffectBeforeInnerLoop() = test("nestedLoopWithOuterSideEffectBeforeInnerLoop", "()V")

    // Nested loop where the inner loop has a side effect
    @Test
    fun nestedLoopWithInnerSideEffect() = test("nestedLoopWithInnerSideEffect", "()V")

    // Await path before a side-effecting inner loop
    @Test
    fun nestedLoopWithAwaitPathBeforeSideEffectingInnerLoop() =
        test("nestedLoopWithAwaitPathBeforeSideEffectingInnerLoop", "()V")

    // Three nested loops inherit an outer side effect
    @Test
    fun nestedLoopsWithOuterSideEffectBeforeTwoCleanInnerLoops() =
        test("nestedLoopsWithOuterSideEffectBeforeTwoCleanInnerLoops", "()V")

    // AtomicLongArray.get is a side-effect-free shared read
    @Test
    fun awaitAtomicLongArrayGet() = testOnSpinWait("awaitAtomicLongArrayGet", "()V")
}
