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
import org.jetbrains.lincheck.trace.TRArrayTracePoint
import org.jetbrains.lincheck.trace.TRLoopIterationTracePoint
import org.jetbrains.lincheck.trace.TRLoopTracePoint
import org.jetbrains.lincheck.trace.TRMethodCallTracePoint
import org.jetbrains.lincheck.trace.TRNull
import org.jetbrains.lincheck.trace.TRPrimitive
import org.jetbrains.lincheck.trace.TRReadArrayTracePoint
import org.jetbrains.lincheck.trace.TRReadFieldTracePoint
import org.jetbrains.lincheck.trace.TRReadLocalVariableTracePoint
import org.jetbrains.lincheck.trace.TRTracePoint
import org.jetbrains.lincheck.trace.TRUnit
import org.jetbrains.lincheck.trace.TRValue
import org.jetbrains.lincheck.trace.TRWriteFieldTracePoint
import org.jetbrains.lincheck.trace.TRWriteLocalVariableTracePoint
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
    ): TRMethodCallTracePoint {
        val methodId = context
            .createAndRegisterMethodDescriptor(className, methodName, Types.MethodType(returnType))
            .id
        return TRMethodCallTracePoint(
            context = context,
            threadId = 0,
            codeLocationId = codeLocationId,
            methodId = methodId,
            obj = TRNull,
            parameters = emptyList(),
        ).also { it.result = TRUnit }
    }

    private fun fieldId(className: String, fieldName: String): Int = context
        .createAndRegisterFieldDescriptor(
            className, fieldName,
            type = Types.ObjectType("java.lang.String"),
            fieldKind = FieldKind.INSTANCE,
            isFinal = false,
            isVolatile = false,
        )
        .id

    fun readField(className: String, fieldName: String, value: TRValue): TRReadFieldTracePoint =
        TRReadFieldTracePoint(context, 0, UNKNOWN_CODE_LOCATION_ID, fieldId(className, fieldName), TRNull, value)

    fun writeField(className: String, fieldName: String, value: TRValue): TRWriteFieldTracePoint =
        TRWriteFieldTracePoint(context, 0, UNKNOWN_CODE_LOCATION_ID, fieldId(className, fieldName), TRNull, value)

    private fun variableId(name: String): Int = context
        .createAndRegisterVariableDescriptor(name, Types.ObjectType("java.lang.Object"))
        .id

    fun readVar(name: String): TRReadLocalVariableTracePoint =
        TRReadLocalVariableTracePoint(context, 0, UNKNOWN_CODE_LOCATION_ID, variableId(name), TRNull)

    fun writeVar(name: String): TRWriteLocalVariableTracePoint =
        TRWriteLocalVariableTracePoint(context, 0, UNKNOWN_CODE_LOCATION_ID, variableId(name), TRNull)

    fun readArray(arrayVariableName: String): TRReadArrayTracePoint {
        val variable = context.createAndRegisterVariableDescriptor(arrayVariableName, Types.ObjectType("[I"))
        val codeLocationId = context.codeLocationsPool.register(
            AccessCodeLocation(
                StackTraceElement("A", "m", "A.kt", 1),
                AccessPath(LocalVariableAccessLocation(variable)),
            )
        )
        return TRReadArrayTracePoint(context, 0, codeLocationId, TRNull, 0, TRPrimitive(1))
    }

    fun loop(loopId: Int): TRLoopTracePoint =
        TRLoopTracePoint(context, 0, UNKNOWN_CODE_LOCATION_ID, loopId)

    /** Creates the next iteration point of [loop], bumping its iteration count. */
    fun iteration(loop: TRLoopTracePoint): TRLoopIterationTracePoint =
        TRLoopIterationTracePoint(
            context, 0, UNKNOWN_CODE_LOCATION_ID, loop.loopId, loopIteration = loop.incrementIterations() + 1,
        )

    /** @return base file name of the saved trace. */
    fun save(root: Tree.Node<TRTracePoint>): String {
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
    build: TraceBuilder.() -> Tree.Node<TRTracePoint>,
    batchLoading: Boolean = false,
    test: (LazyTraceReader, LazyLoadableTraceTree<TRTracePoint>) -> Unit,
) {
    val builder = TraceBuilder()
    val path = builder.save(builder.build())
    LazyTraceReader(path).use { reader ->
        test(reader, reader.readTraceTrees(batchLoading).single())
    }
}

internal val Tree.Node<TRTracePoint>.methodName: String
    get() = (data as TRMethodCallTracePoint).methodName

/** Renders the tree as `label(child,child,...)` for compact structure assertions. */
internal fun Tree<TRTracePoint>.structure(): String = root?.structure() ?: "<empty>"

internal fun Tree.Node<TRTracePoint>.structure(): String =
    if (children.isEmpty()) label()
    else "${label()}(${children.joinToString(",") { it.structure() }})"

private fun Tree.Node<TRTracePoint>.label(): String = when (val tracePoint = data) {
    is TRMethodCallTracePoint -> tracePoint.methodName
    is TRReadFieldTracePoint -> "read(${tracePoint.name})"
    is TRWriteFieldTracePoint -> "write(${tracePoint.name})"
    is TRReadLocalVariableTracePoint -> "readVar(${tracePoint.name})"
    is TRWriteLocalVariableTracePoint -> "writeVar(${tracePoint.name})"
    is TRLoopTracePoint -> "loop[${tracePoint.iterations}]"
    is TRLoopIterationTracePoint -> "iter${tracePoint.loopIteration}"
    is TRArrayTracePoint -> "array"
    else -> tracePoint::class.simpleName!!
}
