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

internal sealed interface TRTracePointPrettifier {
    fun dump(dest: Appendable, verbose: Boolean)
}

internal class TRMethodCallTracePointPrettifier(
    val point: TRMethodCallTracePoint,
    val parameters: List<TRObject?>,
    val methodDescriptor: MethodDescriptor,
): TRTracePointPrettifier {
    var className: String = point.obj?.adornedRepresentation() ?: methodDescriptor.className.substringAfterLast(".")
    var methodName: String = methodDescriptor.methodName

    fun removeCoroutinesCoreSuffix(): TRMethodCallTracePointPrettifier {
        methodName = methodName.removeSuffix("\$kotlinx_coroutines_core")
        return this
    }

    override fun dump(dest: Appendable, verbose: Boolean) {
        dest.append(className)
            .append('.')
            .append(methodName)
            .append('(')

        parameters.forEachIndexed { i, it ->
            if (i != 0) {
                dest.append(", ")
            }
            dest.append(it.toString())
        }
        dest.append(')')

        if (point.exceptionClassName != null) {
            dest.append(": threw ")
            dest.append(point.exceptionClassName)
        } else if (point.result != TR_OBJECT_VOID) {
            dest.append(": ")
            dest.append(point.result.toString())
        }
        dest.append(point.codeLocationId, verbose)
    }
}

internal class TRFieldTracePointPrettifier(val point: TRFieldTracePoint): TRTracePointPrettifier {
    private var isLambdaCaptureSyntheticField = false
    var className: String = point.obj?.adornedRepresentation() ?: point.fieldDescriptor.className.substringAfterLast(".")
    var fieldName: String = point.name

    fun compressLambdaCaptureSyntheticField(): TRFieldTracePointPrettifier {
        if (point.className.startsWith("kotlin.jvm.internal.Ref$") && fieldName == "element") {
            className = className.removePrefix("Ref$")
            isLambdaCaptureSyntheticField = true
        }
        return this
    }

    fun removeVolatileDollar(): TRFieldTracePointPrettifier {
        fieldName = fieldName.removeSuffix("\$volatile\$FU")
        return this
    }

    override fun dump(dest: Appendable, verbose: Boolean) {
        dest.apply {
            append(className)
            if (!isLambdaCaptureSyntheticField) {
                append('.')
                append(fieldName)
            }
            append(point.directionSymbol)
            append(point.value.toString())
            append(point.codeLocationId, verbose)
        }
    }
}

internal class TRLocalVariableTracePointPrettifier(
    val point: TRLocalVariableTracePoint,
    val variableDescriptor: VariableDescriptor
): TRTracePointPrettifier {
    var varName: String = variableDescriptor.name

    fun removeInlineIV(): TRLocalVariableTracePointPrettifier {
        varName = varName.removeSuffix("\$iv")
        return this
    }

    fun removeDollarThis(): TRLocalVariableTracePointPrettifier {
        if (varName != "\$this") {
            varName = varName.removePrefix("\$this")
        }
        return this
    }

    fun removeLeadingDollar(): TRLocalVariableTracePointPrettifier {
        varName = varName.removePrefix("$")
        return this
    }

    override fun dump(dest: Appendable, verbose: Boolean) {
        dest.append(varName)
            .append(point.directionSymbol)
            .append(point.value.toString())
            .append(point.codeLocationId, verbose)
    }
}