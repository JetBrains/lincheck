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

import org.jetbrains.lincheck.jvm.agent.ClassModel
import org.jetbrains.lincheck.jvm.agent.LocalVariableInfo
import org.jetbrains.lincheck.jvm.agent.expressions.java.JavaEnclosingClassEvaluator
import org.jetbrains.lincheck.jvm.agent.expressions.java.JavaExpressionToolchain
import org.jetbrains.lincheck.jvm.agent.expressions.java.JavaWrapperSource
import org.jetbrains.lincheck.jvm.agent.expressions.kotlin.KotlinEnclosingClassEvaluator
import org.jetbrains.lincheck.jvm.agent.expressions.kotlin.KotlinExpressionToolchain
import org.jetbrains.lincheck.jvm.agent.expressions.kotlin.KotlinWrapperSource
import org.jetbrains.lincheck.settings.BreakpointId
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.util.Logger
import org.objectweb.asm.Type
import sun.nio.ch.lincheck.BreakpointStorage
import java.util.concurrent.ConcurrentHashMap

/**
 * Compiles condition and watch source text inside the javaagent.
 *
 * Compilation starts during instrumentation, when the locals and their JVM types are known:
 * 1. [ExpressionWrapper.selectCaptures] finds the locals named by the expressions.
 * 2. [JavaWrapperSource] or [KotlinWrapperSource] creates the runtime [ExpressionWrapper].
 *    It contains capture fields, `createFactory()`, the supplier bridge, and a placeholder evaluator.
 * 3. [JavaEnclosingClassEvaluator] or [KotlinEnclosingClassEvaluator] builds a small source copy of the
 *    application class and compiles the real expression inside it with [JavaExpressionToolchain] or
 *    [KotlinExpressionToolchain]. This gives the compiler the right receiver, members, and overloads without
 *    loading the application class.
 * 4. [ExpressionEvaluatorTransplanter] moves the compiled evaluator method from that disposable class into the
 *    wrapper and connects the wrapper's capture fields to its arguments.
 * 5. [MockedApplicationMembers] identifies accesses that the source copy made visible only for compilation.
 *    [ExpressionBytecodeRewriter] replaces them with reflective accessors understood by the safety checker.
 * 6. The resulting class files are stored on [SnapshotBreakpoint] and follow the same loading, safety checking,
 *    and evaluation path as expressions compiled by the IDE.
 *
 * For example, `count > limit` captures the local `limit` and the enclosing instance.
 * The compiler first emits an evaluator equivalent to `return count > limit`, then returns a wrapper whose
 * `invoke()` reads both captures and calls that evaluator.
 *
 * Compilation runs once per breakpoint registration and uses the locals from the first matching site.
 * A failure blocks the breakpoint and is reported once.
 */
object ExpressionCompiler {

    val isJavaAvailable: Boolean get() = JavaExpressionToolchain.isAvailable
    val isKotlinAvailable: Boolean get() = KotlinExpressionToolchain.isAvailable

    /**
     * Compiled (or failed) resolution per breakpoint registration; bounded by [MAX_CACHE_SIZE].
     * Keyed by the registration id *and* the breakpoint's UUID: ids are scoped to one settings
     * instance, so the id alone could alias across instances (as test harnesses create them).
     */
    private val resolved = ConcurrentHashMap<Pair<BreakpointId, java.util.UUID>, Result<SnapshotBreakpoint>>()

    private const val MAX_CACHE_SIZE = 512

    /**
     * Returns the breakpoint to instrument at the current site:
     * [breakpoint] itself when there is nothing to compile, a fragment-carrying enrichment when
     * its source expressions compiled, or `null` when compilation failed (already reported).
     */
    internal fun resolveCompiledExpressions(
        breakpointId: BreakpointId,
        breakpoint: SnapshotBreakpoint,
        activeLocals: Collection<LocalVariableInfo>,
        enclosingClass: ClassModel,
        classLoader: ClassLoader?,
    ): SnapshotBreakpoint? {
        val needsCondition = breakpoint.conditionClasses == null && !breakpoint.conditionSource.isNullOrBlank()
        val watchSources = breakpoint.watchSources.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        val needsWatches = breakpoint.watchClasses == null && watchSources.isNotEmpty()
        if (!needsCondition && !needsWatches) return breakpoint

        if (resolved.size >= MAX_CACHE_SIZE) {
            resolved.keys.toList().randomOrNull()?.let(resolved::remove)
        }
        val result = resolved.computeIfAbsent(breakpointId to breakpoint.uuid) {
            runCatching {
                compile(
                    breakpointId, breakpoint, needsCondition, watchSources,
                    activeLocals, enclosingClass, classLoader,
                )
            }.onFailure { failure ->
                val message = failure.message ?: "expression compilation failed"
                Logger.warn { "Breakpoint at ${breakpoint.fileName}:${breakpoint.lineNumber}: $message" }
                BreakpointStorage.notifyBreakpointExpressionCompilationFailed(breakpointId, breakpoint, message)
            }
        }
        return result.getOrNull()
    }

    /** Removes cached compilations for breakpoints that are no longer registered. */
    fun retainCompiledExpressions(activeBreakpointIds: Set<BreakpointId>) {
        resolved.keys.removeIf { (breakpointId) -> breakpointId !in activeBreakpointIds }
    }

    private fun compile(
        breakpointId: BreakpointId,
        breakpoint: SnapshotBreakpoint,
        needsCondition: Boolean,
        watchSources: List<String>,
        activeLocals: Collection<LocalVariableInfo>,
        enclosingClass: ClassModel,
        classLoader: ClassLoader?,
    ): SnapshotBreakpoint {
        val kotlin = when (breakpoint.expressionLanguage) {
            SnapshotBreakpoint.EXPRESSION_LANGUAGE_KOTLIN -> true
            SnapshotBreakpoint.EXPRESSION_LANGUAGE_JAVA -> false
            else -> throw ExpressionCompilationException(
                "Cannot compile expressions for '${breakpoint.fileName}': unsupported expression language " +
                    "'${breakpoint.expressionLanguage}'",
            )
        }
        val packageName = enclosingClass.binaryName.substringBeforeLast('.', "").ifEmpty { null }

        var conditionClassName = breakpoint.conditionClassName
        var conditionClasses = breakpoint.conditionClasses
        if (needsCondition) {
            val (binaryName, classes) = compileWrapper(
                packageName = packageName,
                // Named by the JVM-unique registration id: a re-registered breakpoint may reuse
                // its UUID (startup INI), and a class name can only be defined once per loader.
                simpleName = "LincheckAgentCondition$breakpointId",
                expressions = listOf(breakpoint.conditionSource!!),
                kind = ExpressionKind.CONDITION,
                kotlin = kotlin,
                activeLocals = activeLocals,
                enclosingClass = enclosingClass,
                classLoader = classLoader,
            )
            conditionClassName = binaryName
            conditionClasses = classes
        }

        var watchClassName = breakpoint.watchClassName
        var watchClasses = breakpoint.watchClasses
        if (watchSources.isNotEmpty() && breakpoint.watchClasses == null) {
            val (binaryName, classes) = compileWrapper(
                packageName = packageName,
                simpleName = "LincheckAgentWatches$breakpointId",
                expressions = watchSources,
                kind = ExpressionKind.WATCHES,
                kotlin = kotlin,
                activeLocals = activeLocals,
                enclosingClass = enclosingClass,
                classLoader = classLoader,
            )
            watchClassName = binaryName
            watchClasses = classes
        }

        return SnapshotBreakpoint(
            uuid = breakpoint.uuid,
            className = breakpoint.className,
            fileName = breakpoint.fileName,
            lineNumber = breakpoint.lineNumber,
            expressionLanguage = breakpoint.expressionLanguage,
            conditionClassName = conditionClassName,
            conditionFactoryMethodName = breakpoint.conditionFactoryMethodName,
            conditionClasses = conditionClasses,
            watchClassName = watchClassName,
            watchFactoryMethodName = breakpoint.watchFactoryMethodName,
            watchClasses = watchClasses,
            hitLimit = breakpoint.hitLimit,
            watchLabels = breakpoint.watchLabels ?: watchSources.takeIf { it.isNotEmpty() },
            conditionSource = breakpoint.conditionSource,
            watchSources = breakpoint.watchSources,
        )
    }

    private fun compileWrapper(
        packageName: String?,
        simpleName: String,
        expressions: List<String>,
        kind: ExpressionKind,
        kotlin: Boolean,
        activeLocals: Collection<LocalVariableInfo>,
        enclosingClass: ClassModel,
        classLoader: ClassLoader?,
    ): Pair<String, Map<String, ByteArray>> {
        // Both languages compile the expression inside a disposable source facade of the enclosing class, then
        // transplant the evaluator into the wrapper. The enclosing model comes from bytecode and remains available
        // while the real class is still being defined.
        val binaryName = if (packageName != null) "$packageName.$simpleName" else simpleName
        val classes = if (kotlin) {
            val captures = ExpressionWrapper.selectCaptures(expressions, activeLocals).toMutableList()
            activeLocals.firstOrNull { it.name == "this" }?.let {
                if (captures.none { it.name == ExpressionWrapper.INSTANCE_FIELD }) {
                    captures += CapturedLocal(ExpressionWrapper.INSTANCE_FIELD, OPAQUE_RECEIVER_TYPE)
                }
            }
            val source = ExpressionWrapper.kotlinSource(
                packageName, simpleName, captures, kind, classLoader,
            )
            KotlinEnclosingClassEvaluator.compile(
                wrapperBinaryName = binaryName,
                wrapperSource = source,
                expressions = expressions,
                kind = kind,
                captures = captures,
                enclosing = enclosingClass,
                receiverCapture = captures.firstOrNull { it.name == ExpressionWrapper.INSTANCE_FIELD },
                classLoader = classLoader,
            )
        } else {
            val captures = ExpressionWrapper.selectCaptures(expressions, activeLocals).toMutableList()
            activeLocals.firstOrNull { it.name == "this" }?.let {
                if (captures.none { it.name == ExpressionWrapper.INSTANCE_FIELD }) {
                    captures += CapturedLocal(ExpressionWrapper.INSTANCE_FIELD, OPAQUE_RECEIVER_TYPE)
                }
            }
            val source = ExpressionWrapper.javaSource(
                packageName, simpleName, captures, kind,
            )
            JavaEnclosingClassEvaluator.compile(
                wrapperBinaryName = binaryName,
                wrapperSource = source,
                expressions = expressions,
                kind = kind,
                captures = captures,
                enclosing = enclosingClass,
                receiverCapture = captures.firstOrNull { it.name == ExpressionWrapper.INSTANCE_FIELD },
                classLoader = classLoader,
            )
        }
        check(binaryName in classes) {
            "Expression compiler produced no class named $binaryName (got ${classes.keys})"
        }
        return binaryName to classes
    }

    // Generated wrappers live in a child loader, so a package-private application class is not
    // accessible even when the wrapper uses the same package name.
    // Keep the transported receiver object opaque;
    // the transplanted evaluator casts it only when an expression actually accesses the receiver.
    private val OPAQUE_RECEIVER_TYPE = Type.getType(Any::class.java)
}
