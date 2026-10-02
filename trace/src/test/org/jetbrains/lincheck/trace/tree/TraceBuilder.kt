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
import org.jetbrains.lincheck.trace.ArrayTracePoint
import org.jetbrains.lincheck.trace.LoopIterationTracePoint
import org.jetbrains.lincheck.trace.LoopTracePoint
import org.jetbrains.lincheck.trace.MethodCallTracePoint
import org.jetbrains.lincheck.trace.TraceNull
import org.jetbrains.lincheck.trace.TraceScalar
import org.jetbrains.lincheck.trace.ReadArrayTracePoint
import org.jetbrains.lincheck.trace.ReadFieldTracePoint
import org.jetbrains.lincheck.trace.ReadLocalVariableTracePoint
import org.jetbrains.lincheck.trace.TracePoint
import org.jetbrains.lincheck.trace.TraceUnit
import org.jetbrains.lincheck.trace.TraceValue
import org.jetbrains.lincheck.trace.WriteFieldTracePoint
import org.jetbrains.lincheck.trace.WriteLocalVariableTracePoint
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.UNKNOWN_CODE_LOCATION_ID
import org.jetbrains.lincheck.trace.createAndRegisterFieldDescriptor
import org.jetbrains.lincheck.trace.createAndRegisterMethodDescriptor
import org.jetbrains.lincheck.trace.createAndRegisterVariableDescriptor
import org.jetbrains.lincheck.trace.iterationsAsString
import org.jetbrains.lincheck.trace.serialization.INDEX_FILENAME_EXT
import org.jetbrains.lincheck.trace.serialization.LazyTraceReader
import org.jetbrains.lincheck.trace.serialization.saveRecorderTrace
import org.jetbrains.lincheck.util.tree.Tree
import java.io.File
import java.util.IdentityHashMap

/**
 * Builds hand-crafted single-thread traces for tree tests
 * and saves them in the trace-recorder binary format.
 */
internal class TraceBuilder {
    val context = TraceContext()
    private val loopIterations = IdentityHashMap<LoopTracePoint, Int>()

    fun codeLocation(line: Int): Int = context.codeLocationsPool.register(
        MethodCallCodeLocation(StackTraceElement("A", "m", "A.kt", line), accessPath = null, argumentNames = null)
    )

    fun call(
        className: String,
        methodName: String,
        returnType: Types.Type = Types.VOID_TYPE,
        codeLocationId: Int = UNKNOWN_CODE_LOCATION_ID,
    ): MethodCallTracePoint {
        val methodId = context
            .createAndRegisterMethodDescriptor(className, methodName, Types.MethodType(returnType))
            .id
        return MethodCallTracePoint(
            context = context,
            threadId = 0,
            codeLocationId = codeLocationId,
            methodId = methodId,
            obj = TraceNull,
            parameters = emptyList(),
        ).also { it.setResult(TraceUnit) }
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
    ): ReadFieldTracePoint =
        ReadFieldTracePoint(
            context, 0, UNKNOWN_CODE_LOCATION_ID, fieldId(className, fieldName, type), TraceNull, value,
        )

    fun writeField(className: String, fieldName: String, value: TraceValue): WriteFieldTracePoint =
        WriteFieldTracePoint(
            context, 0, UNKNOWN_CODE_LOCATION_ID,
            fieldId(className, fieldName, Types.ObjectType("java.lang.String")), TraceNull, value,
        )

    private fun variableId(name: String): Int = context
        .createAndRegisterVariableDescriptor(name, Types.ObjectType("java.lang.Object"))
        .id

    fun readVar(name: String): ReadLocalVariableTracePoint =
        ReadLocalVariableTracePoint(context, 0, UNKNOWN_CODE_LOCATION_ID, variableId(name), TraceNull)

    fun writeVar(name: String): WriteLocalVariableTracePoint =
        WriteLocalVariableTracePoint(context, 0, UNKNOWN_CODE_LOCATION_ID, variableId(name), TraceNull)

    fun readArray(arrayVariableName: String): ReadArrayTracePoint {
        val variable = context.createAndRegisterVariableDescriptor(arrayVariableName, Types.ObjectType("[I"))
        val codeLocationId = context.codeLocationsPool.register(
            AccessCodeLocation(
                StackTraceElement("A", "m", "A.kt", 1),
                AccessPath(LocalVariableAccessLocation(variable)),
            )
        )
        return ReadArrayTracePoint(context, 0, codeLocationId, TraceNull, 0, TraceScalar(1))
    }

    fun loop(loopId: Int): LoopTracePoint =
        LoopTracePoint(context, 0, UNKNOWN_CODE_LOCATION_ID, loopId)

    /** Creates the next iteration point of [loop]. */
    fun iteration(loop: LoopTracePoint): LoopIterationTracePoint =
        LoopIterationTracePoint(
            context, 0, UNKNOWN_CODE_LOCATION_ID, loop.loopId,
            loopIteration = loopIterations.merge(loop, 1, Int::plus)!!,
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
 * @param dropIndex delete the index file before reading,
 *   forcing the reader to rebuild the context by scanning the whole data file.
 */
internal fun withTraceTree(
    build: TraceBuilder.() -> Tree.Node<TracePoint>,
    batchLoading: Boolean = false,
    dropIndex: Boolean = false,
    test: (LazyTraceReader, LazyLoadableTraceTree<TracePoint>) -> Unit,
) {
    val builder = TraceBuilder()
    val path = builder.save(builder.build())
    if (dropIndex) {
        check(File("$path.$INDEX_FILENAME_EXT").delete()) { "Cannot delete the index of $path" }
    }
    LazyTraceReader(path).use { reader ->
        test(reader, reader.readTraceTrees(batchLoading).single())
    }
}

internal val Tree.Node<TracePoint>.methodName: String
    get() = (data as MethodCallTracePoint).methodName

/** Renders the tree as `label(child,child,...)` for compact structure assertions. */
internal fun Tree<TracePoint>.structure(): String = root?.structure() ?: "<empty>"

internal fun Tree.Node<TracePoint>.structure(): String =
    if (children.isEmpty()) label()
    else "${label()}(${children.joinToString(",") { it.structure() }})"

private fun Tree.Node<TracePoint>.label(): String = when (val tracePoint = data) {
    is MethodCallTracePoint -> tracePoint.methodName
    is ReadFieldTracePoint -> "read(${tracePoint.name})"
    is WriteFieldTracePoint -> "write(${tracePoint.name})"
    is ReadLocalVariableTracePoint -> "readVar(${tracePoint.name})"
    is WriteLocalVariableTracePoint -> "writeVar(${tracePoint.name})"
    is LoopTracePoint -> "loop[${tracePoint.iterationsAsString}]"
    is LoopIterationTracePoint -> "iter${tracePoint.loopIteration}"
    is ArrayTracePoint -> "array"
    else -> tracePoint::class.simpleName!!
}
