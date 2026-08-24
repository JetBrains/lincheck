/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.diff

import org.jetbrains.lincheck.trace.TRLoopIterationTracePoint
import org.jetbrains.lincheck.trace.TRLoopTracePoint
import org.jetbrains.lincheck.trace.TRMethodCallTracePoint
import org.jetbrains.lincheck.trace.TRPrimitive
import org.jetbrains.lincheck.trace.TRReadFieldTracePoint
import org.jetbrains.lincheck.trace.TRReadLocalVariableTracePoint
import org.jetbrains.lincheck.trace.TRTracePoint
import org.jetbrains.lincheck.trace.TRWriteFieldTracePoint
import org.jetbrains.lincheck.trace.serialization.LazyTraceReader
import org.jetbrains.lincheck.trace.serialization.PACK_FILENAME_EXT
import org.jetbrains.lincheck.trace.tree.TraceBuilder
import org.jetbrains.lincheck.trace.tree.readTraceTrees
import org.jetbrains.lincheck.util.tree.Tree
import org.jetbrains.lincheck.util.tree.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end characterization tests for [diffTwoTraces]:
 * two hand-crafted traces are saved, diffed into a packed diff trace,
 * and the diff is read back and rendered with per-point [org.jetbrains.lincheck.trace.DiffStatus].
 *
 * The test traces deliberately avoid patterns rewritten by the compressing postprocessor
 * (`$default`/`access$` pairs, auto-generated accessors, empty loop iterations),
 * so the rendered structure does not depend on the compression step.
 */
class TraceDiffTest {

    private fun savedTrace(build: TraceBuilder.() -> Tree.Node<TRTracePoint>): String {
        val builder = TraceBuilder()
        builder.context.setThreadName(0, "main")
        return builder.save(builder.build())
    }

    private fun diffedStructure(
        left: TraceBuilder.() -> Tree.Node<TRTracePoint>,
        right: TraceBuilder.() -> Tree.Node<TRTracePoint>,
    ): String {
        val outputBase = File.createTempFile("trace-diff-test", ".bin").also { it.deleteOnExit() }
        LazyTraceReader(savedTrace(left)).use { leftReader ->
            LazyTraceReader(savedTrace(right)).use { rightReader ->
                diffTwoTraces(leftReader, rightReader, outputBase.path)
            }
        }
        val packed = File("${outputBase.path}.$PACK_FILENAME_EXT").also { it.deleteOnExit() }
        return LazyTraceReader(packed.path).use { reader ->
            assertTrue("diff output must be marked as diff", reader.isDiff)
            buildString {
                reader.readTraceTrees().forEach { tree -> render(tree.root!!, depth = 0) }
            }.trimEnd()
        }
    }

    private fun StringBuilder.render(node: Tree.Node<TRTracePoint>, depth: Int) {
        append("  ".repeat(depth))
        append(node.data.label())
        append(" [").append(node.data.diffStatus?.toString() ?: "-").append("]\n")
        node.children.forEach { render(it, depth + 1) }
    }

    private fun TRTracePoint.label(): String = when (this) {
        is TRMethodCallTracePoint -> methodName
        is TRReadLocalVariableTracePoint -> "readVar($name)"
        is TRReadFieldTracePoint -> "read($name=${(value as? TRPrimitive)?.value})"
        is TRWriteFieldTracePoint -> "write($name=${(value as? TRPrimitive)?.value})"
        is TRLoopTracePoint -> "loop[$iterations]"
        is TRLoopIterationTracePoint -> "iter$loopIteration"
        else -> this::class.simpleName!!
    }

    private fun assertDiffedStructure(
        expected: String,
        left: TraceBuilder.() -> Tree.Node<TRTracePoint>,
        right: TraceBuilder.() -> Tree.Node<TRTracePoint>,
    ) = assertEquals(expected.trimIndent(), diffedStructure(left, right))

    @Test
    fun `identical traces produce all-unchanged diff`() {
        val trace: TraceBuilder.() -> Tree.Node<TRTracePoint> = {
            node(call("A", "root")) {
                node(readVar("x"))
                node(call("A", "child"))
            }
        }
        assertDiffedStructure(
            """
            root [UNCHANGED]
              readVar(x) [UNCHANGED]
              child [UNCHANGED]
            """,
            left = trace,
            right = trace,
        )
    }

    @Test
    fun `changed field value yields edited pair`() {
        assertDiffedStructure(
            """
            root [UNCHANGED]
              write(x=1) [EDITED_OLD]
              write(x=2) [EDITED_NEW]
            """,
            left = {
                node(call("A", "root")) { node(writeField("A", "x", TRPrimitive(1))) }
            },
            right = {
                node(call("A", "root")) { node(writeField("A", "x", TRPrimitive(2))) }
            },
        )
    }

    @Test
    fun `extra call subtree is added`() {
        assertDiffedStructure(
            """
            root [UNCHANGED]
              child [UNCHANGED]
              extra [ADDED]
                readVar(y) [ADDED]
            """,
            left = {
                node(call("A", "root")) { node(call("A", "child")) }
            },
            right = {
                node(call("A", "root")) {
                    node(call("A", "child"))
                    node(call("A", "extra")) { node(readVar("y")) }
                }
            },
        )
    }

    @Test
    fun `missing call subtree is removed`() {
        assertDiffedStructure(
            """
            root [UNCHANGED]
              child [UNCHANGED]
              extra [REMOVED]
                readVar(y) [REMOVED]
            """,
            left = {
                node(call("A", "root")) {
                    node(call("A", "child"))
                    node(call("A", "extra")) { node(readVar("y")) }
                }
            },
            right = {
                node(call("A", "root")) {
                    node(call("A", "child"))
                }
            },
        )
    }

    @Test
    fun `edited container keeps old version childless and recurses into new one`() {
        assertDiffedStructure(
            """
            root [UNCHANGED]
              child [EDITED_OLD]
              child [EDITED_NEW]
                readVar(x) [UNCHANGED]
            """,
            left = {
                node(call("A", "root")) {
                    node(call("A", "child")) { node(readVar("x")) }
                }
            },
            right = {
                node(call("A", "root")) {
                    node(call("A", "child").also { it.result = TRPrimitive(7) }) {
                        node(readVar("x"))
                    }
                }
            },
        )
    }

    @Test
    fun `edited roots are wrapped into virtual diff root`() {
        assertDiffedStructure(
            """
            <root> [-]
              root [EDITED_OLD]
              root [EDITED_NEW]
                readVar(x) [UNCHANGED]
            """,
            left = {
                node(call("A", "root").also { it.result = TRPrimitive(1) }) {
                    node(readVar("x"))
                }
            },
            right = {
                node(call("A", "root").also { it.result = TRPrimitive(2) }) {
                    node(readVar("x"))
                }
            },
        )
    }

    @Test
    fun `loop iterations are recounted from emitted children`() {
        assertDiffedStructure(
            """
            root [UNCHANGED]
              loop[3] [UNCHANGED]
                iter1 [UNCHANGED]
                  readVar(i) [UNCHANGED]
                iter2 [UNCHANGED]
                  readVar(i) [UNCHANGED]
                iter3 [ADDED]
                  readVar(i) [ADDED]
            """,
            left = {
                node(call("A", "root")) {
                    val l = loop(loopId = 1)
                    node(l) {
                        node(iteration(l)) { node(readVar("i")) }
                        node(iteration(l)) { node(readVar("i")) }
                    }
                }
            },
            right = {
                node(call("A", "root")) {
                    val l = loop(loopId = 1)
                    node(l) {
                        node(iteration(l)) { node(readVar("i")) }
                        node(iteration(l)) { node(readVar("i")) }
                        node(iteration(l)) { node(readVar("i")) }
                    }
                }
            },
        )
    }
}
