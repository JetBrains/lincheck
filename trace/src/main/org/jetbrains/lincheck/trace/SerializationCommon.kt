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
import org.jetbrains.lincheck.util.Logger
import java.io.BufferedReader
import java.io.DataInput
import java.io.DataOutput
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.management.ManagementFactory
import kotlin.math.exp

/**
 * This file contains data structures and extensions for DataInput and DataOutput to save "primitive" trace data.
 *
 * This code is not split into serialization and deserialization parts to keep pairs of functions
 * together, so it is easy to verify that they are true pairs and use the same formats.
 *
 * These functions are for low-level object representations, without kinds and ids.
 */

internal const val TRACE_MAGIC : Long = 0x706e547124ee5f70L
internal const val INDEX_MAGIC : Long = TRACE_MAGIC.inv()
internal const val TRACE_VERSION : Long = 9

internal const val INDEX_FILENAME_SUFFIX = ".idx"
internal const val PACK_FILENAME_SUFFIX = ".packedtrace"

internal const val PACKED_DATA_ITEM_NAME = "trace.data"
internal const val PACKED_INDEX_ITEM_NAME = "trace$INDEX_FILENAME_SUFFIX"
internal const val PACKED_META_ITEM_NAME = "info.txt"

internal const val BLOCK_HEADER_SIZE: Int = Byte.SIZE_BYTES + Int.SIZE_BYTES
internal const val BLOCK_FOOTER_SIZE: Int = Byte.SIZE_BYTES

/**
 * Information about conditions in which trace was collected.
 *
 *  - [className] — Name of traced class.
 *  - [methodName] — Name of traced method.
 *  - [startTime] — start time of trace collection, as returned by [System.currentTimeMillis]
 *  - [endTime] — end time of trace collection, as returned by [System.currentTimeMillis]
 *  - [systemProperties] — state of system properties ([System.getProperties]) at the beginning of trace collection.
 *  - [environment] — state of system environment ([System.getenv]) at the beginning of trace collection.
 */
@ConsistentCopyVisibility
data class TraceMetaInfo private constructor(
    val jvmArgs: String,
    val agentArgs: String,
    val className: String,
    val methodName: String,
    val startTime: Long
) {
    var endTime: Long = 1
        private set

    private val props: MutableMap<String, String> = mutableMapOf()
    private val env: MutableMap<String, String> = mutableMapOf()

    val systemProperties: Map<String, String> get() = props
    val environment: Map<String, String> get() = env

    fun traceEnded() {
        if (endTime <= 0) {
            endTime = System.currentTimeMillis()
        }
    }

    /**
     * Prints this meta info in human-readable way to given [Appendable].
     */
    fun print(printer: Appendable): Unit = with(printer) {
        appendLine("$CLASS_HEADER$className")
        appendLine("$METHOD_HEADER$methodName")
        appendLine("$START_TIME_HEADER$startTime")
        appendLine("$END_TIME_HEADER$endTime")
        appendLine("$JVM_ARGS_HEADER$jvmArgs")
        appendLine("$AGENT_ARGS_HEADER$agentArgs")
        appendMap(PROPERTIES_HEADER, props)
        appendLine()
        appendMap(ENV_HEADER, env)
    }

    companion object {
        private const val CLASS_HEADER: String = "Class: "
        private const val METHOD_HEADER: String = "Method: "
        private const val START_TIME_HEADER: String = "Start time: "
        private const val END_TIME_HEADER: String = "End time: "
        private const val JVM_ARGS_HEADER: String = "JVM arguments: "
        private const val AGENT_ARGS_HEADER: String = "Agent arguments: "
        private const val PROPERTIES_HEADER: String = "Properties:"
        private const val ENV_HEADER: String = "Environment:"

        /**
         * Creates new object: sets [className] and [methodName] to passed parameters,
         * [startTime] to current time and fetch current system properties and environment.
         */
        fun start(agentArgs: String, className: String, methodName: String): TraceMetaInfo {
            val bean = ManagementFactory.getRuntimeMXBean()
            // Read JVM args
            val jvmArgs = bean.inputArguments
                .map { arg -> arg.escapeShell()}
                .joinToString(" ")

            val meta = TraceMetaInfo(jvmArgs, agentArgs, className, methodName, System.currentTimeMillis())
            with (meta) {
                System.getProperties().forEach {
                    props[it.key as String] = it.value as String
                }
                env.putAll(System.getenv())
            }
            return meta
        }

        /**
         * Read meta info in same format as [print] writes.
         */
        fun read(input: InputStream): TraceMetaInfo? {
            val reader = BufferedReader(InputStreamReader(input))

            val className = reader.readLine(CLASS_HEADER) ?: return null
            val methodName = reader.readLine(METHOD_HEADER) ?: return null
            val startTime = reader.readLong(START_TIME_HEADER) ?: return null
            val endTime = reader.readLong(END_TIME_HEADER) ?: return null
            val jvmArgs = reader.readLine(JVM_ARGS_HEADER) ?: return null
            val agentArgs = reader.readLine(AGENT_ARGS_HEADER) ?: return null

            val meta = TraceMetaInfo(jvmArgs, agentArgs, className, methodName, startTime)
            meta.endTime = endTime

            if (!reader.readMap(PROPERTIES_HEADER, meta.props)) return null
            if (!reader.readMap(ENV_HEADER, meta.env)) return null

            return meta
        }

        private fun BufferedReader.readLine(prefix: String): String? {
            val line = readLine() ?: return readError("No \"$prefix\" line")
            if (!line.startsWith(prefix)) return readError("Wrong \"$prefix\" line")
            return line.substring(prefix.length)
        }

        private fun BufferedReader.readLong(prefix: String): Long? {
            val str = readLine(prefix) ?: return null
            val long = str.toLongOrNull() ?: return readError("Invalid format for \"$prefix\": not a number")
            return long
        }

        private fun BufferedReader.checkHeader(prefix: String): Boolean {
            val str = readLine(prefix) ?: return false
            if (str.isEmpty()) return true
            readError<Any>("Section header \"$prefix\" contains unexpected characters")
            return false
        }

        private fun BufferedReader.readMap(header: String, map: MutableMap<String, String>): Boolean {
            if (!checkHeader(header)) return false
            while (true) {
                val line = readLine() ?: break // EOF is Ok
                if (line.isEmpty()) break // Empty line is end-of-map, Ok
                if (line[0] != ' ') {
                    readError<Any>("Wrong line in \"$header\" section: must start from space")
                    return false
                }
                val p = line.parseKV()
                if (p == null) {
                    readError<Any>("Wrong line in \"$header\" section: doesn't contains '='")
                    return false
                }
                map[p.first] = p.second
            }
            return true
        }

        private fun String.parseKV(): Pair<String, String>? {
            val (idx, key) = unescape(1, '=', false)
            if (idx == length) return null
            val (_, value) = unescape(idx + 1)
            return key to value
        }

        private fun String.unescape(from: Int, upTo: Char? = null, special: Boolean = true): Pair<Int, String> {
            var escape = false
            var idx = from
            val sb = StringBuilder()
            while (idx < length) {
                val c = this[idx]
                if (escape) {
                    if (special) {
                        when (c) {
                            'r' -> sb.append('\r')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            else -> sb.append(c)
                        }
                    } else {
                        sb.append(c)
                    }
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == upTo) {
                    break
                } else {
                    sb.append(c)
                }
                idx++
            }
            return idx to sb.toString()
        }

        private fun <T> readError(msg: String): T? {
            Logger.error { "Cannot read trace meta info: $msg" }
            return null
        }

        private fun Appendable.appendMap(header: String, map: Map<String, String>) {
            appendLine(header)
            map.keys.sorted().forEach {
                appendLine(" ${it.escapeKey()}=${map[it]?.escapeValue()}")
            }
        }

        private fun String.escapeKey(): String = this
            .replace("\\", "\\\\")
            .replace("=", "\\=")

        private fun String.escapeValue(): String = this
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")

        private fun String.escapeShell(): String {
            return if (contains(' ') || contains('*') || contains('?') || contains('[')) {
                "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            } else {
                replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
            }
        }
    }
}

internal enum class ObjectKind {
    CLASS_DESCRIPTOR,
    METHOD_DESCRIPTOR,
    FIELD_DESCRIPTOR,
    VARIABLE_DESCRIPTOR,
    STRING,
    ACCESS_PATH,
    CODE_LOCATION,
    TRACEPOINT,
    TRACEPOINT_FOOTER,
    BLOCK_START,
    BLOCK_END,
    EOF
}

internal enum class AccessLocationKind {
    LOCAL_VARIABLE,
    STATIC_FIELD,
    OBJECT_FIELD,
    ARRAY_ELEMENT_BY_INDEX,
    ARRAY_ELEMENT_BY_NAME,
    UNKNOWN
}

internal fun DataOutput.writeType(value: Types.Type) {
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

internal fun DataInput.readType(): Types.Type {
    val type = readByte()
    return when (type.toInt()) {
        0 -> Types.ArrayType(readType())
        1 -> Types.BOOLEAN_TYPE
        2 -> Types.BYTE_TYPE
        3 -> Types.CHAR_TYPE
        4 -> Types.DOUBLE_TYPE
        5 -> Types.FLOAT_TYPE
        6 -> Types.INT_TYPE
        7 -> Types.LONG_TYPE
        8 -> Types.ObjectType(readUTF())
        9 -> Types.SHORT_TYPE
        10 -> Types.VOID_TYPE
        else -> error("Unknown Type id $type")
    }
}

internal fun DataOutput.writeMethodType(value: Types.MethodType) {
    writeType(value.returnType)
    writeInt(value.argumentTypes.size)
    value.argumentTypes.forEach {
        writeType(it)
    }
}

internal fun DataInput.readMethodType(): Types.MethodType {
    val returnType = readType()
    val count = readInt()
    val argumentTypes = mutableListOf<Types.Type>()
    repeat(count) {
        argumentTypes.add(readType())
    }
    return Types.MethodType(argumentTypes, returnType)
}

internal fun DataOutput.writeClassDescriptor(value: ClassDescriptor) {
    writeUTF(value.name)
}

internal fun DataInput.readClassDescriptor(): ClassDescriptor {
    return ClassDescriptor(readUTF())
}

internal fun DataOutput.writeMethodDescriptor(value: MethodDescriptor) {
    writeInt(value.classId)
    writeMethodSignature(value.methodSignature)
}

internal fun DataInput.readMethodDescriptor(context: TraceContext): MethodDescriptor {
    return MethodDescriptor(context,readInt(), readMethodSignature())
}

internal fun DataOutput.writeMethodSignature(value: MethodSignature) {
    writeUTF(value.name)
    writeMethodType(value.methodType)
}

internal fun DataInput.readMethodSignature(): MethodSignature {
    return MethodSignature(readUTF(), readMethodType())
}

internal fun DataOutput.writeFieldDescriptor(value: FieldDescriptor) {
    writeInt(value.classId)
    writeUTF(value.fieldName)
    writeBoolean(value.isStatic)
    writeBoolean(value.isFinal)
}

internal fun DataInput.readFieldDescriptor(context: TraceContext): FieldDescriptor {
    return FieldDescriptor(
        context = context,
        classId = readInt(),
        fieldName = readUTF(),
        isStatic = readBoolean(),
        isFinal = readBoolean()
    )
}

internal fun DataOutput.writeVariableDescriptor(value: VariableDescriptor) {
    writeUTF(value.name)
}

internal fun DataInput.readVariableDescriptor(): VariableDescriptor {
    return VariableDescriptor(readUTF())
}

internal fun DataInput.readAccessLocation(): ShallowAccessLocation {
    val type = readByte().toInt()
    val kind = if (type >= 0 && type < AccessLocationKind.entries.size) AccessLocationKind.entries[type]
               else AccessLocationKind.UNKNOWN
    when (kind) {
        AccessLocationKind.LOCAL_VARIABLE -> {
            val variableDescriptorId = readInt()
            return ShallowLocalVariableAccessLocation(variableDescriptorId)
        }
        AccessLocationKind.STATIC_FIELD -> {
            val fieldDescriptorId = readInt()
            return ShallowStaticFieldAccessLocation(fieldDescriptorId)
        }
        AccessLocationKind.OBJECT_FIELD -> {
            val fieldDescriptorId = readInt()
            return ShallowObjectFieldAccessLocation(fieldDescriptorId)
        }
        AccessLocationKind.ARRAY_ELEMENT_BY_INDEX -> {
            val index = readInt()
            return ShallowArrayElementByIndexAccessLocation(index)
        }
        AccessLocationKind.ARRAY_ELEMENT_BY_NAME -> {
            val accessPathId = readInt()
            return ShallowArrayElementByNameAccessLocation(accessPathId)
        }
        AccessLocationKind.UNKNOWN -> error("Unknown access location kind: read byte is $type, while expected one of: ${AccessLocationKind.entries.map { it.ordinal }.joinToString(", ")}")
    }
}

internal fun DataOutput.writeKind(value: ObjectKind): Unit = writeByte(value.ordinal)

internal fun DataOutput.writeLocationKind(value: AccessLocationKind): Unit = writeByte(value.ordinal)

internal fun DataInput.readKind(): ObjectKind {
    val ordinal = readByte()
    val values = ObjectKind.entries
    if (ordinal < 0 || ordinal > values.size) {
        throw IOException("Cannot read ObjectKind: unknown ordinal $ordinal")
    }
    return values[ordinal.toInt()]
}

internal fun openNewFile(name: String): OutputStream {
    val f = File(name)
    f.parentFile?.mkdirs()
    f.createNewFile()
    return f.outputStream()
}

internal fun openExistingFile(name: String?): InputStream? {
    val f = File(name)
    if (!f.exists()) return null
    return f.inputStream()
}
