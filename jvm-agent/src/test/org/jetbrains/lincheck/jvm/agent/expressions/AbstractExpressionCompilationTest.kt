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

import org.jetbrains.lincheck.descriptors.LocalKind
import org.jetbrains.lincheck.jvm.agent.ClassModel
import org.jetbrains.lincheck.jvm.agent.LocalVariableInfo
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.ExpressionTarget
import org.jetbrains.lincheck.jvm.agent.loadClassesFromBytes
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.objectweb.asm.Label
import org.objectweb.asm.Type
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier
import java.util.function.Function
import java.util.function.Supplier

/** Runs source expressions through the complete agent-side compilation and evaluation contract. */
abstract class AbstractExpressionCompilationTest {

    protected data class Local(val name: String, val type: Type, val value: Any?)

    protected fun int(name: String, value: Int) = Local(name, Type.INT_TYPE, value)
    protected fun long(name: String, value: Long) = Local(name, Type.LONG_TYPE, value)
    protected fun double(name: String, value: Double) = Local(name, Type.DOUBLE_TYPE, value)
    protected fun float(name: String, value: Float) = Local(name, Type.FLOAT_TYPE, value)
    protected fun char(name: String, value: Char) = Local(name, Type.CHAR_TYPE, value)
    protected fun boolean(name: String, value: Boolean) = Local(name, Type.BOOLEAN_TYPE, value)
    protected fun reference(name: String, type: Class<*>, value: Any?) = Local(name, Type.getType(type), value)

    protected fun assertCondition(
        language: String,
        expression: String,
        expected: Boolean,
        locals: List<Local> = emptyList(),
        receiver: Any? = null,
        enclosingType: Class<*> = receiver?.javaClass ?: ExpressionTarget::class.java,
    ) {
        val evaluation = compile(language, listOf(expression), locals, receiver, enclosingType, watches = false)
        @Suppress("UNCHECKED_CAST")
        val factory = evaluation.wrapper.getMethod("createFactory").invoke(null)
            as Function<Array<Any?>, BooleanSupplier>
        assertEquals(expression, expected, factory.apply(evaluation.captureValues).asBoolean)
    }

    protected fun assertWatches(
        language: String,
        expressions: List<String>,
        expected: List<Any?>,
        locals: List<Local> = emptyList(),
        receiver: Any? = null,
        enclosingType: Class<*> = receiver?.javaClass ?: ExpressionTarget::class.java,
    ) {
        val evaluation = compile(language, expressions, locals, receiver, enclosingType, watches = true)
        @Suppress("UNCHECKED_CAST")
        val factory = evaluation.wrapper.getMethod("createFactory").invoke(null)
            as Function<Array<Any?>, Supplier<Array<Any?>>>
        assertArrayEquals(expressions.toString(), expected.toTypedArray(), factory.apply(evaluation.captureValues).get())
    }

    private fun compile(
        language: String,
        expressions: List<String>,
        locals: List<Local>,
        receiver: Any?,
        enclosingClass: Class<*>,
        watches: Boolean,
    ): Evaluation {
        val activeLocals = locals.mapIndexed { index, local -> localInfo(local.name, index, local.type) }.toMutableList()
        if (receiver != null) activeLocals += localInfo("this", activeLocals.size, Type.getType(enclosingClass))
        val breakpoint = SnapshotBreakpoint(
            uuid = UUID.randomUUID(),
            className = enclosingClass.name,
            fileName = enclosingClass.simpleName +
                if (language == SnapshotBreakpoint.EXPRESSION_LANGUAGE_JAVA) ".java" else ".kt",
            lineNumber = 1,
            expressionLanguage = language,
            conditionSource = expressions.singleOrNull().takeUnless { watches },
            watchSources = expressions.takeIf { watches },
        )
        val resolved = checkNotNull(
            ExpressionCompiler.resolveCompiledExpressions(
                breakpointId = nextBreakpointId.incrementAndGet(),
                breakpoint = breakpoint,
                activeLocals = activeLocals,
                enclosingClass = classModel(enclosingClass),
                classLoader = enclosingClass.classLoader,
            ),
        ) { "Expression failed to compile: $expressions" }
        val className = if (watches) resolved.watchClassName!! else resolved.conditionClassName!!
        val classes = if (watches) resolved.watchClasses!! else resolved.conditionClasses!!
        val wrapper = loadClassesFromBytes(enclosingClass.classLoader, className, classes)

        val selected = ExpressionWrapper.selectCaptures(expressions, activeLocals).map { it.name }.toMutableList()
        if (receiver != null) selected += ExpressionWrapper.INSTANCE_FIELD
        val values = selected.map { name ->
            if (name == ExpressionWrapper.INSTANCE_FIELD) receiver else locals.single { it.name == name }.value
        }.toTypedArray()
        return Evaluation(wrapper, values)
    }

    private fun localInfo(name: String, index: Int, type: Type) = LocalVariableInfo(
        name = name,
        index = index,
        type = type,
        labelIndexRange = Label() to Label(),
        localKind = LocalKind.VARIABLE,
    )

    private data class Evaluation(val wrapper: Class<*>, val captureValues: Array<Any?>)

    companion object {
        private val nextBreakpointId = AtomicInteger(20_000)
        private fun classModel(type: Class<*>): ClassModel {
            val path = "/${type.name.replace('.', '/')}.class"
            val bytes = checkNotNull(type.getResourceAsStream(path)).use { it.readBytes() }
            return ClassModel.fromClassBytes(bytes)
        }
    }
}
