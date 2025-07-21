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

import org.jetbrains.lincheck.descriptors.MethodDescriptor
import org.jetbrains.lincheck.descriptors.VariableDescriptor
import org.jetbrains.lincheck.trace.append


internal interface TRMethodCallTracePointPrinter {
    fun print(dest: Appendable, tracePoint: TRMethodCallTracePoint, verbose: Boolean, methodDescriptor: MethodDescriptor)
}

internal abstract class AbstractTRMethodCallTracePointPrinter: TRMethodCallTracePointPrinter {

    override fun print(dest: Appendable, tracePoint: TRMethodCallTracePoint, verbose: Boolean, methodDescriptor: MethodDescriptor) =
        dest.append(tracePoint, verbose, methodDescriptor)

    fun Appendable.append(tracePoint: TRMethodCallTracePoint, verbose: Boolean, methodDescriptor: MethodDescriptor) {
        appendClassName(tracePoint, methodDescriptor)
        append('.')
        appendMethodName(tracePoint, methodDescriptor)
        append('(')
        appendParameters(tracePoint)
        append(')')
        appendResult(tracePoint)
        append(tracePoint.codeLocationId, verbose)
    }

    protected abstract fun Appendable.appendClassName(tracePoint: TRMethodCallTracePoint, methodDescriptor: MethodDescriptor)
    protected abstract fun Appendable.appendMethodName(tracePoint: TRMethodCallTracePoint, methodDescriptor: MethodDescriptor)
    protected abstract fun Appendable.appendParameters(tracePoint: TRMethodCallTracePoint)
    protected abstract fun Appendable.appendResult(tracePoint: TRMethodCallTracePoint)
}

internal class DefaultTRMethodCallTracePointPrinter: AbstractTRMethodCallTracePointPrinter() {

    override fun Appendable.appendClassName(tracePoint: TRMethodCallTracePoint, methodDescriptor: MethodDescriptor) {
        val className = tracePoint.obj?.adornedRepresentation() ?: methodDescriptor.className.substringAfterLast(".")
        append(className)
    }

    override fun Appendable.appendMethodName(tracePoint: TRMethodCallTracePoint, methodDescriptor: MethodDescriptor) {
        append(methodDescriptor.methodName.removeCoroutinesCoreSuffix())
    }

    override fun Appendable.appendParameters(tracePoint: TRMethodCallTracePoint) {
        tracePoint.parameters.forEachIndexed { i, it ->
            if (i != 0) {
                append(", ")
            }
            append(it.toString())
        }
    }

    override fun Appendable.appendResult(tracePoint: TRMethodCallTracePoint) {
        if (tracePoint.exceptionClassName != null) {
            append(": threw ")
            append(tracePoint.exceptionClassName)
        } else if (tracePoint.result != TR_OBJECT_VOID) {
            append(": ")
            append(tracePoint.result.toString())
        }
    }

    private fun String.removeCoroutinesCoreSuffix(): String = removeSuffix("\$kotlinx_coroutines_core")
}


internal interface TRFieldTracePointPrinter {
    fun print(dest: Appendable, tracePoint: TRFieldTracePoint, verbose: Boolean)
}

internal abstract class AbstractTRFieldTracePointPrinter: TRFieldTracePointPrinter {

    override fun print(dest: Appendable, tracePoint: TRFieldTracePoint, verbose: Boolean) =
        dest.append(tracePoint, verbose)

    fun Appendable.append(tracePoint: TRFieldTracePoint, verbose: Boolean) {
        val isLambdaCaptureSyntheticField = isLambdaCaptureSyntheticField(tracePoint)

        appendClassName(tracePoint, isLambdaCaptureSyntheticField)
        appendFieldName(tracePoint, isLambdaCaptureSyntheticField)
        append(tracePoint.directionSymbol)
        append(tracePoint.value.toString())
        append(tracePoint.codeLocationId, verbose)
    }

    protected abstract fun Appendable.appendClassName(tracePoint: TRFieldTracePoint, isLambdaCaptureSyntheticField: Boolean)
    protected abstract fun Appendable.appendFieldName(tracePoint: TRFieldTracePoint, isLambdaCaptureSyntheticField: Boolean)

    private fun isLambdaCaptureSyntheticField(tracePoint: TRFieldTracePoint): Boolean {
        return tracePoint.className.startsWith("kotlin.jvm.internal.Ref$") && tracePoint.name == "element"
    }
}

internal class DefaultTRFieldTracePointPrinter: AbstractTRFieldTracePointPrinter() {

    override fun Appendable.appendClassName(tracePoint: TRFieldTracePoint, isLambdaCaptureSyntheticField: Boolean) {
        val className = tracePoint.obj?.adornedRepresentation() ?: tracePoint.fieldDescriptor.className.substringAfterLast(".")
        if (!isLambdaCaptureSyntheticField) {
            append(className.removePrefix("Ref$"))
        }
        else {
            append(className)
        }
    }

    override fun Appendable.appendFieldName(tracePoint: TRFieldTracePoint, isLambdaCaptureSyntheticField: Boolean) {
        if (!isLambdaCaptureSyntheticField) {
            append('.')
            append(tracePoint.name.removeVolatileDollar())
        }
    }

    private fun String.removeVolatileDollar(): String = removeSuffix("\$volatile\$FU")
}


internal interface TRLocalVariableTracePointPrinter {
    fun print(dest: Appendable, tracePoint: TRLocalVariableTracePoint, verbose: Boolean, variableDescriptor: VariableDescriptor)
}

internal abstract class AbstractTRLocalVariableTracePointPrinter: TRLocalVariableTracePointPrinter {

    override fun print(dest: Appendable, tracePoint: TRLocalVariableTracePoint, verbose: Boolean, variableDescriptor: VariableDescriptor) =
        dest.append(tracePoint, verbose, variableDescriptor)

    fun Appendable.append(tracePoint: TRLocalVariableTracePoint, verbose: Boolean, variableDescriptor: VariableDescriptor) {
        appendVariableName(variableDescriptor.name)
        append(tracePoint.directionSymbol)
        append(tracePoint.value.toString())
        append(tracePoint.codeLocationId, verbose)
    }

    protected abstract fun Appendable.appendVariableName(fieldName: String)
}

internal class DefaultTRLocalVariableTracePointPrinter: AbstractTRLocalVariableTracePointPrinter() {

    override fun Appendable.appendVariableName(fieldName: String) {
        append(fieldName.removeInlineIV().removeDollarThis().removeLeadingDollar())
    }

    private fun String.removeInlineIV(): String = removeSuffix("\$iv")

    private fun String.removeDollarThis(): String = if (this == "\$this") this else removePrefix("\$this")

    private fun String.removeLeadingDollar(): String = removePrefix("$")
}


internal interface TRArrayTracePointPrinter {
    fun print(dest: StringBuilder, tracePoint: TRArrayTracePoint, verbose: Boolean)
}

internal abstract class AbstractTRArrayTracePointPrinter: TRArrayTracePointPrinter {

    override fun print(dest: StringBuilder, tracePoint: TRArrayTracePoint, verbose: Boolean) {
        dest.append(tracePoint, verbose)
    }

    fun StringBuilder.append(tracePoint: TRArrayTracePoint, verbose: Boolean) {
        append(tracePoint.array)
        append('[')
        append(tracePoint.index)
        append(']')
        append(tracePoint.directionSymbol)
        append(tracePoint.value.toString())
        append(tracePoint.codeLocationId, verbose)
    }
}

internal class DefaultTRArrayTracePointPrinter: AbstractTRArrayTracePointPrinter()