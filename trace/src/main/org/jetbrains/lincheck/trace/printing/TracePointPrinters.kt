/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.printing

import org.jetbrains.lincheck.descriptors.*
import org.jetbrains.lincheck.trace.*


interface TraceAppendable {
    val verbose: Boolean
    val printDiff: Boolean

    fun appendDiffStatus(status: DiffStatus?): TraceAppendable
    fun appendClassName(cd: ClassDescriptor): TraceAppendable
    fun appendMethodName(md: MethodDescriptor): TraceAppendable
    fun appendFieldName(fd: FieldDescriptor): TraceAppendable
    fun appendVariableName(vd: VariableDescriptor): TraceAppendable
    fun appendArray(arr: TraceValue): TraceAppendable
    fun appendArrayIndex(index: Int): TraceAppendable
    fun appendObject(obj: TraceValue?): TraceAppendable
    fun appendKeyword(keyword: String): TraceAppendable
    fun appendSpecialSymbol(symbol: String): TraceAppendable
    fun append(text: String?): TraceAppendable
}

fun TraceAppendable.appendAccessPath(accessPath: AccessPath) {
    for (i in accessPath.locations.indices) {
        val location = accessPath.locations[i]
        val nextLocation = accessPath.locations.getOrNull(i + 1)

        if (location.isThisAccess()) continue

        when (location) {
            is LocalVariableAccessLocation -> {
                appendVariableName(location.variableDescriptor)
                if (nextLocation is FieldAccessLocation) {
                    appendSpecialSymbol(".")
                }
            }

            is StaticFieldAccessLocation -> {
                appendClassName(location.fieldDescriptor.classDescriptor)
                appendSpecialSymbol(".")
                appendFieldName(location.fieldDescriptor)
                if (nextLocation is FieldAccessLocation) {
                    appendSpecialSymbol(".")
                }
            }

            is ObjectFieldAccessLocation -> {
                appendFieldName(location.fieldDescriptor)
                if (nextLocation is FieldAccessLocation) {
                    appendSpecialSymbol(".")
                }
            }

            is ArrayElementByIndexAccessLocation -> {
                appendSpecialSymbol("[")
                appendArrayIndex(location.index)
                appendSpecialSymbol("]")
            }

            is ArrayElementByNameAccessLocation -> {
                appendSpecialSymbol("[")
                appendAccessPath(location.indexAccessPath)
                appendSpecialSymbol("]")
            }
        }
    }
}

abstract class AbstractTraceAppendable: TraceAppendable {
    final override fun appendClassName(cd: ClassDescriptor) = appendClassName(cd.name.adornedClassNameRepresentation())
    protected open fun appendClassName(prettyClassName: String): TraceAppendable = append(prettyClassName)

    final override fun appendMethodName(md: MethodDescriptor) = appendMethodName(md.methodName.prettifyMethodName(), md)
    protected open fun appendMethodName(prettyMethodName: String, md: MethodDescriptor): TraceAppendable = append(prettyMethodName)

    final override fun appendFieldName(fd: FieldDescriptor) = appendFieldName(fd.fieldName.prettifyFieldName(), fd)
    protected open fun appendFieldName(prettyFieldName: String, fd: FieldDescriptor): TraceAppendable = append(prettyFieldName)

    final override fun appendVariableName(vd: VariableDescriptor) = appendVariableName(vd.name.prettifyVariableName(), vd)
    protected open fun appendVariableName(prettyVariableName: String, vd: VariableDescriptor): TraceAppendable = append(prettyVariableName)

    override fun appendArray(arr: TraceValue): TraceAppendable = append(arr.toString())
    override fun appendArrayIndex(index: Int): TraceAppendable = append(index.toString())
    override fun appendObject(obj: TraceValue?): TraceAppendable = append(obj.toString())
    override fun appendKeyword(keyword: String): TraceAppendable = append(keyword)
    override fun appendSpecialSymbol(symbol: String): TraceAppendable = append(symbol)

    private fun String.prettifyMethodName(): String = this
        .removeCoroutinesCoreSuffix()

    private fun String.prettifyFieldName(): String = this
        .removeVolatileDollarFU()
        .removeLeadingDollar()

    private fun String.prettifyVariableName(): String = this
        .removeInlineIV()
        .removeDollarThis()
        .removeLeadingDollar()
}

class DefaultTRTextAppendable(
    private val destination: Appendable,
    override val verbose: Boolean = false,
    override val printDiff: Boolean = true
): AbstractTraceAppendable() {
    override fun appendDiffStatus(status: DiffStatus?): TraceAppendable {
        if (!printDiff) return this
        when (status) {
            DiffStatus.UNCHANGED -> append("  ")
            DiffStatus.REMOVED -> append("- ")
            DiffStatus.ADDED -> append("+ ")
            DiffStatus.EDITED_OLD -> append("! ")
            DiffStatus.EDITED_NEW -> append("! ")
            null -> Unit
        }
        return this
    }

    override fun append(text: String?): TraceAppendable {
        destination.append(text)
        return this
    }
}

abstract class AbstractTraceMethodCallTracePointPrinter() {

    protected fun TraceAppendable.appendTracePoint(
        tracePoint: TraceMethodCallTracePoint,
        parentCall: TraceMethodCallTracePoint? = null,
    ): TraceAppendable {
        appendDiffStatus(tracePoint.diffStatus)
        if (tracePoint.isConstructor()) {
            if (tracePoint.isSuperConstructorCall()) {
                appendKeyword("super@")
            } else {
                appendKeyword("new")
                appendSpecialSymbol(" ")
            }
            appendClassName(tracePoint.classDescriptor)
            appendSpecialSymbol("(")
            appendParameters(tracePoint)
            appendSpecialSymbol(")")
            appendResult(tracePoint)
        } else {
            appendOwner(tracePoint, parentCall)
            appendMethodName(tracePoint.methodDescriptor)
            appendSpecialSymbol("(")
            appendParameters(tracePoint)
            appendSpecialSymbol(")")
            appendResult(tracePoint)
        }
        return this
    }

    protected fun TraceAppendable.appendOwner(
        tracePoint: TraceMethodCallTracePoint,
        parentCall: TraceMethodCallTracePoint? = null,
    ): TraceAppendable {
        if (tracePoint.isStatic() && tracePoint.isCalledFromDefiningClass(parentCall)) {
            return this
        }
        val ownerName = tracePoint.accessPath
        if (ownerName != null) {
            ownerName.filterThisAccesses().takeIf { !it.isEmpty() }?.let {
                if (it.isObjectInstanceAccess()) {
                    appendClassName(tracePoint.classDescriptor)
                    appendSpecialSymbol(".")
                } else if (it.isCompanionAccess()) {
                    if (!tracePoint.isCalledFromDefiningClass(parentCall)) {
                        appendClassName(ClassDescriptor(
                            tracePoint.context,
                            tracePoint.classDescriptor.name.substringBeforeLast("\$Companion"),
                        ))
                        appendSpecialSymbol(".")
                    }
                } else {
                    appendAccessPath(ownerName)
                    appendSpecialSymbol(".")
                }
            }
        } else if (tracePoint.obj !is TraceNull) {
            appendObject(tracePoint.obj)
            appendSpecialSymbol(".")
        } else if (!(tracePoint.isStatic() && tracePoint.className.isKtClass())) {
            // TODO: some refactoring is required here, because users can define classes, which end with 'Kt' as well
            appendClassName(tracePoint.classDescriptor)
            appendSpecialSymbol(".")
        }
        return this
    }

    protected fun TraceAppendable.appendParameters(tracePoint: TraceMethodCallTracePoint): TraceAppendable {
        // Due to trace compression codelocation can be shifted and therefore argument names do not match
        // Without having parameter names it is impossible to match up
        // In practise I have only seen `null` names for those kind of pais so probably doesn't really matter.
        val argumentNames = if (tracePoint.parameters.size == tracePoint.argumentNames.size) {
            tracePoint.argumentNames
        } else {
            List(tracePoint.parameters.size) { null }
        }
        
        tracePoint.parameters.forEachIndexed { i, parameter ->
            if (i != 0) {
                appendSpecialSymbol(",")
                append(" ")
            }
            val accessPath = argumentNames[i]
            when {
                accessPath == null -> appendObject(parameter)
                // Inline-renderable values (their toString reveals the full content) get the
                // `name ➜ value` form. Identity-tracked objects/arrays render as `ClassName@identity`,
                // which adds no information over the name, so we just print the name.
                parameter is TraceValueLike || parameter is TraceTypeReference || parameter is TraceTextSnapshot -> {
                    appendAccessPath(accessPath)
                    append(" ")
                    appendSpecialSymbol(READ_ACCESS_SYMBOL)
                    append(" ")
                    appendObject(parameter)
                }
                else -> appendAccessPath(accessPath)
            }
        }
        return this
    }

    protected fun TraceAppendable.appendResult(tracePoint: TraceMethodCallTracePoint): TraceAppendable {
        if (tracePoint.exceptionClassName != null) {
            append(": ")
            appendKeyword("threw")
            append(" ")
            append(tracePoint.exceptionClassName)
        } else if (tracePoint.isMethodUnfinished()) {
            append(": ")
            appendSpecialSymbol(UNFINISHED_METHOD_RESULT_SYMBOL)
        } else if (tracePoint.isMethodResultUntracked()) {
            append(": ")
            appendSpecialSymbol(UNTRACKED_METHOD_RESULT_SYMBOL)
        } else if (tracePoint.result != TraceVoid) {
            append(": ")
            appendObject(tracePoint.result)
        }
        return this
    }
}

object DefaultTRMethodCallTracePointPrinter: AbstractTraceMethodCallTracePointPrinter() {

    fun TraceAppendable.append(
        tracePoint: TraceMethodCallTracePoint,
        parentCall: TraceMethodCallTracePoint? = null,
    ): TraceAppendable {
        appendTracePoint(tracePoint, parentCall)
        append(tracePoint, verbose)
        return this
    }
}

abstract class AbstractTraceMethodCallResultTracePointPrinter {

    protected fun TraceAppendable.appendTracePoint(tracePoint: TraceMethodCallResultTracePoint): TraceAppendable {
        appendDiffStatus(tracePoint.diffStatus)
        if (tracePoint.exceptionClassName != null) {
            appendKeyword("throw")
            append(" ")
            append(tracePoint.exceptionClassName)
        } else if (tracePoint.result is TraceUnfinishedMethodResult) {
            appendSpecialSymbol(UNFINISHED_METHOD_RESULT_SYMBOL)
        } else if (tracePoint.result is TraceUntrackedMethodResult) {
            appendSpecialSymbol(UNTRACKED_METHOD_RESULT_SYMBOL)
        } else {
            if (tracePoint.result != TraceVoid) {
                appendKeyword("return")
                append(" ")
                appendObject(tracePoint.result)
            }
        }
        return this
    }
}

object DefaultTRMethodCallResultTracePointPrinter: AbstractTraceMethodCallResultTracePointPrinter() {
    fun TraceAppendable.append(tracePoint: TraceMethodCallResultTracePoint): TraceAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint, verbose)
        return this
    }
}

abstract class AbstractTraceLoopTracePointPrinter {

    protected fun TraceAppendable.appendTracePoint(tracePoint: TraceLoopTracePoint): TraceAppendable {
        appendDiffStatus(tracePoint.diffStatus)
        appendKeyword("loop")
        appendSpecialSymbol("(")
        append("${tracePoint.iterationsAsString} iterations")
        appendSpecialSymbol(")")
        return this
    }
}

object DefaultTRLoopTracePointPrinter: AbstractTraceLoopTracePointPrinter() {
    fun TraceAppendable.append(tracePoint: TraceLoopTracePoint): TraceAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint, verbose)
        return this
    }
}

abstract class AbstractTraceLoopEndTracePointPrinter {

    protected fun TraceAppendable.appendTracePoint(tracePoint: TraceLoopEndTracePoint): TraceAppendable {
        appendDiffStatus(tracePoint.diffStatus)
        appendKeyword("end loop")
        appendSpecialSymbol("(")
        append("${tracePoint.iterations} iterations")
        appendSpecialSymbol(")")
        return this
    }
}

object DefaultTRLoopEndTracePointPrinter: AbstractTraceLoopEndTracePointPrinter() {
    fun TraceAppendable.append(tracePoint: TraceLoopEndTracePoint): TraceAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint, verbose)
        return this
    }
}

abstract class AbstractTraceLoopIterationTracePointPrinter {

    protected fun TraceAppendable.appendTracePoint(tracePoint: TraceLoopIterationTracePoint): TraceAppendable {
        appendDiffStatus(tracePoint.diffStatus)
        appendSpecialSymbol("<")
        appendKeyword("iteration ")
        append("${tracePoint.loopIteration + 1}")
        appendSpecialSymbol(">")
        return this
    }
}

object DefaultTRLoopIterationTracePointPrinter: AbstractTraceLoopIterationTracePointPrinter() {
    fun TraceAppendable.append(tracePoint: TraceLoopIterationTracePoint): TraceAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint, verbose)
        return this
    }
}

abstract class AbstractTraceLoopIterationEndTracePointPrinter {

    protected fun TraceAppendable.appendTracePoint(tracePoint: TraceLoopIterationEndTracePoint): TraceAppendable {
        appendDiffStatus(tracePoint.diffStatus)
        appendSpecialSymbol("<")
        appendKeyword("end iteration")
        appendSpecialSymbol(">")
        return this
    }
}

object DefaultTRLoopIterationEndTracePointPrinter: AbstractTraceLoopIterationEndTracePointPrinter() {
    fun TraceAppendable.append(tracePoint: TraceLoopIterationEndTracePoint): TraceAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint, verbose)
        return this
    }
}

abstract class AbstractTraceFieldTracePointPrinter {

    protected fun TraceAppendable.appendTracePoint(tracePoint: TraceFieldTracePoint): TraceAppendable {
        appendDiffStatus(tracePoint.diffStatus)
        appendOwner(tracePoint)
        appendFieldName(tracePoint)
        append(" ")
        appendSpecialSymbol(tracePoint.accessSymbol())
        append(" ")
        appendObject(tracePoint.value)
        return this
    }

    protected fun TraceAppendable.appendOwner(tracePoint: TraceFieldTracePoint): TraceAppendable {
        val ownerName = tracePoint.accessPath
        val appendDot = {
            // When lambda captures a local variable, it is wrapped into the `*Ref` class,
            // which stored primitive value in the ` element ` field. We hide such field accesses:
            // see the implementation of the `appendFieldName` (it does not print the field name in such cases).
            // So to avoid a dot symbol after which there will be no actual field name, we
            // need to ensure that a dot is appended only when it is not the described case.
            if (!isLambdaCaptureSyntheticField(tracePoint)) appendSpecialSymbol(".")
        }
        if (ownerName != null) {
            ownerName.filterThisAccesses().takeIf { !it.isEmpty() }?.let {
                appendAccessPath(it)
                appendDot()
            }
        } else if (tracePoint.obj !is TraceNull) {
            appendObject(tracePoint.obj)
            appendDot()
        } else {
            appendClassName(tracePoint.classDescriptor)
            appendDot()
        }
        return this
    }

    protected fun TraceAppendable.appendFieldName(tracePoint: TraceFieldTracePoint): TraceAppendable {
        if (!isLambdaCaptureSyntheticField(tracePoint)) {
            appendFieldName(tracePoint.fieldDescriptor)
        }
        return this
    }

    private fun isLambdaCaptureSyntheticField(tracePoint: TraceFieldTracePoint): Boolean {
        return tracePoint.className.startsWith("kotlin.jvm.internal.Ref$") && tracePoint.name == "element"
    }
}

object DefaultTRFieldTracePointPrinter: AbstractTraceFieldTracePointPrinter() {

    fun TraceAppendable.append(tracePoint: TraceFieldTracePoint): TraceAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint, verbose)
        return this
    }
}


abstract class AbstractTraceLocalVariableTracePointPrinter {

    protected fun TraceAppendable.appendTracePoint(tracePoint: TraceLocalVariableTracePoint): TraceAppendable {
        appendDiffStatus(tracePoint.diffStatus)
        appendVariableName(tracePoint.variableDescriptor)
        append(" ")
        appendSpecialSymbol(tracePoint.accessSymbol())
        append(" ")
        appendObject(tracePoint.value)
        return this
    }
}

object DefaultTRLocalVariableTracePointPrinter: AbstractTraceLocalVariableTracePointPrinter() {

    fun TraceAppendable.append(tracePoint: TraceLocalVariableTracePoint): TraceAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint, verbose)
        return this
    }
}

abstract class AbstractTraceArrayTracePointPrinter {

    protected fun TraceAppendable.appendTracePoint(tracePoint: TraceArrayTracePoint): TraceAppendable {
        appendDiffStatus(tracePoint.diffStatus)
        appendOwner(tracePoint)
        appendSpecialSymbol("[")
        appendArrayIndex(tracePoint.index)
        appendSpecialSymbol("]")
        append(" ")
        appendSpecialSymbol(tracePoint.accessSymbol())
        append(" ")
        appendObject(tracePoint.value)
        return this
    }

    // TODO: DR-356 `ArrayElementByIndexAccessLocation` and `ArrayElementByNameAccessLocation` do not appear in trace
    protected fun TraceAppendable.appendOwner(tracePoint: TraceArrayTracePoint): TraceAppendable {
        val ownerName = tracePoint.accessPath
        if (ownerName != null) {
            ownerName.filterThisAccesses().takeIf { !it.isEmpty() }?.let {
                appendAccessPath(it)
            }
        } else {
            appendArray(tracePoint.array)
        }
        return this
    }
}

object DefaultTRArrayTracePointPrinter: AbstractTraceArrayTracePointPrinter() {

    fun TraceAppendable.append(tracePoint: TraceArrayTracePoint): TraceAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint, verbose)
        return this
    }
}

object DefaultTRLineBreakpointSnapshotTracePointPrinter {
    fun TraceAppendable.append(tracePoint: TraceSnapshotLineBreakpointTracePoint): TraceAppendable {
        append("Live breakpoint [${tracePoint.breakpointUuid}]")
        append(tracePoint, verbose)
        if (tracePoint.watches.isNotEmpty()) {
            append(", watches: [")
            append(tracePoint.watches.joinToString(", "))
            append("]")
        }
        append(", ")

        // timestamp is not printed to ensure printed text is deterministic
        // (as it is used in integration tests to check against golden data);
        // TODO: make timestamp printing configurable
        // val timeStampRepresentation = Instant.ofEpochMilli(tracePoint.currentTimeMillis).toString()
        // append("[$timeStampRepresentation] ")

        // Show condensed stack trace: depth and deepest 3 calls
        val stackTrace = tracePoint.stackTrace
        val deepestCalls = stackTrace.take(3)
        append("stacktrace: [")
        append(deepestCalls.joinToString(", ") { 
            "${it.className.substringAfterLast(".")}.${it.methodName}" 
        })
        val remainingSize = stackTrace.size - deepestCalls.size
        if (remainingSize > 0) append(" ... ($remainingSize more)")
        append("]")

        return this
    }
}

abstract class AbstractTraceThrowTracePointPrinter {

    protected fun TraceAppendable.appendTracePoint(tracePoint: TraceThrowTracePoint): TraceAppendable {
        appendDiffStatus(tracePoint.diffStatus)
        appendKeyword("throw")
        append(" ")
        appendObject(tracePoint.exception)
        return this
    }
}

object DefaultTRThrowTracePointPrinter: AbstractTraceThrowTracePointPrinter() {

    fun TraceAppendable.append(tracePoint: TraceThrowTracePoint): TraceAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint, verbose)
        return this
    }
}

abstract class AbstractTraceCatchTracePointPrinter {

    protected fun TraceAppendable.appendTracePoint(tracePoint: TraceCatchTracePoint): TraceAppendable {
        appendDiffStatus(tracePoint.diffStatus)
        appendKeyword("catch")
        append("(")
        appendObject(tracePoint.exception)
        append(")")
        return this
    }
}

object DefaultTRCatchTracePointPrinter: AbstractTraceCatchTracePointPrinter() {

    fun TraceAppendable.append(tracePoint: TraceCatchTracePoint): TraceAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint, verbose)
        return this
    }
}

internal fun <V: TraceAppendable> V.append(tracePoint: TracePoint, verbose: Boolean): V {
    if (!verbose) return this
    val cl = tracePoint.context.stackTrace(tracePoint.codeLocationId)
    append(" at ").append(cl.fileName).append(":").append(cl.lineNumber.toString())
    return this
}