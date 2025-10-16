/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.prettyPrint
import org.junit.Test

class JavaControlFlowGraphLoopsTests {
    private val tester = JavaControlFlowGraphTester()

    private val javaPath = "analysis/controlflow/JavaControlFlowGraphCases.java.txt"
    private val className = "JavaControlFlowGraphCases"

    private fun golden(name: String) = "analysis/controlflow/golden/loops/$name.txt"

    private fun test(name: String, desc: String) =
        tester.testMethodCfg(
            javaPath, golden(name),
            className, name, desc
        ) { cfg ->
            cfg.computeLoopInformation()
            cfg.loopInfo!!.prettyPrint()
        }

    @Test
    fun straightLine() = test("straightLine", "()I") // will contain no loops

    @Test
    fun whileLoop() = test("whileLoop", "(I)I")

    @Test
    fun whileLoopContinue() = test("whileLoopContinue", "(I)I")

    @Test
    fun whileLoopBreak() = test("whileLoopBreak", "(I)I")

    @Test
    fun whileTrueLoop() = test("whileTrueLoop", "()V")

    @Test
    fun whileTrueLoopReturn() = test("whileTrueLoopReturn", "(I)I")

    @Test
    fun whileTrueLoopBreak() = test("whileTrueLoopBreak", "(I)I")

    @Test
    fun doWhileLoop() = test("doWhileLoop", "(I)I")

    @Test
    fun doWhileLoopContinueBreak() = test("doWhileLoopContinueBreak", "(I)I")

    @Test
    fun doWhileTrueLoop() = test("doWhileTrueLoop", "()V")

    @Test
    fun forLoop() = test("forLoop", "(I)I")

    @Test
    fun forLoopContinueBreak() = test("forLoopContinueBreak", "(I)I")

    @Test
    fun forLoopNested() = test("forLoopNested", "(II)I")

    @Test
    fun forLoopContinueBreakNested() = test("forLoopContinueBreakNested", "(II)I")

    @Test
    fun whileWithThrow() = test("whileWithThrow", "(I)I")

    @Test
    fun whileWithTryCatchInside() = test("whileWithTryCatchInside", "(I)I")

    @Test
    fun whileWithTryCatchOutside() = test("whileWithTryCatchOutside", "(I)I")

    @Test
    fun nestedWhileWithTryCatchBetween() = test("nestedWhileWithTryCatchBetween", "(I)I")

    @Test
    fun nestedWhileWithTryCatchOutside() = test("nestedWhileWithTryCatchOutside", "(I)I")
}