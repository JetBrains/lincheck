/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.tracedata

import java.io.*

private const val INPUT_BUFFER_SIZE: Int = 16*1024*1024

fun loadRecordedTrace(inp: InputStream): Pair<TraceContext, List<TRTracePoint>> {
    DataInputStream(inp.buffered(INPUT_BUFFER_SIZE)).use { input ->
        val magic = input.readLong()
        if (magic != TRACE_MAGIC) {
            error("Wrong magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}")
        }

        val version = input.readLong()
        if (version != TRACE_VERSION) {
            error("Wrong version $version (expected $TRACE_VERSION)")
        }

        val context = TRACE_CONTEXT // TraceContext()
        val roots = mutableListOf<TRTracePoint>()
        val stringCache = mutableListOf<String?>()
        // Load objects
        val eof = loadObjectsEagerly(input, context, stringCache, roots)
        check(eof == ObjectKind.EOF) {
            "Input contains unbalanced method call tracepoint: footer without main data"
        }

        return context to roots
    }
}

private fun loadObjectsEagerly(input: DataInput, context: TraceContext, stringCache: MutableList<String?>, tracepoints: MutableList<TRTracePoint>): ObjectKind {
    while (true) {
        val kind = input.readKind()
        when (kind) {
            ObjectKind.CLASS_DESCRIPTOR -> loadClassDescriptor(input, context)
            ObjectKind.METHOD_DESCRIPTOR -> loadMethodDescriptor(input, context)
            ObjectKind.FIELD_DESCRIPTOR -> loadFieldDescriptor(input, context)
            ObjectKind.VARIABLE_DESCRIPTOR -> loadVariableDescriptor(input, context)
            ObjectKind.STRING -> loadString(input, stringCache)
            ObjectKind.CODE_LOCATION -> loadCodeLocation(input, context, stringCache)
            ObjectKind.TRACEPOINT -> tracepoints.add(loadTracePointEagerly(input, context, stringCache))
            ObjectKind.TRACEPOINT_FOOTER -> return kind
            ObjectKind.EOF -> return kind
        }
    }
}

private fun loadClassDescriptor(
    input: DataInput,
    context: TraceContext
) {
    val id = input.readInt()
    val descriptor = input.readClassDescriptor()
    context.restoreClassDescriptor(id,descriptor)
}

private fun loadMethodDescriptor(
    input: DataInput,
    context: TraceContext
) {
    val id = input.readInt()
    val descriptor = input.readMethodDescriptor(context)
    context.restoreMethodDescriptor(id,descriptor)
}

private fun loadFieldDescriptor(
    input: DataInput,
    context: TraceContext
) {
    val id = input.readInt()
    val descriptor = input.readFieldDescriptor(context)
    context.restoreFieldDescriptor(id,descriptor)
}

private fun loadVariableDescriptor(
    input: DataInput,
    context: TraceContext
) {
    val id = input.readInt()
    val descriptor = input.readVariableDescriptor()
    context.restoreVariableDescriptor(id,descriptor)
}

private fun loadString(
    input: DataInput,
    stringCache: MutableList<String?>
) {
    val id = input.readInt()
    val value = input.readUTF()
    while (stringCache.size <= id) {
        stringCache.add(null)
    }
    stringCache[id] = value
}

private fun loadCodeLocation(
    input: DataInput,
    context: TraceContext,
    stringCache: MutableList<String?>
) {
    val id = input.readInt()

    val fileNameId = input.readInt()
    val classNameId = input.readInt()
    val methodNameId = input.readInt()
    val lineNumber = input.readInt()

    val ste = StackTraceElement(
        /* declaringClass = */ stringCache[classNameId],
        /* methodName = */ stringCache[methodNameId],
        /* fileName = */ stringCache[fileNameId],
        /* lineNumber = */ lineNumber
    )
    context.restoreCodeLocation(id, ste)
}

private fun loadTracePointEagerly(input: DataInput, context: TraceContext, stringCache: MutableList<String?>): TRTracePoint {
    val tracePoint = loadTRTracePoint(input)
    if (tracePoint !is TRMethodCallTracePoint) return tracePoint
    // Load all children
    val kind = loadObjectsEagerly(input, context, stringCache, tracePoint.events)
    check(kind == ObjectKind.TRACEPOINT_FOOTER) {
        "Input contains unbalanced method call tracepoint: tracepoint without footer"
    }
    tracePoint.loadFooter(input)
    return tracePoint
}

