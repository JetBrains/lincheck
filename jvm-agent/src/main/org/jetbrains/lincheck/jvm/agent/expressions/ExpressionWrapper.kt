/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions

import org.jetbrains.lincheck.jvm.agent.bytecodeinfo.LocalVariableInfo
import org.jetbrains.lincheck.jvm.agent.expressions.java.JavaWrapperSource
import org.jetbrains.lincheck.jvm.agent.expressions.kotlin.KotlinWrapperSource
import org.objectweb.asm.Type

/**
 * A local captured into a generated expression wrapper. [name] is the wrapper's field name —
 * the runtime contract with `SnapshotBreakpointTransformer`, which reads the field names from
 * the compiled bytecode and pushes the matching locals at the hook site (`__instance` ⇔ `this`).
 */
internal data class CapturedLocal(val name: String, val type: Type)

/** The condition-vs-watches shape of a generated wrapper. */
internal enum class ExpressionKind { CONDITION, WATCHES }

/**
 * Builds wrapper scaffolding for expressions captured at a breakpoint.
 *
 * JavaPoet and KotlinPoet construct the capture fields, factory, and SAM bridge. The expression itself is compiled
 * in a mock of the enclosing application class, then its evaluator method is transplanted into this wrapper.
 * The wrapper shares the instrumented class's package, and `this` is transported in the `__instance` capture.
 */
internal object ExpressionWrapper {

    /**
     * Selects the locals the expression(s) actually reference, by identifier occurrence.
     *
     * A textual match is deliberately a superset heuristic: capturing an unreferenced local is
     * harmless (its field is assigned and unused), while a missed reference surfaces as a plain
     * compile error naming the symbol. The enclosing receiver is handled separately and always captured for
     * instance methods.
     *
     * For expressions `listOf("count > limit")` and locals `this: Counter`, `count: Int`, `limit: Int`, and
     * `unused: String`, the result is `listOf(CapturedLocal("count", Int), CapturedLocal("limit", Int))` in local
     * order. `this` and `unused` are omitted; the caller adds `this` separately as `__instance` when needed.
     */
    fun selectCaptures(expressions: List<String>, locals: Collection<LocalVariableInfo>): List<CapturedLocal> {
        val identifiers = expressions
            .flatMap { IDENTIFIER_REGEX.findAll(it).map(MatchResult::value) }
            .toSet()
        val captures = mutableListOf<CapturedLocal>()
        for (local in locals.distinctBy { it.name }) {
            if (local.isInlineCallMarker || local.isInlineLambdaMarker) continue
            if (local.name != "this" && local.name in identifiers) {
                captures += CapturedLocal(local.name, local.type)
            }
        }
        return captures
    }

    fun javaSource(
        packageName: String?,
        simpleName: String,
        captures: List<CapturedLocal>,
        kind: ExpressionKind,
    ): String = JavaWrapperSource.render(packageName, simpleName, captures, kind)

    fun kotlinSource(
        packageName: String?,
        simpleName: String,
        captures: List<CapturedLocal>,
        kind: ExpressionKind,
        classLoader: ClassLoader?,
    ): String = KotlinWrapperSource.render(packageName, simpleName, captures, kind, classLoader)

    /**
     * The type the wrapper stores a capture as. Application types are stored as `Object`: the wrapper's signatures
     * must not name a class that may still be in its own definition (see [ExpressionEvaluatorTransplanter]).
     */
    fun storageType(type: Type): Type = if (type.isApplicationType()) OBJECT_TYPE else type

    /** Helper method evaluating the watch array off `invoke()`'s hot path; allowlisted by the checker. */
    const val WATCH_VALUES_HELPER = "__watchValues"

    /** The wrapper field carrying the enclosing instance; pushed as the `this` local at the hook. */
    const val INSTANCE_FIELD = "__instance"

    private val IDENTIFIER_REGEX = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val OBJECT_TYPE = Type.getType(Any::class.java)
}
