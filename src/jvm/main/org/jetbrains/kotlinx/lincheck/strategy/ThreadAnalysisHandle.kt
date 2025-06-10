/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy

import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectLabelFactory.adornedStringRepresentation
import org.jetbrains.kotlinx.lincheck.strategy.managed.ShadowStackFrame
import org.jetbrains.kotlinx.lincheck.trace.CallStackTraceElement
import org.jetbrains.kotlinx.lincheck.trace.ReadTracePoint
import org.jetbrains.kotlinx.lincheck.trace.TraceCollector
import org.jetbrains.kotlinx.lincheck.trace.TracePoint
import org.jetbrains.kotlinx.lincheck.trace.WriteTracePoint
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.transformation.toSimpleClassName
import org.jetbrains.kotlinx.lincheck.util.AnalysisSectionType
import org.jetbrains.kotlinx.lincheck.util.findInstanceFieldReferringTo
import java.util.IdentityHashMap

@Suppress("UNUSED_PARAMETER")

/**
 * This class contains all data needed to track events for one thread.
 * It is not concurrent-safe, and each instance must be used strictly from one thread only.
 *
 * @property threadId Id of thread to use in trace, simply an increasing number.
 * @property traceCollector Collector to store all [TracePoint]-s generated for this thread.
 */
// TODO: unify with ThreadScheduler.ThreadData
// TODO: better name?
internal class ThreadAnalysisHandle(val threadId: Int, val traceCollector: TraceCollector?) {
    /**
     * Current tracked stacktrace.
     */
    // TODO: Unify with shadowStack
    // TODO: do not expose mutable list
    val stackTrace: MutableList<CallStackTraceElement> = arrayListOf()

    /**
     * Shadow stack reflecting the program's actual stack.
     */
    // TODO: do not expose mutable list
    val shadowStack: MutableList<ShadowStackFrame> = arrayListOf()

    /**
     * Stack of entered analysis section types.
     */
    // TODO: unify with `shadowStack`
    // TODO: handle coroutine resumptions (i.e., unify with `suspendedFunctionsStack`)
    // TODO: do not expose mutable list
    val analysisSectionStack: MutableList<AnalysisSectionType> = arrayListOf()

    /**
     * Tracks content of constants (i.e., static final fields).
     * Stores a map `object -> fieldName`,
     * mapping an object to a constant name referencing this object.
     */
    // TODO: do not expose mutable map
    val constants: IdentityHashMap<Any, String> = IdentityHashMap<Any, String>()

    /**
     * Last read constant name (i.e., static final field).
     * We store it as we initialize read value after the trace point is created,
     * so we have to store the trace point somewhere to get it later.
     */
    var lastReadConstantName: String? = null
        private set

    /**
     * Last read trace point, occurred in the current thread.
     * We store it as we initialize read value after the point is created,
     * so we have to store the trace point somewhere to get it later.
     */
    var lastReadTracePoint: ReadTracePoint? = null
        private set

    private val collectTrace = (traceCollector != null)

    fun beforeReadField(
        obj: Any?,
        className: String,
        fieldName: String,
        codeLocation: Int,
        isStatic: Boolean,
        isFinal: Boolean
    ) {
        // We need to ensure all the classes related to the reading object are instrumented.
        // The following call checks all the static fields.
        if (isStatic) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
        }
        if (collectTrace && isStatic && isFinal) {
            lastReadConstantName = fieldName
        }
    }

    fun afterRead(value: Any?) {
        if (!collectTrace) return
        if (lastReadConstantName != null && value != null) {
            constants[value] = lastReadConstantName
        }
        val valueRepresentation = adornedStringRepresentation(value)
        val typeRepresentation = objectFqTypeName(value)
        lastReadTracePoint?.initializeReadValue(valueRepresentation, typeRepresentation)
        lastReadTracePoint = null
        lastReadConstantName = null
    }

    fun afterLocalRead(variableName: String, value: Any?) {
        if (!collectTrace) return
        val shadowStackFrame = shadowStack.last()
        shadowStackFrame.setLocalVariable(variableName, value)
        // TODO: enable local vars tracking in the trace after further polishing
        // TODO: add a flag to enable local vars tracking in the trace conditionally
        // val tracePoint = if (collectTrace) {
        //     ReadTracePoint(
        //         ownerRepresentation = null,
        //         iThread = iThread,
        //         actorId = currentActorId[iThread]!!,
        //         callStackTrace = callStackTrace[iThread]!!,
        //         fieldName = variableName,
        //         codeLocation = codeLocation,
        //         isLocal = true,
        //     ).also { it.initializeReadValue(adornedStringRepresentation(value), objectFqTypeName(value)) }
        // } else {
        //     null
        // }
        // traceCollector!!.passCodeLocation(tracePoint)
    }

    fun afterLocalWrite(variableName: String, value: Any?) {
        if (!collectTrace) return
        val shadowStackFrame = shadowStack.last()
        shadowStackFrame.setLocalVariable(variableName, value)
        // TODO: enable local vars tracking in the trace after further polishing
        // TODO: add a flag to enable local vars tracking in the trace conditionally
        // val tracePoint = if (collectTrace) {
        //     WriteTracePoint(
        //         ownerRepresentation = null,
        //         iThread = iThread,
        //         actorId = currentActorId[iThread]!!,
        //         callStackTrace = callStackTrace[iThread]!!,
        //         fieldName = variableName,
        //         codeLocation = codeLocation,
        //         isLocal = true,
        //     ).also { it.initializeWrittenValue(adornedStringRepresentation(value), objectFqTypeName(value)) }
        // } else {
        //     null
        // }
        // traceCollector!!.passCodeLocation(tracePoint)
    }

    fun createReadFieldTracePoint(
        obj: Any?,
        className: String,
        fieldName: String,
        codeLocation: Int,
        isStatic: Boolean,
        isFinal: Boolean,
        actorId: Int = 0,
    ) : ReadTracePoint? = if (!collectTrace) null else ReadTracePoint(
        ownerRepresentation = getFieldOwnerName(obj, className, fieldName, isStatic),
        iThread = threadId,
        actorId = actorId,
        callStackTrace = stackTrace,
        fieldName = fieldName,
        codeLocation = codeLocation,
        isLocal = false,
    ).apply {
        lastReadTracePoint = this
    }

    fun createReadArrayElementTracePoint(
        array: Any,
        index: Int,
        codeLocation: Int,
        actorId: Int = 0,
    ) : ReadTracePoint? = if (!collectTrace) null else ReadTracePoint(
        ownerRepresentation = null,
        iThread = threadId,
        actorId = actorId,
        callStackTrace = stackTrace,
        fieldName = "${adornedStringRepresentation(array)}[$index]",
        codeLocation = codeLocation,
        isLocal = false,
    ).apply {
        lastReadTracePoint = this
    }

    fun createWriteArrayElementTracePoint(
        array: Any,
        index: Int,
        value: Any?,
        codeLocation: Int,
        actorId: Int = 0,
    ) : WriteTracePoint? = if (!collectTrace) null else WriteTracePoint(
        ownerRepresentation = null,
        iThread = threadId,
        actorId = actorId,
        callStackTrace = stackTrace,
        fieldName = "${adornedStringRepresentation(array)}[$index]",
        codeLocation = codeLocation,
        isLocal = false,
    ).apply {
        initializeWrittenValue(adornedStringRepresentation(value), objectFqTypeName(value))
    }

    fun createWriteFieldTracepoint(
        obj: Any?,
        className: String,
        fieldName: String,
        value: Any?,
        codeLocation: Int,
        isStatic: Boolean,
        actorId: Int = 0,
    ): WriteTracePoint? = if (!collectTrace) null else WriteTracePoint(
        ownerRepresentation = getFieldOwnerName(obj, className, fieldName, isStatic),
        iThread = threadId,
        actorId = actorId,
        callStackTrace = stackTrace,
        fieldName = fieldName,
        codeLocation = codeLocation,
        isLocal = false
    ).apply {
        initializeWrittenValue(adornedStringRepresentation(value), objectFqTypeName(value))
    }

    private fun objectFqTypeName(obj: Any?): String {
        val typeName = obj?.javaClass?.name ?: "null"
        // Note: `if` here is important for performance reasons.
        // In common case we want to return just `typeName` without using string templates
        // to avoid redundant string allocation.
        if (obj?.javaClass?.isEnum == true) {
            return "Enum:$typeName"
        }
        return typeName
    }

    /**
     * Returns string representation of the field owner based on the provided parameters.
     */
    private fun getFieldOwnerName(obj: Any?, className: String, fieldName: String, isStatic: Boolean): String? {
        if (isStatic) {
            val stackTraceElement = shadowStack.last()
            if (stackTraceElement.instance?.javaClass?.name == className) {
                return null
            }
            return className.toSimpleClassName()
        }
        return findOwnerName(obj!!)
    }

    /**
     * Returns beautiful string representation of the [owner].
     * If the [owner] is `this` of the current method, then returns `null`.
     */
    private fun findOwnerName(owner: Any): String? {
        // if the current owner is `this` - no owner needed
        if (isCurrentStackFrameReceiver(owner)) return null
        // do not prettify thread names
        if (owner is Thread) {
            return adornedStringRepresentation(owner)
        }
        // lookup for the object in local variables and use the local variable name if found
        val shadowStackFrame = shadowStack.last()
        shadowStackFrame.getLastAccessVariable(owner)?.let { return it }
        // lookup for a field name in the current stack frame `this`
        shadowStackFrame.instance
            ?.findInstanceFieldReferringTo(owner)
            ?.let { return it.name }
        // lookup for the constant referencing the object
        constants[owner]?.let { return it }
        // otherwise return object's string representation
        return adornedStringRepresentation(owner)
    }

    /**
     * Checks if [owner] is the `this` object (i.e., receiver) of the currently executed method call.
     */
    private fun isCurrentStackFrameReceiver(owner: Any): Boolean {
        val stackTraceElement = shadowStack.last()
        return (owner === stackTraceElement.instance)
    }

}