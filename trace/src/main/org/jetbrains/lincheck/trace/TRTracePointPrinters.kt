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

import org.jetbrains.lincheck.descriptors.ClassDescriptor
import org.jetbrains.lincheck.descriptors.CodeLocations
import org.jetbrains.lincheck.descriptors.FieldDescriptor
import org.jetbrains.lincheck.descriptors.MethodDescriptor
import org.jetbrains.lincheck.descriptors.VariableDescriptor


interface TRAppendable {
    val verbose: Boolean

    fun appendClassName(cd: ClassDescriptor): TRAppendable
    fun appendMethodName(md: MethodDescriptor): TRAppendable
    fun appendFieldName(fd: FieldDescriptor): TRAppendable
    fun appendVariableName(vd: VariableDescriptor): TRAppendable
    fun appendArray(arr: TRObject): TRAppendable
    fun appendArrayIndex(index: Int): TRAppendable
    fun appendObject(obj: TRObject?): TRAppendable
    fun appendKeyword(keyword: String): TRAppendable
    fun appendSpecialSymbol(symbol: String): TRAppendable
    fun append(text: String?): TRAppendable
}

abstract class AbstractTRAppendable: TRAppendable {
    final override fun appendClassName(cd: ClassDescriptor) = appendClassName(cd.name.adornedClassNameRepresentation())
    protected open fun appendClassName(prettyClassName: String): TRAppendable = append(prettyClassName)

    final override fun appendMethodName(md: MethodDescriptor) = appendMethodName(md.methodName.prettifyMethodName(), md)
    protected open fun appendMethodName(prettyMethodName: String, md: MethodDescriptor): TRAppendable = append(prettyMethodName)

    final override fun appendFieldName(fd: FieldDescriptor) = appendFieldName(fd.fieldName.prettifyFieldName(), fd)
    protected open fun appendFieldName(prettyFieldName: String, fd: FieldDescriptor): TRAppendable = append(prettyFieldName)

    final override fun appendVariableName(vd: VariableDescriptor) = appendVariableName(vd.name.prettifyVariableName(), vd)
    protected open fun appendVariableName(prettyVariableName: String, vd: VariableDescriptor): TRAppendable = append(prettyVariableName)

    override fun appendArray(arr: TRObject): TRAppendable = append(arr.toString())
    override fun appendArrayIndex(index: Int): TRAppendable = append(index.toString())
    override fun appendObject(obj: TRObject?): TRAppendable = append(obj.toString())
    override fun appendKeyword(keyword: String): TRAppendable = append(keyword)
    override fun appendSpecialSymbol(symbol: String): TRAppendable = append(symbol)

    private fun String.prettifyMethodName(): String = this
        .removeCoroutinesCoreSuffix()

    private fun String.prettifyFieldName(): String = this
        .removeVolatileDollarFU()

    private fun String.prettifyVariableName(): String = this
        .removeInlineIV()
        .removeDollarThis()
        .removeLeadingDollar()
}

class DefaultTRTextAppendable(
    private val destination: Appendable,
    override val verbose: Boolean = false
): AbstractTRAppendable() {

    override fun append(text: String?): TRAppendable {
        destination.append(text)
        return this
    }
}

abstract class AbstractTRMethodCallTracePointPrinter() {

    protected fun TRAppendable.appendTracePoint(tracePoint: TRMethodCallTracePoint): TRAppendable {
        val md = tracePoint.methodDescriptor

        appendOwner(tracePoint)
        appendSpecialSymbol(".")
        appendMethodName(md)
        appendSpecialSymbol("(")
        appendParameters(tracePoint)
        appendSpecialSymbol(")")
        appendResult(tracePoint)
        return this
    }

    protected fun TRAppendable.appendOwner(tracePoint: TRMethodCallTracePoint): TRAppendable {
        if (tracePoint.obj != null) {
            appendObject(tracePoint.obj)
        }
        else {
            appendClassName(tracePoint.classDescriptor)
        }
        return this
    }

    protected fun TRAppendable.appendParameters(tracePoint: TRMethodCallTracePoint): TRAppendable {
        tracePoint.parameters.forEachIndexed { i, parameter ->
            if (i != 0) {
                appendSpecialSymbol(",")
                append(" ")
            }
            appendObject(parameter)
        }
        return this
    }

    protected fun TRAppendable.appendResult(tracePoint: TRMethodCallTracePoint): TRAppendable {
        if (tracePoint.exceptionClassName != null) {
            append(": ")
            appendKeyword("threw")
            append(" ")
            append(tracePoint.exceptionClassName)
        } else if (tracePoint.result != TR_OBJECT_VOID) {
            append(": ")
            appendObject(tracePoint.result)
        }
        return this
    }
}

object DefaultTRMethodCallTracePointPrinter: AbstractTRMethodCallTracePointPrinter() {

    fun TRAppendable.append(tracePoint: TRMethodCallTracePoint): TRAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint.codeLocationId, verbose)
        return this
    }
}


abstract class AbstractTRFieldTracePointPrinter {

    protected fun TRAppendable.appendTracePoint(tracePoint: TRFieldTracePoint): TRAppendable {
        val isLambdaCaptureSyntheticField = isLambdaCaptureSyntheticField(tracePoint)

        appendOwner(tracePoint)
        appendFieldName(tracePoint, isLambdaCaptureSyntheticField)
        append(" ")
        appendSpecialSymbol(tracePoint.accessSymbol())
        append(" ")
        appendObject(tracePoint.value)
        return this
    }

    protected fun TRAppendable.appendOwner(tracePoint: TRFieldTracePoint): TRAppendable {
        if (tracePoint.obj != null) {
            appendObject(tracePoint.obj)
        }
        else {
            appendClassName(tracePoint.classDescriptor)
        }
        return this
    }

    protected fun TRAppendable.appendFieldName(tracePoint: TRFieldTracePoint, isLambdaCaptureSyntheticField: Boolean): TRAppendable {
        if (!isLambdaCaptureSyntheticField) {
            appendSpecialSymbol(".")
            appendFieldName(tracePoint.fieldDescriptor)
        }
        return this
    }

    private fun isLambdaCaptureSyntheticField(tracePoint: TRFieldTracePoint): Boolean {
        return tracePoint.className.startsWith("kotlin.jvm.internal.Ref$") && tracePoint.name == "element"
    }
}

object DefaultTRFieldTracePointPrinter: AbstractTRFieldTracePointPrinter() {

    fun TRAppendable.append(tracePoint: TRFieldTracePoint): TRAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint.codeLocationId, verbose)
        return this
    }
}


abstract class AbstractTRLocalVariableTracePointPrinter {

    protected fun TRAppendable.appendTracePoint(tracePoint: TRLocalVariableTracePoint): TRAppendable {
        appendVariableName(tracePoint.variableDescriptor)
        append(" ")
        appendSpecialSymbol(tracePoint.accessSymbol())
        append(" ")
        appendObject(tracePoint.value)
        return this
    }
}

object DefaultTRLocalVariableTracePointPrinter: AbstractTRLocalVariableTracePointPrinter() {

    fun TRAppendable.append(tracePoint: TRLocalVariableTracePoint): TRAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint.codeLocationId, verbose)
        return this
    }
}

abstract class AbstractTRArrayTracePointPrinter {

    protected fun TRAppendable.appendTracePoint(tracePoint: TRArrayTracePoint): TRAppendable {
        appendArray(tracePoint.array)
        appendSpecialSymbol("[")
        appendArrayIndex(tracePoint.index)
        appendSpecialSymbol("]")
        append(" ")
        appendSpecialSymbol(tracePoint.accessSymbol())
        append(" ")
        appendObject(tracePoint.value)
        return this
    }
}

object DefaultTRArrayTracePointPrinter: AbstractTRArrayTracePointPrinter() {

    fun TRAppendable.append(tracePoint: TRArrayTracePoint): TRAppendable {
        appendTracePoint(tracePoint)
        append(tracePoint.codeLocationId, verbose)
        return this
    }
}

internal fun <V: TRAppendable> V.append(codeLocationId: Int, verbose: Boolean): V {
    if (!verbose) return this
    val cl = CodeLocations.stackTrace(codeLocationId)
    append(" at ").append(cl.fileName).append(":").append(cl.lineNumber.toString())
    return this
}