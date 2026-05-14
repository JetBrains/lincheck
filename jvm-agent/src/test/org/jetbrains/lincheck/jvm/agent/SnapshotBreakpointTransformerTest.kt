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

import org.jetbrains.lincheck.jvm.agent.InstrumentationMode.LIVE_DEBUGGING
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaBranchedSameLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaChainedCallFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaChainedCallShapeFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaForLoopFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaForLoopSingleLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaIfElseMultiLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaIfElseSingleLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaTryCatchFinallyMultiLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaTryCatchFinallySingleLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaWhileLoopMultiLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaWhileLoopSingleLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.KotlinBranchedSameLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.KotlinChainedCallFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.KotlinChainedCallShapeFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.KotlinForLoopFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.KotlinForLoopSingleLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.KotlinIfElseMultiLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.KotlinIfElseSingleLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.KotlinTryCatchFinallyMultiLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.KotlinTryCatchFinallySingleLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.KotlinWhileLoopMultiLineFixture
import org.jetbrains.lincheck.jvm.agent.fixtures.KotlinWhileLoopSingleLineFixture
import org.jetbrains.lincheck.jvm.agent.transformers.SnapshotBreakpointTransformer
import org.jetbrains.lincheck.settings.LiveDebuggerSettings
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.trace.TraceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ASM9
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.tree.ClassNode
import java.util.UUID

/**
 * Tests for [SnapshotBreakpointTransformer].
 *
 * The transformer's two responsibilities — both motivated by JBRes-9242 — are:
 *
 *   1. **Lambda-shadow skip** (JBRes-9242 / JBRes-9243). Snapshot-breakpoint
 *      hooks inside `lambda$...` / `_u24lambda_u24...` synthetic methods are
 *      suppressed when the source line they sit on is also covered by a
 *      non-synthetic method on the same class — the parent's hook already
 *      fires there, so emitting again inside the lambda double-counts.
 *      Driven by [MethodInformation.nonSyntheticMethodLines],
 *      populated per class by [buildClassInformation].
 *
 *   2. **Basic-block same-line dedup**. Within a single basic block —
 *      bounded by jump / switch / exception-handler-target labels — only the
 *      first `LINENUMBER N` directive for any given line emits a hook;
 *      subsequent `LINENUMBER N` directives in the same block are dropped
 *      (chained-call compiler noise). Crossing a basic-block-entry label
 *      resets the per-block "lines already emitted" set, so loop back-edges,
 *      `try/finally` exception handlers, and mutually-exclusive branches with
 *      the same trailing line each fire their own hook — matching JDI's
 *      "a line may have more than one executable location" semantics. The
 *      basic-block-entry classification is sourced from [MethodLabels], which
 *      records jump / switch / catch-handler targets without building a full
 *      control-flow graph; see [MethodLabels.isJumpOrCatchTarget].
 *
 * Tests in this file drive the [LincheckClassVisitor] via [transformWithSnapshotBreakpoints],
 * which feeds it real per-class data assembled by [buildClassInformation] —
 * the same preprocessing path the agent uses at runtime —
 * so `MethodLabels` (basic-block entries) and `nonSyntheticMethodLines` (lambda-shadow coverage)
 * come from actual implmenetation code, not test stubs.
 * Each test pairs a Java/Kotlin fixture compiled to `.class` with an assertion over the resulting bytecode's snapshot
 * hooks; no synthetic ASM bytecode is generated in tests, so changes to compiler output
 * surface as targeted assertion failures rather than as drift from a hand-rolled shape.
 *
 * Adding a new test case is a matter of adding a new fixture class under
 * `src/test/.../fixtures/` and a new `@Test` method that calls [transformAndCollect].
 */
class SnapshotBreakpointTransformerTest {

    // ── JBRes-9242: dedup of duplicate LINENUMBER directives ──────────────────────────────

    @Test
    fun `Java -- chained-call breakpoint emits exactly one hook in findOrThrow and zero in the lambda body`() {
        // JBRes-9242 target with a lambda: a single user-installed breakpoint on the chained
        // `.orElseThrow(...)` line must yield exactly one snapshot hook for `findOrThrow`,
        // regardless of the multiple `LINENUMBER 32` directives `javac` emits for it.
        // The basic-block same-line dedup collapses every `LINENUMBER 32` directive
        // beyond the first one in the false-branch basic block; the lambda body is
        // independently suppressed by the lambda-shadow check.
        //
        // The intervening `LINENUMBER 31` (`Optional.ofNullable(id)`) does NOT reset the
        // per-block dedup state — only crossing a jump / switch / exception-handler-target
        // label resets it. So the count is `1` on every JDK that emits the
        // ternary-with-chained-call this way (JDK 8 and JDK 17 both qualify).
        val sites = transformAndCollect(
            JavaChainedCallFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(JavaChainedCallFixture::class.java, line = 32)),
        )
        val findOrThrowHooks = sites.count { it.method == "findOrThrow" && it.line == 32 }
        val lambdaHooks = sites.count { it.method.startsWith("lambda\$") }
        assertEquals("Expected exactly one findOrThrow hook at line 32, got sites=$sites", 1, findOrThrowHooks)
        assertEquals("Lambda body must be shadowed, got sites=$sites", 0, lambdaHooks)
    }

    @Test
    fun `Kotlin -- elvis-chain breakpoint emits at least one hook in findOrThrow`() {
        // The `if (id == null) … else (id.takeIf { it > 0 } ?: throw …)` shape compiles to
        // an inlined `takeIf` body whose boolean result drives a `?:` operator, so `kotlinc`
        // emits multiple `LINENUMBER 22` directives across the body: false-branch entry,
        // post-`takeIf` ternary entry, and `?:` null-check entry. Each lands in its own
        // basic block (separated by jump targets), so the basic-block same-line dedup
        // preserves all of them — matching the JDI spec's "a line may have more than one
        // executable location".
        //
        // We assert `≥1` rather than an exact count because the exact number of branches
        // is `kotlinc`-version-dependent. The cross-version invariant is "the breakpoint
        // reaches the parent method"; exact-count regressions of the algorithm are pinned
        // by the for-loop fixtures below, whose LineNumberTable shape is `javac`-stable.
        val sites = transformAndCollect(
            KotlinChainedCallFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(KotlinChainedCallFixture::class.java, line = 22)),
        )
        val findOrThrowHooks = sites.count { it.method == "findOrThrow" && it.line == 22 }
        val lambdaHooks = sites.count { it.method.startsWith("lambda\$") }
        assertTrue("Expected ≥1 findOrThrow hook at line 22, got sites=$sites", findOrThrowHooks >= 1)
        // Kotlin's `takeIf` lambda is inlined at the bytecode level, so there is no
        // separate `lambda$findOrThrow$0` method to check against here.
        assertEquals("No lambda methods expected for inlined takeIf, got sites=$sites", 0, lambdaHooks)
    }

    @Test
    fun `Java -- chained-call shape collapses same-line LINENUMBERs in one basic block`() {
        // `JavaChainedCallShapeFixture.chainedCall(Integer)` is the lambda-free analogue of
        // `JavaChainedCallFixture.findOrThrow`: a ternary whose false-branch is a chained
        // `Optional.ofNullable(id).orElse(new Object())` broken across source lines. `javac`
        // emits the LineNumberTable `[31, 33, 32, 33, 31]` for it — two `LINENUMBER 33`
        // directives sitting in the same false-branch basic block, separated only by an
        // unrelated `LINENUMBER 32` anchor. The basic-block same-line dedup collapses the
        // second `LINENUMBER 33` because no jump/switch/catch-handler-target label sits
        // between them — the basic block has not ended.
        //
        // No lambda body is involved here, so this test isolates the same-BB dedup behavior
        // from the lambda-shadow skip that `JavaChainedCallFixture` exercises.
        val sites = transformAndCollect(
            JavaChainedCallShapeFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(JavaChainedCallShapeFixture::class.java, line = 33)),
        )
        assertEquals(
            "Expected exactly one hook at line 33 in chainedCall, got=$sites",
            listOf(HookSite("chainedCall", line = 33)),
            sites,
        )
    }

    @Test
    fun `Kotlin -- chained-call shape emits one hook on the chained line`() {
        // Kotlin counterpart of `JavaChainedCallShapeFixture` — the same lambda-free
        // `Optional.ofNullable(id).orElse(Any())` shape inside a Kotlin `if/else` expression.
        // Unlike `javac`, `kotlinc` 2.2.0 emits the LineNumberTable `[30, 31, 32]` for it —
        // a SINGLE `LINENUMBER 32` directive for the chained `.orElse(Any())` line. So
        // there is no compiler-emitted duplicate to collapse, and the same-BB dedup logic
        // is not exercised by this fixture; the assertion is the parity check that one
        // emitted `LINENUMBER` produces one hook.
        //
        // The dedup-collapses-multiple-same-line-directives invariant for Kotlin is covered
        // by the `Kotlin -- elvis-chain ...` test above, where `kotlinc` does emit multiple
        // `LINENUMBER 22` directives inside `KotlinChainedCallFixture.findOrThrow`.
        val sites = transformAndCollect(
            KotlinChainedCallShapeFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(KotlinChainedCallShapeFixture::class.java, line = 32)),
        )
        assertEquals(
            "Expected exactly one hook at line 32 in chainedCall, got=$sites",
            listOf(HookSite("chainedCall", line = 32)),
            sites,
        )
    }

    @Test
    fun `Java -- mutually-exclusive branches ending on the same line each emit a hook`() {
        // `JavaBranchedSameLineFixture.branchedSameLine(int)` adds an inline `continue`
        // to the counting-for-loop body, so the increment basic block is reached both by
        // body fall-through AND by the `continue`'s forward jump — i.e. it has multiple
        // predecessors. `javac` still emits two `LINENUMBER 33` directives (initialiser
        // + increment) in disjoint basic blocks, and the basic-block same-line dedup
        // preserves both.
        //
        // This is the dedup-doesn't-over-collapse invariant in a different control-flow
        // shape from `JavaForLoopFixture`: the increment BB's multi-predecessor entry
        // exercises the "any label that's a jump-target resets the per-block set" branch
        // of the label classification, not just the simple fall-through-after-condition
        // case from a plain for-loop.
        val sites = transformAndCollect(
            JavaBranchedSameLineFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(JavaBranchedSameLineFixture::class.java, line = 33)),
        )
        val headerHooks = sites.count { it.method == "branchedSameLine" && it.line == 33 }
        assertEquals(
            "Expected two hooks on the loop-header line (initialiser + increment, " +
                "across the continue's forward jump), got=$sites",
            2, headerHooks,
        )
    }

    @Test
    fun `Kotlin -- mutually-exclusive branches ending on the same line each emit a hook`() {
        // Kotlin counterpart of `JavaBranchedSameLineFixture`. Kotlin's
        // `for (i in 0 until bound)` desugars to a counting loop whose header line carries
        // the initialiser and the increment, so `kotlinc` emits two `LINENUMBER 26`
        // directives — one at the initialiser and one at the back-edge increment — in
        // disjoint basic blocks (the back-edge target is a jump target that resets the
        // per-block set). The inline `continue` puts the increment basic block under
        // multi-predecessor entry, same as the Java fixture.
        val sites = transformAndCollect(
            KotlinBranchedSameLineFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(KotlinBranchedSameLineFixture::class.java, line = 26)),
        )
        val headerHooks = sites.count { it.method == "branchedSameLine" && it.line == 26 }
        assertEquals(
            "Expected two hooks on the range-for header line (initialiser + back-edge, " +
                "across the continue's forward jump), got=$sites",
            2, headerHooks,
        )
    }

    // ── Compiler-shape coverage: each control-flow construct in multi- and single-line form ──

    @Test
    fun `Java -- if-else multi-line emits an independent hook for each branch body`() {
        // `JavaIfElseMultiLineFixture` LineNumberTable `[18, 19, 21, 23]` — the
        // then-body (`s = 1` at L19) and the else-body (`s = 2` at L21) each carry their
        // own `LINENUMBER` directive in disjoint basic blocks (the else-body's label is
        // a jump target of the `IFLE`). Breakpoints at both lines must fire independently.
        val sites = transformAndCollect(
            JavaIfElseMultiLineFixture::class.java,
            breakpoints = listOf(
                snapshotBreakpoint(JavaIfElseMultiLineFixture::class.java, line = 19),
                snapshotBreakpoint(JavaIfElseMultiLineFixture::class.java, line = 21),
            ),
        )
        assertEquals(
            "Expected one hook in each branch body, got=$sites",
            listOf(
                HookSite("ifElseMultiLine", line = 19),
                HookSite("ifElseMultiLine", line = 21),
            ),
            sites,
        )
    }

    @Test
    fun `Java -- if-else single-line collapses to a single hook`() {
        // `JavaIfElseSingleLineFixture` puts the entire `if (...) {...} else {...}` on
        // one source line. `javac` emits one `LINENUMBER 18` directive regardless of
        // the branching inside, so a breakpoint at L18 produces exactly one hook.
        val sites = transformAndCollect(
            JavaIfElseSingleLineFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(JavaIfElseSingleLineFixture::class.java, line = 18)),
        )
        assertEquals(
            listOf(HookSite("ifElseSingleLine", line = 18)),
            sites,
        )
    }

    @Test
    fun `Kotlin -- if-else multi-line emits an independent hook for each branch body`() {
        // Kotlin counterpart of `JavaIfElseMultiLineFixture`. `kotlinc` emits the
        // LineNumberTable `[17, 18, 19, 21, 23]` for it — the then-body (`s = 1` at L19)
        // and the else-body (`s = 2` at L21) each carry their own `LINENUMBER` directive
        // in disjoint basic blocks (the else-body's label is a jump target of the `IFLE`).
        // Breakpoints at both lines must fire independently.
        val sites = transformAndCollect(
            KotlinIfElseMultiLineFixture::class.java,
            breakpoints = listOf(
                snapshotBreakpoint(KotlinIfElseMultiLineFixture::class.java, line = 19),
                snapshotBreakpoint(KotlinIfElseMultiLineFixture::class.java, line = 21),
            ),
        )
        assertEquals(
            "Expected one hook in each branch body, got=$sites",
            listOf(
                HookSite("ifElseMultiLine", line = 19),
                HookSite("ifElseMultiLine", line = 21),
            ),
            sites,
        )
    }

    @Test
    fun `Kotlin -- if-else single-line collapses to a single hook`() {
        // Kotlin counterpart of `JavaIfElseSingleLineFixture`. `kotlinc` emits one
        // `LINENUMBER 18` directive for the entire `if (...) {...} else {...}` on
        // one source line, so a breakpoint at L18 produces exactly one hook.
        val sites = transformAndCollect(
            KotlinIfElseSingleLineFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(KotlinIfElseSingleLineFixture::class.java, line = 18)),
        )
        assertEquals(
            listOf(HookSite("ifElseSingleLine", line = 18)),
            sites,
        )
    }

    @Test
    fun `Java -- for-loop back-edge preserves the per-iteration LINENUMBER hit`() {
        // `JavaForLoopFixture.forLoopShape(int)` is the canonical counting for-loop:
        //
        //     int sum = 0;                        // L27
        //     for (int i = 0; i < bound; i++) {   // L28 (header)
        //         sum += i;                       // L29 (body)
        //     }
        //     return sum;                         // L31
        //
        // `javac` emits the LineNumberTable `[27, 28, 29, 28, 31]` — two `LINENUMBER 28`
        // directives, one at the initialiser (`int i = 0`) and one at the increment
        // (`i++`). They sit in disjoint basic blocks separated by the loop body BB and
        // by the back-edge target at the loop condition, so the basic-block same-line
        // dedup preserves both, matching JDI's "a line may have more than one executable
        // location" semantics.
        //
        // A regression that over-collapses `LINENUMBER 28` (e.g. by resetting only on
        // catch-handler targets, ignoring jump targets) would surface here as a single
        // hook instead of two.
        val sites = transformAndCollect(
            JavaForLoopFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(JavaForLoopFixture::class.java, line = 28)),
        )
        val headerHooks = sites.count { it.method == "forLoopShape" && it.line == 28 }
        assertEquals(
            "Expected two hooks on the loop-header line " +
                "(initialiser + per-iteration increment), got=$sites",
            2, headerHooks,
        )
    }

    @Test
    fun `Java -- for-loop single-line collapses to a single hook`() {
        // Contrast with `JavaForLoopFixture` (multi-line counting for-loop, where
        // init and increment share the header line and each emit their own
        // `LINENUMBER` directive in disjoint basic blocks → 2 hooks):
        // `JavaForLoopSingleLineFixture` puts the whole `for (init; cond; incr)
        // { body }` on one source line, so `javac` emits a single `LINENUMBER 23`
        // directive and the transformer injects exactly one hook.
        val sites = transformAndCollect(
            JavaForLoopSingleLineFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(JavaForLoopSingleLineFixture::class.java, line = 23)),
        )
        assertEquals(
            listOf(HookSite("forLoopSingleLine", line = 23)),
            sites,
        )
    }

    @Test
    fun `Kotlin -- for-loop back-edge preserves the per-iteration LINENUMBER hit`() {
        // Kotlin counterpart of `JavaForLoopFixture`. Kotlin's
        // `for (i in 0 until bound)` over an `IntRange` desugars to a counting loop
        // whose header line carries both the initialiser and the back-edge increment;
        // `kotlinc` emits the LineNumberTable `[24, 25, 26, 25, 28]` for it — two
        // `LINENUMBER 25` directives in disjoint basic blocks separated by the loop
        // body BB and by the back-edge target. The basic-block same-line dedup
        // preserves both, matching JDI's "a line may have more than one executable
        // location" semantics.
        val sites = transformAndCollect(
            KotlinForLoopFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(KotlinForLoopFixture::class.java, line = 25)),
        )
        val headerHooks = sites.count { it.method == "forLoopShape" && it.line == 25 }
        assertEquals(
            "Expected two hooks on the range-for header line " +
                "(initialiser + back-edge), got=$sites",
            2, headerHooks,
        )
    }

    @Test
    fun `Kotlin -- for-loop single-line collapses to a single hook`() {
        // Kotlin counterpart of `JavaForLoopSingleLineFixture`. `kotlinc` emits a single
        // `LINENUMBER 22` directive for the whole `for (i in 0 until bound) { s += i }`
        // on one source line, regardless of the desugared counting structure underneath.
        val sites = transformAndCollect(
            KotlinForLoopSingleLineFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(KotlinForLoopSingleLineFixture::class.java, line = 22)),
        )
        assertEquals(
            listOf(HookSite("forLoopSingleLine", line = 22)),
            sites,
        )
    }

    @Test
    fun `Java -- while-loop multi-line emits a single hook for the header`() {
        // `JavaWhileLoopMultiLineFixture` is the {@code while}-loop analogue of
        // `JavaForLoopFixture`. Unlike a counting for-loop, while-loops have no
        // separate init/increment statements sharing the header source line, so
        // `javac` emits only ONE `LINENUMBER 22` directive for the header. A
        // breakpoint at L22 fires exactly once per call (and at runtime re-fires per
        // iteration via the back-edge through the same single hook).
        val sites = transformAndCollect(
            JavaWhileLoopMultiLineFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(JavaWhileLoopMultiLineFixture::class.java, line = 22)),
        )
        assertEquals(
            listOf(HookSite("whileLoopMultiLine", line = 22)),
            sites,
        )
    }

    @Test
    fun `Java -- while-loop single-line collapses to a single hook`() {
        // `JavaWhileLoopSingleLineFixture` puts the entire while loop on one source
        // line. `javac` emits one `LINENUMBER 18` directive for it.
        val sites = transformAndCollect(
            JavaWhileLoopSingleLineFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(JavaWhileLoopSingleLineFixture::class.java, line = 18)),
        )
        assertEquals(
            listOf(HookSite("whileLoopSingleLine", line = 18)),
            sites,
        )
    }

    @Test
    fun `Kotlin -- while-loop multi-line emits a single hook for the header`() {
        // Kotlin counterpart of `JavaWhileLoopMultiLineFixture`. Like `javac`, `kotlinc`
        // emits only ONE `LINENUMBER 22` directive for the `while`-header line (no
        // separate init/increment to duplicate); the body gets its own LINENUMBER on its
        // own line. A breakpoint at L22 fires exactly once per call (and at runtime re-
        // fires per iteration via the back-edge through the same single hook).
        val sites = transformAndCollect(
            KotlinWhileLoopMultiLineFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(KotlinWhileLoopMultiLineFixture::class.java, line = 22)),
        )
        assertEquals(
            listOf(HookSite("whileLoopMultiLine", line = 22)),
            sites,
        )
    }

    @Test
    fun `Kotlin -- while-loop single-line collapses to a single hook`() {
        // Kotlin counterpart of `JavaWhileLoopSingleLineFixture`. `kotlinc` emits one
        // `LINENUMBER 18` directive for the entire while loop on one source line.
        val sites = transformAndCollect(
            KotlinWhileLoopSingleLineFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(KotlinWhileLoopSingleLineFixture::class.java, line = 18)),
        )
        assertEquals(
            listOf(HookSite("whileLoopSingleLine", line = 18)),
            sites,
        )
    }

    @Test
    fun `Java -- try-catch-finally multi-line emits one hook per inlined finally path`() {
        // `JavaTryCatchFinallyMultiLineFixture` has `finally { s = touch(s); }` on L27.
        // `javac` inlines the finally body into THREE bytecode paths — try-success,
        // catch-success, and exception-rethrow — each with its own `LINENUMBER 27`
        // directive in a disjoint basic block (the catch-handler and rethrow-handler
        // entries are exception-target labels, which the basic-block same-line dedup
        // treats as basic-block entries). All three hooks must be preserved.
        val sites = transformAndCollect(
            JavaTryCatchFinallyMultiLineFixture::class.java,
            breakpoints = listOf(
                snapshotBreakpoint(JavaTryCatchFinallyMultiLineFixture::class.java, line = 27),
            ),
        )
        val finallyHooks = sites.count {
            it.method == "tryCatchFinallyMultiLine" && it.line == 27
        }
        assertEquals(
            "Expected three hooks for the inlined finally body " +
                "(try-success + catch-success + any-exception rethrow paths), got=$sites",
            3, finallyHooks,
        )
    }

    @Test
    fun `Java -- try-catch-finally single-line collapses to a single hook`() {
        // `JavaTryCatchFinallySingleLineFixture` puts the entire try/catch/finally on
        // one source line. The bytecode still inlines the finally body into three
        // paths, but `javac` emits only ONE `LINENUMBER 18` directive for the source
        // line, so the transformer's `visitLineNumber` is invoked exactly once and one
        // hook is injected.
        val sites = transformAndCollect(
            JavaTryCatchFinallySingleLineFixture::class.java,
            breakpoints = listOf(
                snapshotBreakpoint(JavaTryCatchFinallySingleLineFixture::class.java, line = 18),
            ),
        )
        assertEquals(
            listOf(HookSite("tryCatchFinallySingleLine", line = 18)),
            sites,
        )
    }

    @Test
    fun `Kotlin -- try-catch-finally multi-line emits one hook per inlined finally path`() {
        // Kotlin counterpart of `JavaTryCatchFinallyMultiLineFixture`. `kotlinc` inlines
        // the `finally { s = touch(s) }` body (L30) into the try-success, catch-success,
        // and exception-rethrow paths, producing three separate `LINENUMBER 30` directives
        // in disjoint basic blocks (the catch-handler and rethrow-handler entries are
        // exception-target labels, which the basic-block same-line dedup treats as
        // basic-block entries). All three hooks must be preserved.
        val sites = transformAndCollect(
            KotlinTryCatchFinallyMultiLineFixture::class.java,
            breakpoints = listOf(
                snapshotBreakpoint(KotlinTryCatchFinallyMultiLineFixture::class.java, line = 30),
            ),
        )
        val finallyHooks = sites.count {
            it.method == "tryCatchFinallyMultiLine" && it.line == 30
        }
        assertEquals(
            "Expected three hooks for the inlined finally body " +
                "(try-success + catch-success + any-exception rethrow paths), got=$sites",
            3, finallyHooks,
        )
    }

    @Test
    fun `Kotlin -- try-catch-finally single-line collapses to a single hook`() {
        // Kotlin counterpart of `JavaTryCatchFinallySingleLineFixture`. The bytecode
        // still inlines the finally body into three paths, but `kotlinc` emits only ONE
        // `LINENUMBER 18` directive for the source line, so the transformer's
        // `visitLineNumber` is invoked exactly once and one hook is injected.
        val sites = transformAndCollect(
            KotlinTryCatchFinallySingleLineFixture::class.java,
            breakpoints = listOf(
                snapshotBreakpoint(KotlinTryCatchFinallySingleLineFixture::class.java, line = 18),
            ),
        )
        assertEquals(
            listOf(HookSite("tryCatchFinallySingleLine", line = 18)),
            sites,
        )
    }

    // ── JBRes-9243: breakpoints on lambda source lines reach the generated lambda class ───

        @Test
    fun `Breakpoint with non-matching className yields no hook`() {
        // `SnapshotBreakpointTransformer.visitLineNumber` filters by
        // `SnapshotBreakpoint.isApplicableTo(className, fileName)` as a safeguard even
        // when a breakpoint somehow reaches a transformer for an unrelated class. A
        // class-name mismatch must therefore produce zero hooks regardless of the line.
        val unrelatedBreakpoint = SnapshotBreakpoint(
            uuid = UUID.randomUUID(),
            className = "com.example.NotARealClass",
            fileName = "NotARealClass.java",
            // Same line as `JavaChainedCallFixture.findOrThrow`'s chained call so the
            // line check would match if the className safeguard weren't applied.
            lineNumber = 32,
            conditionClassName = null,
            conditionFactoryMethodName = null,
            conditionCapturedVars = null,
            conditionCodeFragment = null,
        )
        val sites = transformAndCollect(JavaChainedCallFixture::class.java, listOf(unrelatedBreakpoint))
        assertEquals(emptyList<HookSite>(), sites)
    }

    @Test
    fun `Breakpoint at a line not present in any method yields no hook`() {
        // No `LINENUMBER` directive carries this line, so `visitLineNumber` is never
        // called with a matching line and no hook is ever emitted.
        val sites = transformAndCollect(
            JavaChainedCallFixture::class.java,
            breakpoints = listOf(snapshotBreakpoint(JavaChainedCallFixture::class.java, line = 9_999)),
        )
        assertEquals(emptyList<HookSite>(), sites)
    }

    @Test
    fun liveDebuggerProfileAcceptsOwnerAndGeneratedDescendants() {
        val fixture = LambdaFixtureClass
        val profile = LiveDebuggerTransformationProfile(
            LiveDebuggerSettings(
                listOf(snapshotBreakpoint(fixture.outerCanonicalName, fixture.fileName, line = 1))
            )
        )

        assertTrue(profile.shouldTransform(fixture.outerCanonicalName))
        assertTrue(profile.shouldTransform(fixture.lambdaCanonicalName))
        assertFalse(profile.shouldTransform("java.util.ArrayList"))
    }

    @Test
    fun snapshotBreakpointsAreInjectedIntoOwnerAndGeneratedClasses() {
        val fixture = LambdaFixtureClass
        // Two breakpoints, both owned by the outer class: one on a line that lives
        // in the outer class body and one on a line that lives only in the lambda body.
        val breakpoints = listOf(
            snapshotBreakpoint(fixture.outerCanonicalName, fixture.fileName, line = 27),
            snapshotBreakpoint(fixture.outerCanonicalName, fixture.fileName, line = 29),
        )
        assertSnapshotHookInjected(fixture.outerInternalName, breakpoints)
        assertSnapshotHookInjected(fixture.lambdaInternalName, breakpoints)
    }

    private fun assertSnapshotHookInjected(
        internalClassName: String,
        breakpoints: List<SnapshotBreakpoint>,
    ) {
        val transformed = transformWithSnapshotBreakpoints(internalClassName, breakpoints)
        val hookCount = transformed.snapshotHookInvocationCount()
        assertTrue(
            "$internalClassName must contain Injections.onSnapshotLineBreakpoint, got=$hookCount",
            hookCount >= 1,
        )
    }

    private object LambdaFixtureClass {
        const val fileName = "LambdaFixture.kt"
        const val outerCanonicalName = "org.jetbrains.kotlinx.lincheck_test.fixtures.LambdaFixture"
        const val outerInternalName = "org/jetbrains/kotlinx/lincheck_test/fixtures/LambdaFixture"
        const val lambdaCanonicalName = "$outerCanonicalName\$run\$1"
        const val lambdaInternalName = "$outerInternalName\$run\$1"
    }
}

private data class HookSite(val method: String, val line: Int)

/**
 * Reads [fixtureClass]'s `.class` bytes from the classpath, runs them through
 * [transformWithSnapshotBreakpoints] with [breakpoints], and returns the
 * `(method, line)` of every snapshot hook the transformer emits, in the order
 * they appear in the bytecode.
 */
private fun transformAndCollect(
    fixtureClass: Class<*>,
    breakpoints: List<SnapshotBreakpoint>,
): List<HookSite> {
    val internalName = fixtureClass.name.replace('.', '/')
    val transformedBytes = transformWithSnapshotBreakpoints(internalName, breakpoints)
    return collectSnapshotHookSites(transformedBytes)
}

/**
 * Walks [transformedBytes] and records `(methodName, line)` for every `INVOKESTATIC`
 * of `Injections.onSnapshotLineBreakpoint` — the static hook the snapshot-breakpoint
 * transformer injects. The line is taken from the most recent `LINENUMBER` directive,
 * which the transformer leaves unchanged when it inserts a hook.
 */
private fun collectSnapshotHookSites(transformedBytes: ByteArray): List<HookSite> {
    val sites = mutableListOf<HookSite>()
    ClassReader(transformedBytes).accept(
        object : ClassVisitor(ASM9) {
            override fun visitMethod(
                access: Int, methodName: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?,
            ) = object : MethodVisitor(ASM9) {
                private var currentLine = -1

                override fun visitLineNumber(line: Int, start: Label) {
                    currentLine = line
                }

                override fun visitMethodInsn(
                    opcode: Int, owner: String, name: String,
                    descriptor: String, isInterface: Boolean,
                ) {
                    if (opcode == INVOKESTATIC &&
                        owner == "sun/nio/ch/lincheck/Injections" &&
                        name == "onSnapshotLineBreakpoint"
                    ) {
                        sites += HookSite(methodName, currentLine)
                    }
                }
            }
        },
        0,
    )
    return sites
}

internal fun snapshotBreakpoint(
    fixtureClass: Class<*>,
    line: Int,
): SnapshotBreakpoint = SnapshotBreakpoint(
    uuid = UUID.randomUUID(),
    className = fixtureClass.name,
    // SnapshotBreakpointTransformer matches on (className, lineNumber) only;
    // `fileName` is informational and unused by dedup, so any value works here.
    fileName = "${fixtureClass.simpleName}.unknown",
    lineNumber = line,
    conditionClassName = null,
    conditionFactoryMethodName = null,
    conditionCapturedVars = null,
    conditionCodeFragment = null,
)

/**
 * Creates a [SnapshotBreakpoint] with no condition attached. Tests that need
 * a conditional breakpoint should construct [SnapshotBreakpoint] directly.
 */
internal fun snapshotBreakpoint(
    className: String,
    fileName: String,
    line: Int,
): SnapshotBreakpoint = SnapshotBreakpoint(
    uuid = UUID.randomUUID(),
    className = className,
    fileName = fileName,
    lineNumber = line,
    conditionClassName = null,
    conditionFactoryMethodName = null,
    conditionCapturedVars = null,
    conditionCodeFragment = null,
)

/**
 * Reads `$internalClassName.class` from the test classpath,
 * runs the [LincheckClassVisitor] with [LiveDebuggerTransformationProfile]
 * (which gates everything except [SnapshotBreakpointTransformer] off),
 * and returns the resulting bytes.
 *
 * Per-class data ([MethodLabels], `nonSyntheticMethodLines`, …) is assembled
 * by the [buildClassInformation] helper — the same one the agent invokes from
 * [LincheckClassFileTransformer.transformImpl] — so the visitor sees the same shape
 * of inputs in tests as at runtime.
 */
internal fun transformWithSnapshotBreakpoints(
    internalClassName: String,
    breakpoints: List<SnapshotBreakpoint>,
): ByteArray {
    val classBytes = readClassBytes(internalClassName)
    val reader = ClassReader(classBytes)
    val classLoader = Thread.currentThread().contextClassLoader
    val writer = SafeClassWriter(reader, classLoader, ClassWriter.COMPUTE_FRAMES)

    val classNode = ClassNode()
    reader.accept(classNode, ClassReader.EXPAND_FRAMES)

    val liveDebuggerSettings = LiveDebuggerSettings(breakpoints)
    val profile = LiveDebuggerTransformationProfile(liveDebuggerSettings)

    val classInformation = buildClassInformation(classNode, reader, profile)

    classNode.accept(
        LincheckClassVisitor(
            classVisitor = writer,
            classInformation = classInformation,
            instrumentationMode = LIVE_DEBUGGING,
            profile = profile,
            statsTracker = null,
            liveDebuggerSettings = liveDebuggerSettings,
            context = TraceContext(),
        ),
    )
    return writer.toByteArray()
}

/**
 * Counts INVOKESTATIC calls to `Injections.onSnapshotLineBreakpoint` in the
 * given class bytes. The transformer emits one such call per instrumented
 * source line, so this is the simplest indicator that a breakpoint was wired
 * into the bytecode.
 */
internal fun ByteArray.snapshotHookInvocationCount(): Int {
    var count = 0
    ClassReader(this).accept(object : ClassVisitor(ASM9) {
        override fun visitMethod(
            access: Int, name: String?, desc: String?, sig: String?, ex: Array<out String>?,
        ): MethodVisitor = object : MethodVisitor(ASM9) {
            override fun visitMethodInsn(
                opcode: Int,
                owner: String?,
                name: String?,
                descriptor: String?,
                isInterface: Boolean,
            ) {
                if (opcode == INVOKESTATIC &&
                    owner == "sun/nio/ch/lincheck/Injections" &&
                    name == "onSnapshotLineBreakpoint"
                ) count++
            }
        }
    }, 0)
    return count
}

private fun readClassBytes(internalClassName: String): ByteArray =
    requireNotNull(
        Thread.currentThread().contextClassLoader.getResourceAsStream("$internalClassName.class")
    ) { "Could not find $internalClassName.class on the test classpath" }
        .use { it.readBytes() }
