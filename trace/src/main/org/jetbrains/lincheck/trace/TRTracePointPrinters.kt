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
    fun appendClassName(prettyClassName: String): TRAppendable = append(prettyClassName)
    fun appendMethodName(prettyMethodName: String, md: MethodDescriptor): TRAppendable = append(prettyMethodName)
    fun appendFieldName(prettyFieldName: String, fd: FieldDescriptor): TRAppendable = append(prettyFieldName)
    fun appendVariableName(prettyVariableName: String, vd: VariableDescriptor): TRAppendable = append(prettyVariableName)
    fun appendArray(arr: TRObject): TRAppendable = append(arr.toString())
    fun appendArrayIndex(index: Int): TRAppendable = append(index.toString())
    fun appendObject(obj: TRObject?): TRAppendable = append(obj.toString())
    fun appendKeyword(keyword: String): TRAppendable = append(keyword)
    fun appendSpecialSymbol(symbol: String): TRAppendable = append(symbol)
    fun append(text: String?): TRAppendable
}

internal object DefaultTRTextAppendable: TRAppendable {
    private var destination: Appendable? = null

    override fun append(text: String?): TRAppendable {
        check(destination != null) { "Before using DefaultTRTextAppendable adapter, setDestination() method must be called first with non-null destination." }
        destination!!.append(text)
        return this
    }

    /**
     * Sets the new [destination] of this [TRAppendable] instance to [dest].
     *
     * To decrease memory consumption, there is only one instance of [TRAppendable] per each
     * trace point printer. That instance uses [setDestination] method to change the
     * actual underlying byte buffer before printing the new trace point.
     */
    fun setDestination(dest: Appendable): TRAppendable {
        destination = dest
        return this
    }

    /**
     * Sets [destination] to `null`.
     */
    fun reset(): TRAppendable {
        destination = null
        return this
    }
}

abstract class AbstractTRMethodCallTracePointPrinter() {

    protected fun TRAppendable.append(tracePoint: TRMethodCallTracePoint): TRAppendable {
        val md = tracePoint.methodDescriptor

        appendOwner(tracePoint, md)
        appendSpecialSymbol(".")
        appendMethodName(md)
        appendSpecialSymbol("(")
        appendParameters(tracePoint)
        appendSpecialSymbol(")")
        appendResult(tracePoint)
        return this
    }

    protected fun TRAppendable.appendOwner(tracePoint: TRMethodCallTracePoint, methodDescriptor: MethodDescriptor): TRAppendable {
        if (tracePoint.obj != null) {
            appendObject(tracePoint.obj)
        }
        else {
            appendClassName(methodDescriptor.className.adornedClassNameRepresentation())
        }
        return this
    }

    protected fun TRAppendable.appendMethodName(methodDescriptor: MethodDescriptor): TRAppendable {
        appendMethodName(
            methodDescriptor.methodName.prettifyMethodName(),
            methodDescriptor
        )
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

    private fun String.prettifyMethodName(): String = this
        .removeCoroutinesCoreSuffix()
}

internal object DefaultTRMethodCallTracePointPrinter: AbstractTRMethodCallTracePointPrinter() {

    fun Appendable.append(tracePoint: TRMethodCallTracePoint, verbose: Boolean): Appendable {
        with(DefaultTRTextAppendable) {
            setDestination(this@append)
            append(tracePoint)
            append(tracePoint.codeLocationId, verbose)
            reset()
        }
        return this
    }
}


abstract class AbstractTRFieldTracePointPrinter {

    protected fun TRAppendable.append(tracePoint: TRFieldTracePoint): TRAppendable {
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
            appendClassName(tracePoint.fieldDescriptor.className.adornedClassNameRepresentation())
        }
        return this
    }

    protected fun TRAppendable.appendFieldName(tracePoint: TRFieldTracePoint, isLambdaCaptureSyntheticField: Boolean): TRAppendable {
        if (!isLambdaCaptureSyntheticField) {
            appendSpecialSymbol(".")
            appendFieldName(
                tracePoint.name.prettifyFieldName(),
                tracePoint.fieldDescriptor
            )
        }
        return this
    }

    private fun isLambdaCaptureSyntheticField(tracePoint: TRFieldTracePoint): Boolean {
        return tracePoint.className.startsWith("kotlin.jvm.internal.Ref$") && tracePoint.name == "element"
    }

    private fun String.prettifyFieldName(): String = this
        .removeVolatileDollarFU()
}

internal object DefaultTRFieldTracePointPrinter: AbstractTRFieldTracePointPrinter() {

    fun Appendable.append(tracePoint: TRFieldTracePoint, verbose: Boolean): Appendable {
        with(DefaultTRTextAppendable) {
            setDestination(this@append)
            append(tracePoint)
            append(tracePoint.codeLocationId, verbose)
            reset()
        }
        return this
    }
}


abstract class AbstractTRLocalVariableTracePointPrinter {

    protected fun TRAppendable.append(tracePoint: TRLocalVariableTracePoint): TRAppendable {
        val vd = tracePoint.variableDescriptor

        appendVariableName(vd.name.prettifyVariableName(), vd)
        append(" ")
        appendSpecialSymbol(tracePoint.accessSymbol())
        append(" ")
        appendObject(tracePoint.value)
        return this
    }

    private fun String.prettifyVariableName(): String = this
        .removeInlineIV()
        .removeDollarThis()
        .removeLeadingDollar()
}

internal object DefaultTRLocalVariableTracePointPrinter: AbstractTRLocalVariableTracePointPrinter() {

    fun Appendable.append(tracePoint: TRLocalVariableTracePoint, verbose: Boolean): Appendable {
        with(DefaultTRTextAppendable) {
            setDestination(this@append)
            append(tracePoint)
            append(tracePoint.codeLocationId, verbose)
            reset()
        }
        return this
    }
}

abstract class AbstractTRArrayTracePointPrinter {

    protected fun TRAppendable.append(tracePoint: TRArrayTracePoint): TRAppendable {
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

internal object DefaultTRArrayTracePointPrinter: AbstractTRArrayTracePointPrinter() {

    fun Appendable.append(tracePoint: TRArrayTracePoint, verbose: Boolean): Appendable {
        with(DefaultTRTextAppendable) {
            setDestination(this@append)
            append(tracePoint)
            append(tracePoint.codeLocationId, verbose)
            reset()
        }
        return this
    }
}

private fun <V: TRAppendable> V.append(codeLocationId: Int, verbose: Boolean): V {
    if (!verbose) return this
    val cl = CodeLocations.stackTrace(codeLocationId)
    append(" at ").append(cl.fileName).append(":").append(cl.lineNumber.toString())
    return this
}