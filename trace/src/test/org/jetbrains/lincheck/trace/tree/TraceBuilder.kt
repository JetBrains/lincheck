/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.tree

import org.jetbrains.lincheck.descriptors.AccessCodeLocation
import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.descriptors.FieldKind
import org.jetbrains.lincheck.descriptors.LocalVariableAccessLocation
import org.jetbrains.lincheck.descriptors.MethodCallCodeLocation
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.trace.TraceArrayTracePoint
import org.jetbrains.lincheck.trace.TraceLoopIterationTracePoint
import org.jetbrains.lincheck.trace.TraceLoopTracePoint
import org.jetbrains.lincheck.trace.TraceMethodCallTracePoint
import org.jetbrains.lincheck.trace.TraceNull
import org.jetbrains.lincheck.trace.TraceScalar
import org.jetbrains.lincheck.trace.TraceReadArrayTracePoint
import org.jetbrains.lincheck.trace.TraceReadFieldTracePoint
import org.jetbrains.lincheck.trace.TraceReadLocalVariableTracePoint
import org.jetbrains.lincheck.trace.TracePoint
import org.jetbrains.lincheck.trace.TraceUnit
import org.jetbrains.lincheck.trace.TraceValue
import org.jetbrains.lincheck.trace.TraceWriteFieldTracePoint
import org.jetbrains.lincheck.trace.TraceWriteLocalVariableTracePoint
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.UNKNOWN_CODE_LOCATION_ID
import org.jetbrains.lincheck.trace.createAndRegisterFieldDescriptor
import org.jetbrains.lincheck.trace.createAndRegisterMethodDescriptor
import org.jetbrains.lincheck.trace.createAndRegisterVariableDescriptor
import org.jetbrains.lincheck.trace.serialization.INDEX_FILENAME_EXT
import org.jetbrains.lincheck.trace.serialization.LazyTraceReader
import org.jetbrains.lincheck.trace.serialization.saveRecorderTrace
import org.jetbrains.lincheck.util.tree.TreeRewriteRule
import org.jetbrains.lincheck.util.tree.Tree
import java.io.File

/**
 * Builds hand-crafted single-thread traces for tree tests
 * and saves them in the trace-recorder binary format.
 */
internal class TraceBuilder {
    val context = TraceContext()

    fun codeLocation(line: Int): Int = context.codeLocationsPool.register(
        MethodCallCodeLocation(StackTraceElement("A", "m", "A.kt", line), accessPath = null, argumentNames = null)
    )

    fun call(
        className: String,
        methodName: String,
        returnType: Types.Type = Types.VOID_TYPE,
        codeLocationId: Int = UNKNOWN_CODE_LOCATION_ID,
    ): TraceMethodCallTracePoint {
        val methodId = context
            .createAndRegisterMethodDescriptor(className, methodName, Types.MethodType(returnType))
            .id
        return TraceMethodCallTracePoint(
            context = context,
            threadId = 0,
            codeLocationId = codeLocationId,
            methodId = methodId,
            obj = TraceNull,
            parameters = emptyList(),
        ).also { it.result = TraceUnit }
    }

    private fun fieldId(className: String, fieldName: String, type: Types.Type): Int = context
        .createAndRegisterFieldDescriptor(
            className, fieldName,
            type = type,
            fieldKind = FieldKind.INSTANCE,
            isFinal = false,
            isVolatile = false,
        )
        .id

    /** @param type the field's *declared* type, which [value] may be narrower than. */
    fun readField(
        className: String,
        fieldName: String,
        value: TraceValue,
        type: Types.Type = Types.ObjectType("java.lang.String"),
    ): TraceReadFieldTracePoint =
        TraceReadFieldTracePoint(
            context, 0, UNKNOWN_CODE_LOCATION_ID, fieldId(className, fieldName, type), TraceNull, value,
        )

    fun writeField(className: String, fieldName: String, value: TraceValue): TraceWriteFieldTracePoint =
        TraceWriteFieldTracePoint(
            context, 0, UNKNOWN_CODE_LOCATION_ID,
            fieldId(className, fieldName, Types.ObjectType("java.lang.String")), TraceNull, value,
        )

    private fun variableId(name: String): Int = context
        .createAndRegisterVariableDescriptor(name, Types.ObjectType("java.lang.Object"))
        .id

    fun readVar(name: String): TraceReadLocalVariableTracePoint =
        TraceReadLocalVariableTracePoint(context, 0, UNKNOWN_CODE_LOCATION_ID, variableId(name), TraceNull)

    fun writeVar(name: String): TraceWriteLocalVariableTracePoint =
        TraceWriteLocalVariableTracePoint(context, 0, UNKNOWN_CODE_LOCATION_ID, variableId(name), TraceNull)

    fun readArray(arrayVariableName: String): TraceReadArrayTracePoint {
        val variable = context.createAndRegisterVariableDescriptor(arrayVariableName, Types.ObjectType("[I"))
        val codeLocationId = context.codeLocationsPool.register(
            AccessCodeLocation(
                StackTraceElement("A", "m", "A.kt", 1),
                AccessPath(LocalVariableAccessLocation(variable)),
            )
        )
        return TraceReadArrayTracePoint(context, 0, codeLocationId, TraceNull, 0, TraceScalar(1))
    }

    fun loop(loopId: Int): TraceLoopTracePoint =
        TraceLoopTracePoint(context, 0, UNKNOWN_CODE_LOCATION_ID, loopId)

    /** Creates the next iteration point of [loop], bumping its iteration count. */
    fun iteration(loop: TraceLoopTracePoint): TraceLoopIterationTracePoint =
        TraceLoopIterationTracePoint(
            context, 0, UNKNOWN_CODE_LOCATION_ID, loop.loopId, loopIteration = loop.incrementIterations() + 1,
        )

    /** @return base file name of the saved trace. */
    fun save(root: Tree.Node<TracePoint>): String {
        val dataFile = File.createTempFile("trace-tree-test", ".bin").also { it.deleteOnExit() }
        File("${dataFile.path}.$INDEX_FILENAME_EXT").deleteOnExit()
        saveRecorderTrace(dataFile.path, context, listOf(Tree(root)))
        return dataFile.path
    }
}

/**
 * Builds a trace tree with [build], saves it,
 * and runs [test] over the lazily loaded tree read back from disk.
 *
 * @param batchLoading see [LazyLoadableTraceTree].
 */
internal fun withTraceTree(
    build: TraceBuilder.() -> Tree.Node<TracePoint>,
    batchLoading: Boolean = false,
    test: (LazyTraceReader, LazyLoadableTraceTree<TracePoint>) -> Unit,
) {
    val builder = TraceBuilder()
    val path = builder.save(builder.build())
    LazyTraceReader(path).use { reader ->
        test(reader, reader.readTraceTrees(batchLoading).single())
    }
}

internal val Tree.Node<TracePoint>.methodName: String
    get() = (data as TraceMethodCallTracePoint).methodName

/** Renders the tree as `label(child,child,...)` for compact structure assertions. */
internal fun Tree<TracePoint>.structure(): String = root?.structure() ?: "<empty>"

internal fun Tree.Node<TracePoint>.structure(): String =
    if (children.isEmpty()) label()
    else "${label()}(${children.joinToString(",") { it.structure() }})"

private fun Tree.Node<TracePoint>.label(): String = when (val tracePoint = data) {
    is TraceMethodCallTracePoint -> tracePoint.methodName
    is TraceReadFieldTracePoint -> "read(${tracePoint.name})"
    is TraceWriteFieldTracePoint -> "write(${tracePoint.name})"
    is TraceReadLocalVariableTracePoint -> "readVar(${tracePoint.name})"
    is TraceWriteLocalVariableTracePoint -> "writeVar(${tracePoint.name})"
    is TraceLoopTracePoint -> "loop[${tracePoint.iterations}]"
    is TraceLoopIterationTracePoint -> "iter${tracePoint.loopIteration}"
    is TraceArrayTracePoint -> "array"
    else -> tracePoint::class.simpleName!!
}
