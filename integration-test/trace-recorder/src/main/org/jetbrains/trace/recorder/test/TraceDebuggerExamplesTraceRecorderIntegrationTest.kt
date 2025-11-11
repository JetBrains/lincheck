/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test

import org.junit.Test
import java.nio.file.Paths

class TraceDebuggerExamplesTraceRecorderIntegrationTest : AbstractTraceRecorderIntegrationTest() {
    override val projectPath: String = Paths.get("build", "integrationTestProjects", "TraceDebuggerExamples").toString()

    @Test
    fun simpleProgramNonFailingTest() {
        runGradleTest(
            testClassName = "org.examples.hackathon.SimpleProgramNonFailingTest",
            testMethodName = "test",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_LinkedHashSetTest putAnObjectWithoutDefinedHashCode`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.LinkedHashSetTest",
            testMethodName = "putAnObjectWithoutDefinedHashCode",
            gradleCommands = listOf(":test"),
        )
    }

    @Test // actually runs the same test as above, but via the `runGradleTests` call
    fun `org_examples_integration_bugs_LinkedHashSetTest`() {
        runGradleTests(
            testClassNamePrefix = "org.examples.integration.bugs.LinkedHashSetTest",
            gradleBuildCommands = listOf(":compileTestKotlin"),
            gradleTestCommands = listOf(":test"),
            checkRepresentation = false,
        )
    }

    @Test
    fun `org_examples_integration_bugs_ElementRefTest accessLocalVariableFromLambda`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ElementRefTest",
            testMethodName = "accessLocalVariableFromLambda",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ThreadsTest daemonThreadTest`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ThreadsTest",
            testMethodName = "daemonThreadTest",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInline`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInline",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testPrintln`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testPrintln",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testRepeat`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testRepeat",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInlineLambda`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInlineLambda",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testDefaultArg`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testDefaultArg",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInlineClass`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInlineClass",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInner`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInner",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInlineClassInlineFunction`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInlineClassInlineFunction",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInlineClassInlineFunctionWithDefault`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInlineClassInlineFunctionWithDefault",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testCapture`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testCapture",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testReceivers`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testReceivers",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testInternalViaAccessor`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testInternalViaAccessor",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_bugs_ManglingTest testNestedInlineFunction`() {
        runGradleTest(
            testClassName = "org.examples.integration.bugs.ManglingTest",
            testMethodName = "testNestedInlineFunction",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_JavaForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.JavaForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_JavaWhileLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.JavaWhileLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_JavaDoLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.JavaDoLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_BreakedForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.BreakedForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ComplexNestedLoopsRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ComplexNestedLoopsRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ContinuedForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ContinuedForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_EmptyForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.EmptyForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ForLoopWithMethodCallRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ForLoopWithMethodCallRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ForLoopWithTryCatchFinallyRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ForLoopWithTryCatchFinallyRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ForLoopWithTryCatchRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ForLoopWithTryCatchRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ForWithIfLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ForWithIfLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_LoopWithNestedLoopMethodCallRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.LoopWithNestedLoopMethodCallRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_NestedForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.NestedForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_NestedLoopWithExceptionRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.NestedLoopWithExceptionRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_OneIterForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.OneIterForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_PartiallyEmptyForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.PartiallyEmptyForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_RepeatedForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.RepeatedForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_SimpleDoWhileLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.SimpleDoWhileLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_SimpleForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.SimpleForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_SimpleWhileLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.SimpleWhileLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_TwoForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.TwoForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_WhileWithIfLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.WhileWithIfLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ZeroIterForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ZeroIterForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_BreakedNestedRepeatLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.BreakedNestedRepeatLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_BreakedOuterForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.BreakedOuterForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ContinuedNestedRepeatLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ContinuedNestedRepeatLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ContinuedOuterForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ContinuedOuterForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_NestedRepeatLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.NestedRepeatLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_RepeatLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.RepeatLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ListBreakedForAndMapLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ListBreakedForAndMapLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ListBreakedForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ListBreakedForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ListContinuedForAndMapLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ListContinuedForAndMapLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ListContinuedForLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ListContinuedForLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ListForEachBreakedLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ListForEachBreakedLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_ListForEachContinuedLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.ListForEachContinuedLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_SequenceBreakedForAndMapLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.SequenceBreakedForAndMapLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }

    @Test
    fun `org_examples_integration_loops_SequenceContinuedForAndMapLoopRepresentationTest operation`() {
        runGradleTest(
            testClassName = "org.examples.integration.loops.SequenceContinuedForAndMapLoopRepresentationTest",
            testMethodName = "operation",
            gradleCommands = listOf(":test"),
        )
    }
}
