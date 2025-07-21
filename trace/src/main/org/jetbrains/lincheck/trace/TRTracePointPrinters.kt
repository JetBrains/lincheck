/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.descriptors.CodeLocations
import org.jetbrains.lincheck.descriptors.FieldDescriptor
import org.jetbrains.lincheck.descriptors.MethodDescriptor
import org.jetbrains.lincheck.descriptors.VariableDescriptor


interface TRAppendable {
    fun appendClassName(prettyClassName: String, receiver: TRObject?): TRAppendable = append(prettyClassName)
    fun appendMethodName(prettyMethodName: String, md: MethodDescriptor): TRAppendable = append(prettyMethodName)
    fun appendFieldName(prettyFieldName: String, fd: FieldDescriptor): TRAppendable = append(prettyFieldName)
    fun appendVariableName(prettyVariableName: String, vd: VariableDescriptor): TRAppendable = append(prettyVariableName)
    fun appendArray(arr: TRObject): TRAppendable = append(arr.adornedRepresentation())
    fun appendArrayIndex(index: Int): TRAppendable = append(index.toString())
    fun appendObject(obj: TRObject?): TRAppendable = append(obj.toString())
    fun appendKeyword(keyword: String): TRAppendable = append(keyword)
    fun appendSpecialSymbol(symbol: String): TRAppendable = append(symbol)
    fun append(text: String?): TRAppendable
}

internal data class DefaultTRAppendable(val dest: Appendable): TRAppendable {
    override fun append(text: String?): TRAppendable {
        dest.append(text)
        return this
    }
}

abstract class AbstractTRMethodCallTracePointPrinter() {

    protected fun TRAppendable.append(tracePoint: TRMethodCallTracePoint, ) {
        val md = tracePoint.methodDescriptor

        appendClassName(tracePoint, md)
        appendSpecialSymbol(".")
        appendMethodName(md)
        appendSpecialSymbol("(")
        appendParameters(tracePoint)
        appendSpecialSymbol(")")
        appendResult(tracePoint)
    }

    protected fun TRAppendable.appendClassName(tracePoint: TRMethodCallTracePoint, methodDescriptor: MethodDescriptor) {
        val className = tracePoint.obj?.adornedRepresentation() ?: methodDescriptor.className.substringAfterLast(".")
        appendClassName(className, tracePoint.obj)
    }

    protected fun TRAppendable.appendMethodName(methodDescriptor: MethodDescriptor) {
        appendMethodName(
            methodDescriptor.methodName.removeCoroutinesCoreSuffix(),
            methodDescriptor
        )
    }

    protected fun TRAppendable.appendParameters(tracePoint: TRMethodCallTracePoint) {
        tracePoint.parameters.forEachIndexed { i, parameter ->
            if (i != 0) {
                appendSpecialSymbol(",")
                append(" ")
            }
            appendObject(parameter)
        }
    }

    protected fun TRAppendable.appendResult(tracePoint: TRMethodCallTracePoint) {
        if (tracePoint.exceptionClassName != null) {
            append(": ")
            appendKeyword("threw")
            append(" ")
            append(tracePoint.exceptionClassName)
        } else if (tracePoint.result != TR_OBJECT_VOID) {
            append(": ")
            appendObject(tracePoint.result)
        }
    }

    private fun String.removeCoroutinesCoreSuffix(): String = removeSuffix("\$kotlinx_coroutines_core")
}

internal class DefaultTRMethodCallTracePointPrinter(): AbstractTRMethodCallTracePointPrinter() {

    fun print(dest: Appendable, tracePoint: TRMethodCallTracePoint, verbose: Boolean) =
        with(DefaultTRAppendable(dest)) {
            append(tracePoint)
            append(tracePoint.codeLocationId, verbose)
        }
}


abstract class AbstractTRFieldTracePointPrinter {

    protected fun TRAppendable.append(tracePoint: TRFieldTracePoint) {
        val isLambdaCaptureSyntheticField = isLambdaCaptureSyntheticField(tracePoint)

        appendClassName(tracePoint, isLambdaCaptureSyntheticField)
        appendFieldName(tracePoint, isLambdaCaptureSyntheticField)
        append(" ")
        appendSpecialSymbol(tracePoint.accessSymbol())
        append(" ")
        appendObject(tracePoint.value)
    }

    protected fun TRAppendable.appendClassName(tracePoint: TRFieldTracePoint, isLambdaCaptureSyntheticField: Boolean) {
        val className = tracePoint.obj?.adornedRepresentation() ?: tracePoint.fieldDescriptor.className.substringAfterLast(".")
        if (!isLambdaCaptureSyntheticField) {
            appendClassName(className.removePrefix("Ref$"), tracePoint.obj)
        }
        else {
            appendClassName(className, tracePoint.obj)
        }
    }

    protected fun TRAppendable.appendFieldName(tracePoint: TRFieldTracePoint, isLambdaCaptureSyntheticField: Boolean) {
        if (!isLambdaCaptureSyntheticField) {
            appendSpecialSymbol(".")
            appendFieldName(
                tracePoint.name.removeVolatileDollar(),
                tracePoint.fieldDescriptor
            )
        }
    }

    private fun isLambdaCaptureSyntheticField(tracePoint: TRFieldTracePoint): Boolean {
        return tracePoint.className.startsWith("kotlin.jvm.internal.Ref$") && tracePoint.name == "element"
    }

    private fun String.removeVolatileDollar(): String = removeSuffix("\$volatile\$FU")
}

internal class DefaultTRFieldTracePointPrinter: AbstractTRFieldTracePointPrinter() {

    fun print(dest: Appendable, tracePoint: TRFieldTracePoint, verbose: Boolean) =
        with(DefaultTRAppendable(dest)) {
            append(tracePoint)
            append(tracePoint.codeLocationId, verbose)
        }
}


abstract class AbstractTRLocalVariableTracePointPrinter {

    protected fun TRAppendable.append(tracePoint: TRLocalVariableTracePoint) {
        val vd = tracePoint.variableDescriptor

        appendVariableName(vd.name.removeInlineIV().removeDollarThis().removeLeadingDollar(), vd)
        append(" ")
        appendSpecialSymbol(tracePoint.accessSymbol())
        append(" ")
        appendObject(tracePoint.value)
    }

    private fun String.removeInlineIV(): String = removeSuffix("\$iv")

    private fun String.removeDollarThis(): String = if (this == "\$this") this else removePrefix("\$this")

    private fun String.removeLeadingDollar(): String = removePrefix("$")
}

internal class DefaultTRLocalVariableTracePointPrinter: AbstractTRLocalVariableTracePointPrinter() {

    fun print(dest: Appendable, tracePoint: TRLocalVariableTracePoint, verbose: Boolean) =
        with(DefaultTRAppendable(dest)) {
            append(tracePoint)
            append(tracePoint.codeLocationId, verbose)
        }
}

abstract class AbstractTRArrayTracePointPrinter {

    protected fun TRAppendable.append(tracePoint: TRArrayTracePoint) {
        appendArray(tracePoint.array)
        appendSpecialSymbol("[")
        appendArrayIndex(tracePoint.index)
        appendSpecialSymbol("]")
        append(" ")
        appendSpecialSymbol(tracePoint.accessSymbol())
        append(" ")
        appendObject(tracePoint.value)
    }
}

internal class DefaultTRArrayTracePointPrinter: AbstractTRArrayTracePointPrinter() {

    fun print(dest: StringBuilder, tracePoint: TRArrayTracePoint, verbose: Boolean) {
        with(DefaultTRAppendable(dest)) {
            append(tracePoint)
            append(tracePoint.codeLocationId, verbose)
        }
    }
}

private fun <V: TRAppendable> V.append(codeLocationId: Int, verbose: Boolean): V {
    if (!verbose) return this
    val cl = CodeLocations.stackTrace(codeLocationId)
    append(" at ").append(cl.fileName).append(":").append(cl.lineNumber.toString())
    return this
}