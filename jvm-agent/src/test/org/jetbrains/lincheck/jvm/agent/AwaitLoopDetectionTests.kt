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
import org.jetbrains.lincheck.jvm.agent.transformers.computeAwaitLoops
import org.junit.Test

/**
 * Tests for await-loop detection logic.
 *
 * An await loop is a loop that:
 *   - contains at least one shared read (field/array read)
 *   - contains NO shared writes, monitor operations, or side-effecting calls
 *     (except Thread.onSpinWait)
 *
 * Each test compiles a Java method from [AwaitLoopCases.java.txt],
 * builds the CFG, computes loop information & await loops,
 * and compares the textual result against a golden file.
 */
class AwaitLoopDetectionTests {
    private val tester = JavaControlFlowGraphTester()

    private val javaPath = "analysis/controlflow/AwaitLoopCases.java.txt"
    private val className = "AwaitLoopCases"

    private fun golden(name: String) = "analysis/controlflow/golden/await_loops/$name.txt"

    /**
     * Builds a formatted string containing both loop info and await-loop detection results.
     */
    private fun test(name: String, desc: String) =
        tester.testMethodCfg(
            javaPath, golden(name),
            className, name, desc
        ) { cfg ->
            val loopInfo = cfg.computeLoopInformation()
            val awaitLoopIds = cfg.computeAwaitLoops(loopInfo)
            buildString {
                appendLine("=== Loop Information ===")
                appendLine(loopInfo.toFormattedString())
                appendLine()
                appendLine("=== Await Loop Detection ===")
                if (awaitLoopIds.isEmpty()) {
                    appendLine("No await loops detected.")
                } else {
                    for (loopId in awaitLoopIds.sorted()) {
                        val loop = loopInfo.getLoopInfo(loopId)!!
                        appendLine("Await loop $loopId: header=${loop.header}, body=${loop.body}")
                    }
                }
            }.trimEnd()
        }

    // Case 1: Pure await loop — only reads a volatile field
    @Test
    fun awaitFieldRead() = test("awaitFieldRead", "()V")

    // Case 2: Non-await loop — contains a field write
    @Test
    fun nonAwaitFieldWrite() = test("nonAwaitFieldWrite", "()V")

    // Case 3: Non-await loop — contains a method call
    @Test
    fun nonAwaitWithMethodCall() = test("nonAwaitWithMethodCall", "()V")

    // Case 4: Non-await loop — contains monitor enter/exit
    @Test
    fun nonAwaitWithMonitor() = test("nonAwaitWithMonitor", "()V")

    // Case 5: Await loop — only reads from an array
    @Test
    fun awaitArrayRead() = test("awaitArrayRead", "()I")

    // Case 6: Non-await loop — writes to an array
    @Test
    fun nonAwaitArrayWrite() = test("nonAwaitArrayWrite", "()V")

    // Case 7: No loops at all
    @Test
    fun noLoop() = test("noLoop", "()I")

    // Case 8: Two loops — first is await (reads only), second is not (has write)
    @Test
    fun mixedLoops() = test("mixedLoops", "()V")

    // Case 9: Await loop with both field and array reads
    @Test
    fun awaitMixedReads() = test("awaitMixedReads", "()V")

    // Case 10: Non-await loop — reads AND writes (counter++)
    @Test
    fun nonAwaitReadAndWrite() = test("nonAwaitReadAndWrite", "()V")
}
