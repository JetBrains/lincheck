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

import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.toFormattedString
import org.junit.Test


class JavaControlFlowGraphTest {
    private val tester = JavaControlFlowGraphTester()

    private val javaPath = "analysis/controlflow/JavaControlFlowGraphCases.java.txt"
    private val className = "JavaControlFlowGraphCases"

    private fun golden(name: String) = "analysis/controlflow/golden/cfg/$name.txt"

    private fun test(name: String, desc: String) =
        tester.testMethodCfg(javaPath, golden(name), className, name, desc) { cfg ->
            cfg.toFormattedString()
        }

    @Test
    fun straightLine() = test("straightLine", "()I")

    @Test
    fun ifStmt() = test("ifStmt", "(I)I")

    @Test
    fun ifElseStmt() = test("ifElseStmt", "(I)I")

    @Test
    fun ifNull() = test("ifNull", "(Ljava/lang/Object;)I")

    @Test
    fun ifRefCompare() = test("ifRefCompare", "(Ljava/lang/Object;Ljava/lang/Object;)I")

    @Test
    fun ifElseNested() = test("ifElseNested", "(II)I")

    @Test
    fun ifReturn() = test("ifReturn", "(I)I")

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
    fun tableSwitch() = test("tableSwitch", "(I)I")

    @Test
    fun lookupSwitch() = test("lookupSwitch", "(I)I")

    @Test
    fun tryCatch() = test("tryCatch", "(I)I")

    @Test
    fun tryMultiCatch() = test("tryMultiCatch", "(I)I")

    @Test
    fun tryCatchBlocks() = test("tryCatchBlocks", "(I)I")

    @Test
    fun tryFinally() = test("tryFinally", "(I)I")

    @Test
    fun tryCatchFinally() = test("tryCatchFinally", "(I)I")

    @Test
    fun throwStmt() = test("throwStmt", "(I)I")

    @Test
    fun throwCatch() = test("throwCatch", "(I)I")

    @Test
    fun implicitThrowCatch() = test("implicitThrowCatch", "(I)I")

    @Test
    fun whileWithConditionOutside() = test("whileWithConditionOutside", "(I)I")

    @Test
    fun whileWithThrow() = test("whileWithThrow", "(I)I")

    @Test
    fun whileWithTryCatchInside() = test("whileWithTryCatchInside", "(I)I")

    @Test
    fun whileWithTryCatchOutside() = test("whileWithTryCatchOutside", "(I)I")

    @Test
    fun whileWithTryCatchOutsideAndExtraThrow() = test("whileWithTryCatchOutsideAndExtraThrow", "(I)I")

    @Test
    fun nestedWhileWithTryCatchBetween() = test("nestedWhileWithTryCatchBetween", "(I)I")

    @Test
    fun nestedWhileWithTryCatchOutside() = test("nestedWhileWithTryCatchOutside", "(I)I")
}
