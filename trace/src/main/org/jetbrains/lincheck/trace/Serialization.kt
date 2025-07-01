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

import java.io.*

private const val OUTPUT_BUFFER_SIZE: Int = 16*1024*1024

const val TRACE_MAGIC : Long = 0x706e547124ee5f70L
const val TRACE_VERSION : Long = 6

fun saveRecorderTrace(out: OutputStream, context: TraceContext, rootCallsPerThread: List<TRTracePoint>) {
    check(context == TRACE_CONTEXT) { "Now only global TRACE_CONTEXT is supported" }
    DataOutputStream(out.buffered(OUTPUT_BUFFER_SIZE)).use { output ->
        output.writeLong(TRACE_MAGIC)
        output.writeLong(TRACE_VERSION)

        val codeLocationsStringPool = internalizeCodeLocationStrings(context)

        saveCache(output, context.classDescriptors, DataOutput::writeClassDescriptor)
        saveCache(output, context.methodDescriptors, DataOutput::writeMethodDescriptor)
        saveCache(output, context.fieldDescriptors, DataOutput::writeFieldDescriptor)
        saveCache(output, context.variableDescriptors, DataOutput::writeVariableDescriptor)
        saveCache(output, codeLocationsStringPool.content, DataOutput::writeUTF)

        saveCodeLocations(output, context.codeLocations, context,codeLocationsStringPool)

        output.writeInt(rootCallsPerThread.size)
        rootCallsPerThread.forEach { root ->
            root.save(output)
        }
    }
}

fun loadRecordedTrace(inp: InputStream): Pair<TraceContext, List<TRTracePoint>> {
    DataInputStream(inp.buffered(OUTPUT_BUFFER_SIZE)).use { input ->
        val magic = input.readLong()
        if (magic != TRACE_MAGIC) {
            error("Wrong magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}")
        }

        val version = input.readLong()
        if (version != TRACE_VERSION) {
            error("Wrong version $version (expected $TRACE_VERSION)")
        }

        val context = TRACE_CONTEXT // TraceContext()
        loadCache(input, context::restoreClassDescriptor, DataInput::readClassDescriptor)
        loadCache(input, context::restoreMethodDescriptor, { readMethodDescriptor(context) })
        loadCache(input, context::restoreFieldDescriptor, { readFieldDescriptor(context) })
        loadCache(input, context::restoreVariableDescriptor, DataInput::readVariableDescriptor)

        val codeLocationsStringPool = IndexedPool<String>()
        loadCache(input, codeLocationsStringPool::getOrCreateId, DataInput::readUTF)
        loadCodeLocations(input, context,codeLocationsStringPool)


        val threadNum = input.readInt()
        val roots = mutableListOf<TRMethodCallTracePoint>()
        repeat(threadNum) {
            roots.add(loadTRTracePoint(input) as TRMethodCallTracePoint)
        }
        return context to roots
    }
}

private fun <V> saveCache(output: DataOutput, cache: List<V>, writer: DataOutput.(V) -> Unit) {
    output.writeInt(cache.size)
    cache.forEach {
        output.writer(it)
    }
}


private fun <V> loadCache(input: DataInput, setter: (V) -> Unit, reader: DataInput.() -> V) {
    val count = input.readInt()
    repeat(count) {
        setter(input.reader())
    }
}

private fun internalizeCodeLocationStrings(context: TraceContext): IndexedPool<String> {
    val pool = IndexedPool<String>()
    context.codeLocations.forEach {
        // Maybe, this class missed in cache?
        context.getOrCreateClassId(it.className.toCanonicalClassName())

        pool.getOrCreateId(it.methodName)
        val fn = it.fileName
        if (fn != null) {
            pool.getOrCreateId(fn)
        }
    }
    return pool
}

private fun saveCodeLocations(output: DataOutput, locations: List<StackTraceElement>, context: TraceContext, stringCache: IndexedPool<String>) {
    output.writeInt(locations.size)
    locations.forEach {
        output.writeStackTraceElement(it, context, stringCache)
    }
}

private fun loadCodeLocations(input: DataInput, context: TraceContext, stringCache: IndexedPool<String>) {
    val count = input.readInt()
    repeat(count) {
        CodeLocations.newCodeLocation(input.readStackTraceElement(context, stringCache))
    }
}

private fun DataOutput.writeType(value: Types.Type) {
    when (value) {
        is Types.ArrayType -> {
            writeByte(0)
            writeType(value.elementType)
        }
        is Types.BooleanType -> writeByte(1)
        is Types.ByteType -> writeByte(2)
        is Types.CharType -> writeByte(3)
        is Types.DoubleType -> writeByte(4)
        is Types.FloatType -> writeByte(5)
        is Types.IntType -> writeByte(6)
        is Types.LongType -> writeByte(7)
        is Types.ObjectType -> {
            writeByte(8)
            writeUTF(value.className)
        }
        is Types.ShortType ->writeByte(9)
        is Types.VoidType -> writeByte(10)
    }
}

private fun DataOutput.writeMethodType(value: Types.MethodType) {
    writeType(value.returnType)
    writeInt(value.argumentTypes.size)
    value.argumentTypes.forEach {
        writeType(it)
    }
}

private fun DataInput.readType(): Types.Type {
    val type = readByte()
    return when (type.toInt()) {
        0 -> Types.ArrayType(readType())
        1 -> Types.BooleanType()
        2 -> Types.ByteType()
        3 -> Types.CharType()
        4 -> Types.DoubleType()
        5 -> Types.FloatType()
        6 -> Types.IntType()
        7 -> Types.LongType()
        8 -> Types.ObjectType(readUTF())
        9 -> Types.ShortType()
        10 -> Types.VoidType()
        else -> error("Unknown Type id $type")
    }
}

private fun DataInput.readMethodType(): Types.MethodType {
    val returnType = readType()
    val count = readInt()
    val argumentTypes = mutableListOf<Types.Type>()
    repeat(count) {
        argumentTypes.add(readType())
    }
    return Types.MethodType(argumentTypes, returnType)
}

private fun DataOutput.writeClassDescriptor(value: ClassDescriptor) {
    writeUTF(value.name)
}

private fun DataInput.readClassDescriptor(): ClassDescriptor {
    return ClassDescriptor(readUTF())
}

private fun DataOutput.writeMethodDescriptor(value: MethodDescriptor) {
   writeInt(value.classId)
   writeMethodSignature(value.methodSignature)
}

private fun DataInput.readMethodDescriptor(context: TraceContext): MethodDescriptor {
    return MethodDescriptor(context,readInt(), readMethodSignature())
}

private fun DataOutput.writeMethodSignature(value: MethodSignature) {
    writeUTF(value.name)
    writeMethodType(value.methodType)
}

private fun DataInput.readMethodSignature(): MethodSignature {
    return MethodSignature(readUTF(), readMethodType())
}

private fun DataOutput.writeFieldDescriptor(value: FieldDescriptor) {
    writeInt(value.classId)
    writeUTF(value.fieldName)
    writeBoolean(value.isStatic)
    writeBoolean(value.isFinal)
}

private fun DataInput.readFieldDescriptor(context: TraceContext): FieldDescriptor {
    return FieldDescriptor(
        context = context,
        classId = readInt(),
        fieldName = readUTF(),
        isStatic = readBoolean(),
        isFinal = readBoolean()
    )
}

private fun DataOutput.writeVariableDescriptor(value: VariableDescriptor) {
    writeUTF(value.name)
}

private fun DataInput.readVariableDescriptor(): VariableDescriptor {
    return VariableDescriptor(readUTF())
}

private fun DataOutput.writeStackTraceElement(value: StackTraceElement, context: TraceContext, stringCache: IndexedPool<String>) {
    writeInternalizedString(value.className.toCanonicalClassName(), { name -> context.getOrCreateClassId(name) })
    writeInternalizedString(value.methodName, stringCache::getOrCreateId)
    writeInternalizedString(value.fileName, stringCache::getOrCreateId)
    writeInt(value.lineNumber)
}

private fun DataInput.readStackTraceElement(context: TraceContext, stringCache: IndexedPool<String>): StackTraceElement {
    val className = readInternalizedString({ id -> context.getClassDescriptor(id).name  })?.toInternalClassName()
    val methodName = readInternalizedString(stringCache::get)
    val fileName = readInternalizedString(stringCache::get)
    val lineNumber = readInt()
    return StackTraceElement(className, methodName, fileName, lineNumber)
}

private fun DataOutput.writeInternalizedString(value: String?, internalizer: (String) -> Int?) {
    if (value == null) {
        writeInt(-1)
    } else {
        writeInt(internalizer(value) ?: -1)
    }
}

private fun DataInput.readInternalizedString(internalizer: (Int) -> String?): String? {
    val id = readInt()
    if (id < 0) {
        return null
    } else {
        return internalizer(id)
    }
}

/**
 * Converts a string representing a class name in internal format (e.g., "com/example/MyClass")
 * into a canonical class name format with (e.g., "com.example.MyClass").
 */
private fun String.toCanonicalClassName() =
    this.replace('/', '.')

/**
 * Converts a string representing a class name in canonical format (e.g., "com.example.MyClass")
 * into an internal class name format with (e.g., "com/example/MyClass").
 */
private fun String.toInternalClassName() =
    this.replace('.', '/')
