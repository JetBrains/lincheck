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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

const val OUTPUT_BUFFER_SIZE: Int = 16*1024*1024
const val TRACE_MAGIC : Long = 0x706e547124ee5f70L
const val TRACE_VERSION : Long = 1

fun saveRecorderTrace(out: OutputStream, rootCallsPerThread: List<TRTracePoint>) {
    val output = DataOutputStream(out.buffered(OUTPUT_BUFFER_SIZE))

    output.writeLong(TRACE_MAGIC)
    output.writeLong(TRACE_VERSION)

    saveCache(output, methodCache)
    saveCache(output, fieldCache)
    saveCache(output, variableCache)

    output.writeInt(rootCallsPerThread.size)
    rootCallsPerThread.forEach { root ->
        encodeTRTracePoint(output, root)
    }
}

fun loadRecordedTrace(inp: InputStream): List<TRTracePoint> {
    val input = DataInputStream(inp.buffered(OUTPUT_BUFFER_SIZE))
    val magic = input.readLong()
    if (magic != TRACE_MAGIC) {
        error("Wrong magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}")
    }

    val version = input.readLong()
    if (version != TRACE_VERSION) {
        error("Wrong version $version (expected $TRACE_VERSION)")
    }

    loadCache(input, methodCache)
    loadCache(input, fieldCache)
    loadCache(input, variableCache)

    val threadNum = input.readInt()
    val roots = mutableListOf<TRMethodCallTracePoint>()
    repeat(threadNum) {
        roots.add(loadTracePoint(input) as TRMethodCallTracePoint)
    }
    return roots
}

private inline fun <reified  V> saveCache(output: DataOutputStream, cache: IndexedPool<V>) {
    output.writeInt(cache.content.size)
    cache.content.forEach {
        saveProtoBuf(output, it)
    }
}

private fun encodeTRTracePoint(output: DataOutputStream, node: TRTracePoint) {
    saveProtoBuf(output, node)
    if (node is TRMethodCallTracePoint) {
        output.writeInt(node.events.size)
        node.events.forEach { encodeTRTracePoint(output, it) }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private inline fun <reified  V> saveProtoBuf(output: DataOutputStream, value: V) {
    val ba = ProtoBuf.encodeToByteArray(value)
    output.writeInt(ba.size)
    output.write(ba)
}

private inline fun <reified  V> loadCache(input: DataInputStream, cache: IndexedPool<V>) {
    val count = input.readInt()
    repeat(count) {
        val value = loadProtoBuf<V>(input)
        cache.getOrCreateId(value)
    }
}

private fun loadTracePoint(input: DataInputStream): TRTracePoint {
    val value = loadProtoBuf<TRTracePoint>(input)
    if (value is TRMethodCallTracePoint) {
        val count = input.readInt()
        repeat(count) {
            val child = loadTracePoint(input)
            value.events.add(child)
        }
    }
    return value
}

@OptIn(ExperimentalSerializationApi::class)
private inline fun <reified  V> loadProtoBuf(input: DataInputStream): V {
    val size = input.readInt()
    val ba = ByteArray(size)
    input.read(ba)
    return ProtoBuf.decodeFromByteArray<V>(ba)
}
