/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.impl

import AbstractGradleTraceIntegrationTest
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class TraceDebuggerExamplesTraceRecorderIntegrationTest : AbstractGradleTraceIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "TraceDebuggerExamples").toString()

    @Test
    fun simpleProgramNonFailingTest() {
        runTest(
            testClassName = "org.examples.hackathon.SimpleProgramNonFailingTest",
            testMethodName = "test",
            commands = listOf(":test"),
        )
    }

    @Test
    fun koverAddTest() {
        runTest(
            testClassName = "org.examples.kover.CalculatorTest",
            testMethodName = "addTest",
            commands = listOf(":test-kover:test"),
        )
    }

    @Test
    fun koverIsEvenTest() {
        runTest(
            testClassName = "org.examples.kover.CalculatorTest",
            testMethodName = "isEvenTest",
            commands = listOf(":test-kover:test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_LinkedHashSetTest putAnObjectWithoutDefinedHashCode`() {
        runTest(
            testClassName = "org.examples.integration.bugs.LinkedHashSetTest",
            testMethodName = "putAnObjectWithoutDefinedHashCode",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ElementRefTest accessLocalVariableFromLambda`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ElementRefTest",
            testMethodName = "accessLocalVariableFromLambda",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ThreadsTest daemonThreadTest`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ThreadsTest",
            testMethodName = "daemonThreadTest",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInline`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInline",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testPrintln`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testPrintln",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testRepeat`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testRepeat",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInlineLambda`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInlineLambda",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testDefaultArg`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testDefaultArg",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInlineClass`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInlineClass",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInner`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInner",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInlineClassInlineFunction`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInlineClassInlineFunction",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInlineClassInlineFunctionWithDefault`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInlineClassInlineFunctionWithDefault",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testCapture`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testCapture",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testReceivers`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testReceivers",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInternalViaAccessor`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInternalViaAccessor",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testNestedInlineFunction`() {
        runTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testNestedInlineFunction",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_JavaForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.JavaForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_JavaWhileLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.JavaWhileLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_JavaDoLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.JavaDoLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_BreakedForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.BreakedForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ComplexNestedLoopsRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ComplexNestedLoopsRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ContinuedForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ContinuedForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_EmptyForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.EmptyForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ForLoopWithMethodCallRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ForLoopWithMethodCallRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ForLoopWithTryCatchFinallyRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ForLoopWithTryCatchFinallyRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ForLoopWithTryCatchRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ForLoopWithTryCatchRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ForWithIfLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ForWithIfLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_LoopWithNestedLoopMethodCallRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.LoopWithNestedLoopMethodCallRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_NestedForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.NestedForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_NestedLoopWithExceptionRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.NestedLoopWithExceptionRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_OneIterForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.OneIterForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_PartiallyEmptyForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.PartiallyEmptyForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_RepeatedForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.RepeatedForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_SimpleDoWhileLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.SimpleDoWhileLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_SimpleForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.SimpleForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_SimpleWhileLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.SimpleWhileLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_TwoForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.TwoForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_WhileWithIfLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.WhileWithIfLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ZeroIterForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ZeroIterForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_BreakedNestedRepeatLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.BreakedNestedRepeatLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_BreakedOuterForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.BreakedOuterForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ContinuedNestedRepeatLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ContinuedNestedRepeatLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ContinuedOuterForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ContinuedOuterForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_NestedRepeatLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.NestedRepeatLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_RepeatLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.RepeatLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ListBreakedForAndMapLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ListBreakedForAndMapLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ListBreakedForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ListBreakedForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ListContinuedForAndMapLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ListContinuedForAndMapLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ListContinuedForLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ListContinuedForLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ListForEachBreakedLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ListForEachBreakedLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ListForEachContinuedLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.ListForEachContinuedLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_SequenceBreakedForAndMapLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.SequenceBreakedForAndMapLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_SequenceContinuedForAndMapLoopRepresentationTest operation`() {
        runTest(
            testClassName = "org.examples.integration.loops.SequenceContinuedForAndMapLoopRepresentationTest",
            testMethodName = "operation",
            commands = listOf(":test"),
        )
    }
}
