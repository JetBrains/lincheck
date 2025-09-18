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

import org.jetbrains.lincheck.descriptors.*


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

fun TRAppendable.appendAccessPath(accessPath: AccessPath) {
    for (i in accessPath.locations.indices) {
        val location = accessPath.locations[i]
        val nextLocation = accessPath.locations.getOrNull(i + 1)

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
        appendMethodName(md)
        appendSpecialSymbol("(")
        appendParameters(tracePoint)
        appendSpecialSymbol(")")
        appendResult(tracePoint)
        return this
    }

    protected fun TRAppendable.appendOwner(tracePoint: TRMethodCallTracePoint): TRAppendable {
        if (!tracePoint.isInitialTestMethod() && tracePoint.isStatic()) {
            return this
        }
        val ownerName = CodeLocations.accessPath(tracePoint.codeLocationId)
        if (ownerName != null) {
            ownerName.filterThisAccesses().takeIf { !it.isEmpty() }?.let {
                appendAccessPath(it)
                appendSpecialSymbol(".")
            }
        } else if (tracePoint.obj != null) {
            appendObject(tracePoint.obj)
            appendSpecialSymbol(".")
        } else {
            appendClassName(tracePoint.classDescriptor)
            appendSpecialSymbol(".")
        }
        return this
    }

    protected fun TRAppendable.appendParameters(tracePoint: TRMethodCallTracePoint): TRAppendable {
        // Due to trace compression codelocation can be shifted and therefore argument names do not match
        // Without having paramter names it is impossible to match up
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
                parameter?.isPrimitive == true -> {
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

    protected fun TRAppendable.appendResult(tracePoint: TRMethodCallTracePoint): TRAppendable {
        if (tracePoint.exceptionClassName != null) {
            append(": ")
            appendKeyword("threw")
            append(" ")
            append(tracePoint.exceptionClassName)
        } else if (tracePoint.isMethodUnfinished()) {
            append(": ")
            appendSpecialSymbol(UNFINISHED_METHOD_RESULT_SYMBOL)
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
        val ownerName = CodeLocations.accessPath(tracePoint.codeLocationId)
        if (ownerName != null) {
            ownerName.filterThisAccesses().takeIf { !it.isEmpty() }?.let {
                appendAccessPath(it)
                appendSpecialSymbol(".")
            }
        } else if (tracePoint.obj != null) {
            appendObject(tracePoint.obj)
            appendSpecialSymbol(".")
        } else {
            appendClassName(tracePoint.classDescriptor)
            appendSpecialSymbol(".")
        }
        return this
    }

    protected fun TRAppendable.appendFieldName(tracePoint: TRFieldTracePoint, isLambdaCaptureSyntheticField: Boolean): TRAppendable {
        if (!isLambdaCaptureSyntheticField) {
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
    protected fun TRAppendable.appendOwner(tracePoint: TRArrayTracePoint): TRAppendable {
        val ownerName = CodeLocations.accessPath(tracePoint.codeLocationId)
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