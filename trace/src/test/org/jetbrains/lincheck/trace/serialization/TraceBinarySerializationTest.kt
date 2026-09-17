/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.descriptors.AccessLocation
import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.descriptors.ArrayElementByIndexAccessLocation
import org.jetbrains.lincheck.descriptors.ArrayElementByNameAccessLocation
import org.jetbrains.lincheck.descriptors.ClassDescriptor
import org.jetbrains.lincheck.descriptors.FieldDescriptor
import org.jetbrains.lincheck.descriptors.FieldKind
import org.jetbrains.lincheck.descriptors.LocalVariableAccessLocation
import org.jetbrains.lincheck.descriptors.MethodDescriptor
import org.jetbrains.lincheck.descriptors.MethodSignature
import org.jetbrains.lincheck.descriptors.ObjectFieldAccessLocation
import org.jetbrains.lincheck.descriptors.StaticFieldAccessLocation
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.descriptors.VariableDescriptor
import org.jetbrains.lincheck.trace.DiffStatus
import org.jetbrains.lincheck.trace.TraceArray
import org.jetbrains.lincheck.trace.TraceArraySnapshot
import org.jetbrains.lincheck.trace.TraceCatchTracePoint
import org.jetbrains.lincheck.trace.TraceLoopIterationTracePoint
import org.jetbrains.lincheck.trace.TraceLoopTracePoint
import org.jetbrains.lincheck.trace.TraceMethodCallTracePoint
import org.jetbrains.lincheck.trace.TraceObject
import org.jetbrains.lincheck.trace.TraceMapSnapshot
import org.jetbrains.lincheck.trace.TraceObjectSnapshot
import org.jetbrains.lincheck.trace.TraceScalar
import org.jetbrains.lincheck.trace.TraceReadArrayTracePoint
import org.jetbrains.lincheck.trace.TraceTextSnapshot
import org.jetbrains.lincheck.trace.TraceTypeReference
import org.jetbrains.lincheck.trace.TypeFlavor
import org.jetbrains.lincheck.trace.TraceNull
import org.jetbrains.lincheck.trace.TraceString
import org.jetbrains.lincheck.trace.TraceArbitraryDecimal
import org.jetbrains.lincheck.trace.TraceArbitraryInteger
import org.jetbrains.lincheck.trace.TraceException
import org.jetbrains.lincheck.trace.TraceExceptionSnapshot
import org.jetbrains.lincheck.trace.TraceReadLocalVariableTracePoint
import org.jetbrains.lincheck.trace.TraceReadFieldTracePoint
import org.jetbrains.lincheck.trace.TraceRedacted
import org.jetbrains.lincheck.trace.TraceSnapshotLineBreakpointTracePoint
import org.jetbrains.lincheck.trace.TraceThrowTracePoint
import org.jetbrains.lincheck.trace.TracePoint
import org.jetbrains.lincheck.trace.TraceUnfinishedMethodResult
import org.jetbrains.lincheck.trace.TraceUntrackedMethodResult
import org.jetbrains.lincheck.trace.TraceRenderedValue
import org.jetbrains.lincheck.trace.TraceValue
import org.jetbrains.lincheck.trace.TraceUnit
import org.jetbrains.lincheck.trace.TraceVoid
import org.jetbrains.lincheck.trace.TraceWriteArrayTracePoint
import org.jetbrains.lincheck.trace.TraceWriteLocalVariableTracePoint
import org.jetbrains.lincheck.trace.TraceWriteFieldTracePoint
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.createAndRegisterClassDescriptor
import org.jetbrains.lincheck.trace.RUNTIME_JVM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.jetbrains.lincheck.descriptors.LineCodeLocation
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException
import java.util.EnumSet
import java.util.UUID

/**
 * Round-trip tests for the per-class binary (de)serialization functions
 * defined in `TraceBinarySerialization.kt`.
 *
 * Every test calls [assertRoundTrip], which serializes a value through the
 * `write*` extension, deserializes it through the matching `read*` extension,
 * and asserts that the result equals the original — i.e. that
 * `deserialize(serialize(x)) == x`.
 *
 * Section order mirrors the section order in `TraceBinarySerialization.kt`.
 */
class TraceBinarySerializationTest {

    // ======== Trace File Header ========

    @Test
    fun traceHeaderRoundTrip() {
        val bytes = encodeBytes { writeTraceHeader(RUNTIME_JVM) }
        val runtime = DataInputStream(ByteArrayInputStream(bytes)).use { it.checkTraceHeader() }
        assertEquals(RUNTIME_JVM, runtime)
    }

    @Test
    fun traceHeaderRoundTripsANonJvmRuntime() {
        // A runtime this build has never heard of is passed through, not rejected:
        // the header names the producer, and the reader is not the arbiter of which producers exist.
        listOf("python", "smalltalk").forEach { producer ->
            val bytes = encodeBytes { writeTraceHeader(producer) }
            val runtime = DataInputStream(ByteArrayInputStream(bytes)).use { it.checkTraceHeader() }
            assertEquals(producer, runtime)
        }
    }

    @Test
    fun traceHeaderPutsTheRuntimeAfterMagicAndVersion() {
        val bytes = encodeBytes { writeTraceHeader(RUNTIME_JVM) }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            assertEquals(TRACE_MAGIC, input.readLong())
            assertEquals(TRACE_VERSION, input.readLong())
            assertEquals(RUNTIME_JVM, input.readUTF())
        }
    }

    @Test
    fun traceIndexHeaderRoundTrip() {
        val bytes = encodeBytes { writeTraceIndexHeader() }
        DataInputStream(ByteArrayInputStream(bytes)).use { it.checkTraceIndexHeader() }
    }

    @Test
    fun readTraceHeaderRejectsWrongMagic() {
        // Writing the index magic where the data magic is expected must fail.
        val bytes = encodeBytes { writeTraceIndexHeader() }
        val exception = assertThrows(IllegalStateException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use { it.checkTraceHeader() }
        }
        assertTrue(
            "expected 'magic' in message but was: ${exception.message}",
            exception.message!!.contains("magic"),
        )
    }

    @Test
    fun readTraceIndexHeaderRejectsWrongMagic() {
        val bytes = encodeBytes { writeTraceHeader(RUNTIME_JVM) }
        val exception = assertThrows(IllegalStateException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use { it.checkTraceIndexHeader() }
        }
        assertTrue(
            "expected 'magic' in message but was: ${exception.message}",
            exception.message!!.contains("magic"),
        )
    }

    @Test
    fun readTraceHeaderRejectsWrongVersion() {
        val bytes = encodeBytes {
            writeLong(TRACE_MAGIC)
            writeLong(TRACE_VERSION + 1) // mismatched version
        }
        val exception = assertThrows(IllegalStateException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use { it.checkTraceHeader() }
        }
        assertTrue(
            "expected 'version' in message but was: ${exception.message}",
            exception.message!!.contains("version"),
        )
    }

    @Test
    fun readTraceIndexHeaderRejectsWrongVersion() {
        val bytes = encodeBytes {
            writeLong(INDEX_MAGIC)
            writeLong(TRACE_VERSION + 1) // mismatched version
        }
        val exception = assertThrows(IllegalStateException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use { it.checkTraceIndexHeader() }
        }
        assertTrue(
            "expected 'version' in message but was: ${exception.message}",
            exception.message!!.contains("version"),
        )
    }

    // ======== Object Kinds ========

    @Test
    fun objectKind() {
        for (kind in ObjectKind.entries) {
            assertRoundTrip(kind, DataOutput::writeKind, DataInput::readKind)
        }
    }

    @Test
    fun readKindRejectsOutOfRangeOrdinal() {
        val bytes = encodeBytes { writeByte(100) }
        val exception = assertThrows(IOException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use { it.readKind() }
        }
        assertTrue(
            "expected 'ObjectKind' in message but was: ${exception.message}",
            exception.message!!.contains("ObjectKind"),
        )
    }

    // ======== Strings ========

    @Test
    fun string() {
        val cases: List<String> = listOf(
            "",
            "hello",
            "with spaces and punctuation: ,.;!?",
            "Юникод 🎉",
        )
        for (value in cases) {
            assertRoundTrip(value, DataOutput::writeString, DataInput::readString)
        }
    }

    @Test
    fun nullableString() {
        val cases: List<String?> = listOf(
            null,
            "",
            "hello",
            "with spaces and punctuation: ,.;!?",
            "Юникод 🎉",
        )
        for (value in cases) {
            assertRoundTrip(value, DataOutput::writeNullableString, DataInput::readNullableString)
        }
    }

    // ======== Thread Names ========

    @Test
    fun threadName() {
        val cases: List<Pair<Int, String>> = listOf(
            0 to "main",
            1 to "Thread-1",
            7 to "worker-pool-7",
            Int.MAX_VALUE to "edge-case",
            42 to "",                            // empty name
            -1 to "negative-id",                 // negative id (unusual, still valid)
            100 to "Юникод 🎉",                  // non-ASCII name
            5 to "with spaces and punctuation",
        )
        for (case in cases) {
            assertRoundTrip(
                value = case,
                writer = { (id, name) -> writeThreadName(id, name) },
                reader = { readThreadName() },
            )
        }
    }

    // ======== Types ========

    @Test
    fun types() {
        val primitives: List<Types.Type> = listOf(
            Types.VOID_TYPE,
            Types.BOOLEAN_TYPE,
            Types.BYTE_TYPE,
            Types.CHAR_TYPE,
            Types.DOUBLE_TYPE,
            Types.FLOAT_TYPE,
            Types.INT_TYPE,
            Types.LONG_TYPE,
            Types.SHORT_TYPE,
        )
        val boxed: List<Types.Type> = listOf(
            Types.BOOLEAN_TYPE_BOXED,
            Types.BYTE_TYPE_BOXED,
            Types.CHAR_TYPE_BOXED,
            Types.DOUBLE_TYPE_BOXED,
            Types.FLOAT_TYPE_BOXED,
            Types.INT_TYPE_BOXED,
            Types.LONG_TYPE_BOXED,
            Types.SHORT_TYPE_BOXED,
        )
        val objects: List<Types.Type> = listOf(
            Types.OBJECT_TYPE,
            Types.ObjectType("java.lang.String"),
            Types.ObjectType("java.util.Map\$Entry"),
            Types.ObjectType("com.example.Outer\$Inner\$Deeper"),
            Types.ObjectType("com.example.Foo\$1"), // anonymous inner
            Types.ObjectType("kotlin.collections.HashMap"),
            Types.ObjectType("A"), // single-character class name
        )
        val primitiveArrays: List<Types.Type> = listOf(
            Types.BOOLEAN_ARRAY_TYPE,
            Types.BYTE_ARRAY_TYPE,
            Types.CHAR_ARRAY_TYPE,
            Types.DOUBLE_ARRAY_TYPE,
            Types.FLOAT_ARRAY_TYPE,
            Types.INT_ARRAY_TYPE,
            Types.LONG_ARRAY_TYPE,
            Types.SHORT_ARRAY_TYPE,
        )
        val objectArrays: List<Types.Type> = listOf(
            Types.OBJECT_ARRAY_TYPE,
            Types.ArrayType(Types.ObjectType("java.lang.String")),
            Types.ArrayType(Types.INT_TYPE_BOXED),
        )
        val multiDimensionalArrays: List<Types.Type> = listOf(
            Types.ArrayType(Types.INT_ARRAY_TYPE),                                  // int[][]
            Types.ArrayType(Types.ArrayType(Types.INT_ARRAY_TYPE)),                 // int[][][]
            Types.ArrayType(Types.OBJECT_ARRAY_TYPE),                               // Object[][]
            Types.ArrayType(Types.ArrayType(Types.ObjectType("java.lang.String"))), // String[][]
        )
        val cases = listOf(
            primitives,
            boxed,
            objects,
            primitiveArrays,
            objectArrays,
            multiDimensionalArrays
        ).flatten()

        for (type in cases) {
            assertRoundTrip(type, DataOutput::writeType, DataInput::readType)
        }
    }

    // ======== Method Types ========

    @Test
    fun methodType() {
        val cases: List<Types.MethodType> = listOf(
            // no-arg cases
            Types.MethodType(Types.VOID_TYPE),
            Types.MethodType(Types.INT_TYPE),
            Types.MethodType(Types.ObjectType("java.lang.String")),

            // constructor-like (void return, object arg)
            Types.MethodType(Types.VOID_TYPE, Types.ObjectType("java.lang.String")),

            // all primitives in args
            Types.MethodType(
                Types.VOID_TYPE,
                Types.INT_TYPE, Types.LONG_TYPE, Types.DOUBLE_TYPE,
                Types.FLOAT_TYPE, Types.BOOLEAN_TYPE, Types.BYTE_TYPE,
                Types.SHORT_TYPE, Types.CHAR_TYPE,
            ),

            // all boxed primitives in args
            Types.MethodType(
                Types.OBJECT_TYPE,
                Types.INT_TYPE_BOXED, Types.LONG_TYPE_BOXED, Types.DOUBLE_TYPE_BOXED,
                Types.FLOAT_TYPE_BOXED, Types.BOOLEAN_TYPE_BOXED, Types.BYTE_TYPE_BOXED,
                Types.SHORT_TYPE_BOXED, Types.CHAR_TYPE_BOXED,
            ),

            // primitive arrays in args, returning object array
            Types.MethodType(
                Types.OBJECT_ARRAY_TYPE,
                Types.INT_ARRAY_TYPE, Types.LONG_ARRAY_TYPE, Types.BOOLEAN_ARRAY_TYPE,
            ),

            // multi-dimensional arrays
            Types.MethodType(
                Types.ArrayType(Types.INT_ARRAY_TYPE),
                Types.ArrayType(Types.OBJECT_ARRAY_TYPE),
            ),

            // many args of mixed kinds
            Types.MethodType(
                Types.ObjectType("java.lang.String"),
                Types.INT_TYPE,
                Types.OBJECT_TYPE,
                Types.INT_TYPE_BOXED,
                Types.OBJECT_ARRAY_TYPE,
                Types.ArrayType(Types.ArrayType(Types.LONG_TYPE)),
            ),
        )

        for (methodType in cases) {
            assertRoundTrip(methodType, DataOutput::writeMethodType, DataInput::readMethodType)
        }
    }

    // ======== Method Signatures ========

    @Test
    fun methodSignature() {
        val cases = listOf(
            // no-arg method
            MethodSignature("noArgs", Types.MethodType(Types.VOID_TYPE)),

            // getter/setter
            MethodSignature("getValue", Types.MethodType(Types.INT_TYPE)),
            MethodSignature("setValue", Types.MethodType(Types.VOID_TYPE, Types.INT_TYPE)),

            // constructor and static initializer
            MethodSignature("<init>", Types.MethodType(Types.VOID_TYPE, Types.ObjectType("java.lang.String"))),
            MethodSignature("<clinit>", Types.MethodType(Types.VOID_TYPE)),

            // many primitive args
            MethodSignature(
                "withManyArgs",
                Types.MethodType(Types.INT_TYPE, Types.LONG_TYPE, Types.DOUBLE_TYPE, Types.FLOAT_TYPE),
            ),

            // mangled / synthetic-flavored names (still valid JVM identifiers)
            MethodSignature("access\$000", Types.MethodType(Types.OBJECT_TYPE, Types.OBJECT_TYPE)),
            MethodSignature("foo\$kotlin_stdlib", Types.MethodType(Types.VOID_TYPE)),

            // Kotlin operator-like name
            MethodSignature("invoke", Types.MethodType(Types.OBJECT_TYPE, Types.OBJECT_TYPE)),

            // method returning array
            MethodSignature("toArray", Types.MethodType(Types.OBJECT_ARRAY_TYPE)),

            // method taking and returning multi-dim arrays
            MethodSignature(
                "matrixMultiply",
                Types.MethodType(
                    Types.ArrayType(Types.INT_ARRAY_TYPE),
                    Types.ArrayType(Types.INT_ARRAY_TYPE),
                    Types.ArrayType(Types.INT_ARRAY_TYPE),
                ),
            ),
        )

        for (signature in cases) {
            assertRoundTrip(signature, DataOutput::writeMethodSignature, DataInput::readMethodSignature)
        }
    }

    // ======== Class Descriptors ========

    @Test
    fun classDescriptor() {
        val context = TraceContext()
        val cases = listOf(
            // non-primitive class name
            ClassDescriptor(context, "com.example.Foo"),

            // boxed-type class names
            ClassDescriptor(context, "java.lang.Integer"),
            ClassDescriptor(context, "java.lang.Long"),
            ClassDescriptor(context, "java.lang.Boolean"),
            ClassDescriptor(context, "java.lang.Character"),

            // common JDK types
            ClassDescriptor(context, "java.lang.Object"),
            ClassDescriptor(context, "java.lang.String"),

            // nested / anonymous
            ClassDescriptor(context, "java.util.HashMap\$Node"),
            ClassDescriptor(context, "com.example.Foo\$1"),
            ClassDescriptor(context, "com.example.Outer\$Inner\$Deeper"),

            // Kotlin function class
            ClassDescriptor(context, "kotlin.Function1"),

            // edge cases
            ClassDescriptor(context, "A"), // minimal name
            ClassDescriptor(context, ""),  // empty
        )
        for (descriptor in cases) {
            assertRoundTrip(descriptor, writer = { writeClassDescriptor(it) }, reader = { readClassDescriptor(context) })
        }
    }

    // ======== Method Descriptors ========

    @Test
    fun methodDescriptor() {
        val context = TraceContext()
        val cases = listOf(
            // simple no-arg
            MethodDescriptor(context, classId = 0,
                methodSignature = MethodSignature("noArgs", Types.MethodType(Types.VOID_TYPE))
            ),

            // constructor
            MethodDescriptor(context, classId = 1,
                methodSignature = MethodSignature("<init>", Types.MethodType(Types.VOID_TYPE))
            ),

            // static initializer
            MethodDescriptor(context, classId = 2,
                methodSignature = MethodSignature("<clinit>", Types.MethodType(Types.VOID_TYPE))
            ),

            // large classId
            MethodDescriptor(context, classId = Int.MAX_VALUE,
                methodSignature = MethodSignature("edgeCaseClassId", Types.MethodType(Types.VOID_TYPE))
            ),

            // returning array
            MethodDescriptor(context, classId = 3,
                methodSignature = MethodSignature("toArray", Types.MethodType(Types.OBJECT_ARRAY_TYPE))
            ),

            // many parameters, mixed kinds
            MethodDescriptor(
                context,
                classId = 4,
                methodSignature = MethodSignature(
                    "doManyThings",
                    Types.MethodType(
                        Types.INT_TYPE,
                        Types.INT_TYPE,
                        Types.OBJECT_TYPE,
                        Types.INT_TYPE_BOXED,
                        Types.OBJECT_ARRAY_TYPE,
                        Types.ArrayType(Types.ArrayType(Types.LONG_TYPE)),
                    ),
                ),
            ),
        )

        for (descriptor in cases) {
            assertRoundTrip(descriptor, writer = { writeMethodDescriptor(it) }, reader = { readMethodDescriptor(context) })
        }
    }

    // ======== Field Descriptors ========

    @Test
    fun fieldDescriptor() {
        val context = TraceContext()

        val fieldTypes = listOf(
            Types.INT_TYPE,
            Types.LONG_TYPE,
            Types.BOOLEAN_TYPE,

            Types.OBJECT_TYPE,
            Types.INT_TYPE_BOXED,
            Types.ObjectType("java.lang.String"),

            Types.OBJECT_ARRAY_TYPE,
            Types.INT_ARRAY_TYPE,
            Types.ArrayType(Types.ObjectType("java.lang.String")),
        )

        val fieldNames = listOf(
            "value",
            "count",
            "_internal",
            "name\$delegate",
            "x",
            ""
        )

        // The wire format stores only `isStatic`, and the reader rebuilds the FieldKind from it,
        // so the matrix walks every FieldKind to pin that mapping.
        for (fieldKind in FieldKind.entries) {
            for (isFinal in listOf(false, true)) {
                for (isVolatile in listOf(false, true)) {
                    for ((classId, fieldName) in fieldNames.withIndex()) {
                        for (type in fieldTypes) {
                            val descriptor = FieldDescriptor(
                                context = context,
                                classId = classId,
                                fieldName = fieldName,
                                type = type,
                                fieldKind = fieldKind,
                                isFinal = isFinal,
                                isVolatile = isVolatile,
                            )
                            assertRoundTrip(descriptor, writer = { writeFieldDescriptor(it) }, reader = { readFieldDescriptor(context) })
                        }
                    }
                }
            }
        }
    }

    // ======== Variable Descriptors ========

    @Test
    fun variableDescriptor() {
        val context = TraceContext()

        val variableTypes = listOf(
            Types.INT_TYPE,
            Types.LONG_TYPE,
            Types.BOOLEAN_TYPE,

            Types.OBJECT_TYPE,
            Types.INT_TYPE_BOXED,
            Types.ObjectType("java.lang.String"),

            Types.INT_ARRAY_TYPE,
            Types.OBJECT_ARRAY_TYPE,
            Types.ArrayType(Types.INT_ARRAY_TYPE),
        )

        val variableNames = listOf(
            "i",                       // single-char loop counter
            "counter",                 // ordinary name
            "this",                    // reserved-keyword-shaped name (still a valid JVM local)
            "\$local0",                // Kotlin-synthetic-flavored
            "\$\$delegated\$\$",       // multi-dollar
            "with_underscores",
            "withCamelCase",
            "",                        // empty
        )

        for (type in variableTypes) {
            for (name in variableNames) {
                val descriptor = VariableDescriptor(context, name, type)
                assertRoundTrip(descriptor, writer = { writeVariableDescriptor(it) }, reader = { readVariableDescriptor(context) })
            }
        }
    }

    // ======== Code Locations ========

    @Test
    fun codeLocationKind() {
        for (kind in CodeLocationKind.entries) {
            assertRoundTrip(kind, DataOutput::writeCodeLocationKind, DataInput::readCodeLocationKind)
        }
    }

    @Test
    fun readCodeLocationKindRejectsOutOfRangeOrdinal() {
        val bytes = encodeBytes { writeByte(100) }
        val exception = assertThrows(IOException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use { it.readCodeLocationKind() }
        }
        assertTrue(
            "expected 'CodeLocationKind' in message but was: ${exception.message}",
            exception.message!!.contains("CodeLocationKind"),
        )
    }

    // ======== Access Locations ========

    @Test
    fun accessLocationKind() {
        for (kind in AccessLocationKind.entries) {
            assertRoundTrip(kind, DataOutput::writeAccessLocationKind, DataInput::readAccessLocationKind)
        }
    }

    @Test
    fun accessLocationLocalVariable() {
        val context = TraceContext()
        val variable = VariableDescriptor(context, "counter", Types.INT_TYPE)
        context.variablePool.register(variable)
        val location: AccessLocation = LocalVariableAccessLocation(variable)
        assertRoundTrip(
            location,
            writer = { writeAccessLocation(context, it) },
            reader = { readAccessLocation(context) },
        )
    }

    @Test
    fun accessLocationStaticField() {
        val context = TraceContext()
        val field = FieldDescriptor(
            context = context,
            classId = 0,
            fieldName = "STATIC_X",
            type = Types.INT_TYPE,
            fieldKind = FieldKind.STATIC,
            isFinal = false,
            isVolatile = false,
        )
        context.fieldPool.register(field)
        val location: AccessLocation = StaticFieldAccessLocation(field)
        assertRoundTrip(
            location,
            writer = { writeAccessLocation(context, it) },
            reader = { readAccessLocation(context) },
        )
    }

    @Test
    fun accessLocationObjectField() {
        val context = TraceContext()
        val field = FieldDescriptor(
            context = context,
            classId = 0,
            fieldName = "field",
            type = Types.ObjectType("java.lang.Object"),
            fieldKind = FieldKind.INSTANCE,
            isFinal = false,
            isVolatile = false,
        )
        context.fieldPool.register(field)
        val location: AccessLocation = ObjectFieldAccessLocation(field)
        assertRoundTrip(
            location,
            writer = { writeAccessLocation(context, it) },
            reader = { readAccessLocation(context) },
        )
    }

    @Test
    fun accessLocationArrayElementByIndex() {
        val context = TraceContext()
        for (index in listOf(0, 1, 42, Int.MAX_VALUE)) {
            val location: AccessLocation = ArrayElementByIndexAccessLocation(index)
            assertRoundTrip(
                location,
                writer = { writeAccessLocation(context, it) },
                reader = { readAccessLocation(context) },
            )
        }
    }

    @Test
    fun accessLocationArrayElementByName() {
        val context = TraceContext()
        val variable = VariableDescriptor(context, "i", Types.INT_TYPE)
        context.variablePool.register(variable)
        val indexPath = AccessPath(LocalVariableAccessLocation(variable))
        context.accessPathPool.register(indexPath)
        val location: AccessLocation = ArrayElementByNameAccessLocation(indexPath)
        assertRoundTrip(
            location,
            writer = { writeAccessLocation(context, it) },
            reader = { readAccessLocation(context) },
        )
    }

    @Test
    fun readAccessLocationKindRejectsOutOfRangeOrdinal() {
        val bytes = encodeBytes { writeByte(100) }
        val exception = assertThrows(IOException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use { it.readAccessLocationKind() }
        }
        assertTrue(
            "expected 'AccessLocationKind' in message but was: ${exception.message}",
            exception.message!!.contains("AccessLocationKind"),
        )
    }

    // ======== TR Values ========

    @Test
    fun trValueJvmPrimitives() {
        val context = TraceContext()
        // Every scalar encoding, at the bounds of the JVM primitive that backs it.
        val cases: List<TraceValue> = listOf(
            TraceScalar(0.toByte()),
            TraceScalar(Byte.MIN_VALUE),
            TraceScalar(Byte.MAX_VALUE),

            TraceScalar(1234.toShort()),
            TraceScalar(Short.MIN_VALUE),
            TraceScalar(Short.MAX_VALUE),

            TraceScalar(0),
            TraceScalar(42),
            TraceScalar(Int.MIN_VALUE),
            TraceScalar(Int.MAX_VALUE),

            TraceScalar(0L),
            TraceScalar(Long.MIN_VALUE),
            TraceScalar(Long.MAX_VALUE),

            TraceScalar(3.14f),
            TraceScalar(Float.MIN_VALUE),
            TraceScalar(Float.MAX_VALUE),

            TraceScalar(2.71828),
            TraceScalar(Double.MIN_VALUE),
            TraceScalar(Double.MAX_VALUE),

            TraceScalar('A'),
            TraceScalar('ж'), // non-ASCII
            TraceScalar(' '),

            TraceScalar(true),
            TraceScalar(false),
        )

        for (value in cases) {
            assertRoundTrip(value, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
        }
    }

    @Test
    fun trValueString() {
        val context = TraceContext()
        val cases: List<TraceValue> = listOf(
            TraceString("hello"),
            TraceString(""),
            TraceString("Юникод 🎉"),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
        }
    }

    @Test
    fun trValueBigNumber() {
        val context = TraceContext()
        val cases: List<TraceValue> = listOf(
            TraceArbitraryInteger("12345"),
            TraceArbitraryInteger(""),
            TraceArbitraryDecimal("3.14"),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
        }
    }

    @Test
    fun trValueClassReference() {
        val context = TraceContext()
        val cases: List<TraceValue> = listOf(
            TraceTypeReference("MyClass.class", TypeFlavor.JAVA_CLASS),
            TraceTypeReference("java.lang.String.class", TypeFlavor.JAVA_CLASS),
            TraceTypeReference("", TypeFlavor.JAVA_CLASS),
            TraceTypeReference("MyClass.kclass", TypeFlavor.KOTLIN_CLASS),
            TraceTypeReference("kotlin.String.kclass", TypeFlavor.KOTLIN_CLASS),
            TraceTypeReference("", TypeFlavor.KOTLIN_CLASS),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
        }
    }

    @Test
    fun trValueCharSequence() {
        val context = TraceContext()
        val sbCd = context.createAndRegisterClassDescriptor("java.lang.StringBuilder")
        val cbCd = context.createAndRegisterClassDescriptor("java.nio.CharBuffer")
        val cases: List<TraceValue> = listOf(
            TraceTextSnapshot(sbCd, 0xCAFE, "builder contents"),
            TraceTextSnapshot(sbCd, 0, ""),
            TraceTextSnapshot(cbCd, 0xBEEF, "buffer text"),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
        }
    }

    @Test
    fun trValueException() {
        val context = TraceContext()
        val cd = context.createAndRegisterClassDescriptor("java.lang.IllegalStateException")
        val cases: List<TraceValue> = listOf(
            // Plain TraceException — the trace-recorder shape: class descriptor + identity only.
            TraceException(cd, 0),
            TraceException(cd, 0xCAFE),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
        }
    }

    @Test
    fun trValueExceptionSnapshot() {
        val context = TraceContext()
        val cd = context.createAndRegisterClassDescriptor("java.lang.IllegalStateException")
        val cases: List<TraceValue> = listOf(
            // No message, no frames — minimum-shape snapshot.
            TraceExceptionSnapshot(cd, 0, message = TraceNull, stackTrace = emptyList()),
            // Typical shape — message present, a couple of rendered frames.
            TraceExceptionSnapshot(
                cd, 0xCAFE,
                message = TraceString("boom"),
                stackTrace = listOf(
                    "com.example.Foo.bar(Foo.java:42)",
                    "com.example.Foo.main(Foo.java:7)",
                ),
            ),
            // Empty-string message and one frame — exercises non-null empty path.
            TraceExceptionSnapshot(
                cd,
                1234,
                message = TraceString(""),
                stackTrace = listOf("com.example.Foo.tail(Foo.java:1)"),
            ),
            TraceExceptionSnapshot(
                cd,
                5678,
                message = TraceRedacted(
                    classDescriptor = context.createAndRegisterClassDescriptor("java.lang.String"),
                    templateUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                    templateName = "GDPR defaults",
                ),
                stackTrace = emptyList(),
            ),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
        }
    }

    @Test
    fun trValueUnit() {
        val context = TraceContext()
        assertRoundTrip(TraceUnit, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueRedacted() {
        val context = TraceContext()
        val cases = listOf(
            TraceRedacted(
                context.createAndRegisterClassDescriptor("java.lang.String"),
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "GDPR defaults",
            ),
            TraceRedacted(context.createAndRegisterClassDescriptor("int"), null, null),
            TraceRedacted(null, null, null),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
        }
    }

    @Test
    fun trValueRendered() {
        val context = TraceContext()
        val cases = listOf(
            TraceRenderedValue("<Check: backups>"),
            TraceRenderedValue(""),
            TraceRenderedValue("a:b;c=d|e,f"),
            TraceRenderedValue("Ünïcødé ✓"),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
        }
    }

    @Test
    fun trValueRenderedIsALeafInsideAnObjectSnapshot() {
        val context = TraceContext()
        val cd = context.createAndRegisterClassDescriptor("hc.api.models.Check")
        val value: TraceValue = TraceObjectSnapshot(
            cd,
            1_140_234_871,
            rendered = null,
            linkedMapOf("status" to TraceRenderedValue("'up'"), "n_pings" to TraceRenderedValue("42")),
        )
        assertRoundTrip(value, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueMapSnapshot() {
        val context = TraceContext()
        val cd = context.createAndRegisterClassDescriptor("java.util.LinkedHashMap")
        val cases: List<TraceValue> = listOf(
            // Empty, and truncated: totalSize larger than what was captured.
            TraceMapSnapshot(cd, 1, totalSize = 0, capturedEntries = emptyList()),
            TraceMapSnapshot(cd, 2, totalSize = 99, capturedEntries = listOf(TraceString("k") to TraceString("v"))),
            // Non-string keys are the reason this shape exists: a map keyed by anything else
            // cannot be expressed as an object snapshot's named fields.
            TraceMapSnapshot(
                cd, 3, totalSize = 2,
                capturedEntries = listOf(
                    TraceScalar(7) to TraceString("seven"),
                    TraceRenderedValue("(1, 2)") to TraceScalar(true),
                ),
            ),
            // Duplicate keys stay distinct rows: entries are a list, not a Map.
            TraceMapSnapshot(
                cd, 4, totalSize = 2,
                capturedEntries = listOf(TraceString("dup") to TraceScalar(1), TraceString("dup") to TraceScalar(2)),
            ),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
        }
    }

    @Test
    fun trValueMapSnapshotNestsSnapshotsOnBothSidesOfAnEntry() {
        // A key may itself carry a class descriptor, so `preWriteTraceValue` recurses over both
        // halves of an entry.
        val context = TraceContext()
        val mapClass = context.createAndRegisterClassDescriptor("java.util.HashMap")
        val keyClass = context.createAndRegisterClassDescriptor("org.example.Key")
        val valueClass = context.createAndRegisterClassDescriptor("org.example.Value")
        val value: TraceValue = TraceMapSnapshot(
            mapClass, 5, totalSize = 1,
            capturedEntries = listOf(
                TraceObjectSnapshot(keyClass, 6, rendered = null, linkedMapOf("id" to TraceScalar(1))) to
                    TraceArraySnapshot(valueClass, 7, totalSize = 1, capturedElements = listOf(TraceString("x"))),
            ),
        )
        assertRoundTrip(value, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueMapSnapshotKeepsItsWireOrdinal() {
        assertEquals(28, TraceValueKind.MAP_SNAPSHOT.ordinal)
    }

    /**
     * Drives a real [TraceWriter] into a reader with its own empty context, so a class descriptor
     * that `preWriteTraceValue` failed to register surfaces as a missing-pool-entry failure.
     * The plain codec round-trip above cannot catch that: it reuses the writer's context.
     */
    @Test
    fun mapSnapshotEntriesGetTheirClassDescriptorsRegistered() {
        val context = TraceContext()
        context.setThreadName(0, "main")
        val location = context.codeLocationsPool.register(
            LineCodeLocation(StackTraceElement("org.example.Svc", "handle", "Svc.kt", 10), emptyList())
        )
        val mapClass = context.createAndRegisterClassDescriptor("java.util.HashMap")
        val keyClass = context.createAndRegisterClassDescriptor("org.example.Key")
        val tracePoint = TraceSnapshotLineBreakpointTracePoint(
            context = context,
            codeLocationId = location,
            threadId = 0,
            breakpointUuid = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            stackTraceCodeLocationIds = emptyList(),
            currentTimeMillis = 1L,
            locals = listOf(
                TraceMapSnapshot(
                    mapClass, 1, totalSize = 1,
                    // The only class descriptor below the map lives on the *key* half of the entry.
                    capturedEntries = listOf(
                        TraceObjectSnapshot(keyClass, 2, rendered = null, linkedMapOf("id" to TraceScalar(1))) to TraceString("v")
                    ),
                )
            ),
            watches = emptyList(),
            traceId = null,
            eventId = 0,
        )

        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        val writer = NetworkTraceWriter(context, SimpleTraceContextSavedState(), out, out)
        out.writeTraceHeader(RUNTIME_JVM)
        out.flush()
        val header = bytes.toByteArray()
        bytes.reset()
        out.writeKind(ObjectKind.BLOCK_START)
        out.writeInt(0)
        out.writeKind(ObjectKind.THREAD_NAME)
        out.writeThreadName(0, "main")
        writer.writeTracePoint(tracePoint)
        out.writeKind(ObjectKind.BLOCK_END)
        out.flush()

        val reader = NetworkTraceReader()
        val received = mutableListOf<TraceSnapshotLineBreakpointTracePoint>()
        reader.addTracePointListener { received += it }
        reader.start()
        reader.processMessage(header)
        reader.processMessage(bytes.toByteArray())

        assertEquals(1, received.size)
        val map = received.single().locals.single() as TraceMapSnapshot
        val key = map.capturedEntries.single().first as TraceObjectSnapshot
        assertEquals("org.example.Key", key.className)
        assertEquals("java.util.HashMap", map.className)
    }

    /**
     * Same empty-context setup as the map test above, for a [TraceRedacted] carrying a class descriptor.
     * A redacted value spells its class *inline as a name* rather than as a class-pool id,
     * so it stays resolvable even though `preWriteTraceValue` registers no descriptor for it.
     */
    @Test
    fun redactedValueGetsItsClassDescriptorRegistered() {
        val context = TraceContext()
        context.setThreadName(0, "main")
        val location = context.codeLocationsPool.register(
            LineCodeLocation(StackTraceElement("org.example.Svc", "handle", "Svc.kt", 10), emptyList())
        )
        val secretClass = context.createAndRegisterClassDescriptor("org.example.Secret")
        val tracePoint = TraceSnapshotLineBreakpointTracePoint(
            context = context,
            codeLocationId = location,
            threadId = 0,
            breakpointUuid = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            stackTraceCodeLocationIds = emptyList(),
            currentTimeMillis = 1L,
            locals = listOf(
                TraceRedacted(
                    classDescriptor = secretClass,
                    templateUuid = UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    templateName = "Credentials",
                )
            ),
            watches = emptyList(),
            traceId = null,
            eventId = 0,
        )

        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        val writer = NetworkTraceWriter(context, SimpleTraceContextSavedState(), out, out)
        out.writeTraceHeader(RUNTIME_JVM)
        out.flush()
        val header = bytes.toByteArray()
        bytes.reset()
        out.writeKind(ObjectKind.BLOCK_START)
        out.writeInt(0)
        out.writeKind(ObjectKind.THREAD_NAME)
        out.writeThreadName(0, "main")
        writer.writeTracePoint(tracePoint)
        out.writeKind(ObjectKind.BLOCK_END)
        out.flush()

        val reader = NetworkTraceReader()
        val received = mutableListOf<TraceSnapshotLineBreakpointTracePoint>()
        reader.addTracePointListener { received += it }
        reader.start()
        reader.processMessage(header)
        reader.processMessage(bytes.toByteArray())

        assertEquals(1, received.size)
        val redacted = received.single().locals.single() as TraceRedacted
        assertEquals("org.example.Secret", redacted.capturedClassName)
        assertEquals("Credentials", redacted.templateName)
    }

    @Test
    fun trValueRenderedKeepsItsWireOrdinal() {
        assertEquals(27, TraceValueKind.RENDERED.ordinal)
    }

    @Test
    fun trValueNull() {
        val context = TraceContext()
        // [TraceNull] denotes a captured `null` reference; it round-trips through `TraceValueKind.NULL`.
        assertRoundTrip(TraceNull, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueVoid() {
        val context = TraceContext()
        assertRoundTrip(TraceVoid, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueObjectWithoutFields() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        // A plain TraceObject reference: class descriptor and identity, no captured fields.
        val emptyObject = TraceObject(
            classDescriptor = context.classPool[fooClassId],
            identity = 0xDEADL,
            rendered = null,
        )
        assertRoundTrip(emptyObject, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueObjectWithSingleField() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val singleField = TraceObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identity = 0xCAFEL,
            rendered = null,
            fields = mapOf("x" to TraceScalar(1)),
        )
        assertRoundTrip(singleField, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueObjectWithMultipleMixedFields() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val nestedChild = TraceObject(
            classDescriptor = context.classPool[fooClassId],
            identity = 0xDEADL,
            rendered = null,
        )
        // mix of scalar, string, Unit, null, and nested-object field values
        val mixedFields = TraceObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identity = 0xBEEFL,
            rendered = null,
            fields = mapOf(
                "i" to TraceScalar(1),
                "s" to TraceString("hi"),
                "u" to TraceUnit,
                "child" to nestedChild,
                "missing" to TraceNull,
            ),
        )
        assertRoundTrip(mixedFields, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueObjectDeeplyNested() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        // 3-level nested chain: root → child → grandchild → primitive leaf
        val grandchild = TraceObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identity = 0x11L,
            rendered = null,
            fields = mapOf("leaf" to TraceScalar(true)),
        )
        val child = TraceObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identity = 0x22L,
            rendered = null,
            fields = mapOf("grandchild" to grandchild),
        )
        val root = TraceObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identity = 0x33L,
            rendered = null,
            fields = mapOf("child" to child),
        )
        assertRoundTrip(root, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueArrayGenuinelyEmpty() {
        val context = TraceContext()
        val arrayClassId = context.createAndRegisterClassDescriptor("[Ljava.lang.Object;").id
        // captured elements is empty AND totalSize == 0 (genuinely empty array)
        val genuinelyEmpty = TraceArray(
            classDescriptor = context.classPool[arrayClassId],
            identity = 0xF00DL,
            totalSize = 0,
        )
        assertRoundTrip(genuinelyEmpty, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueArrayNoneCaptured() {
        val context = TraceContext()
        val arrayClassId = context.createAndRegisterClassDescriptor("[Ljava.lang.Object;").id
        // captured elements is empty but totalSize > 0 (array had elements at runtime, none captured)
        val noneCaptured = TraceArray(
            classDescriptor = context.classPool[arrayClassId],
            identity = 0xFACEL,
            totalSize = 50,
        )
        assertRoundTrip(noneCaptured, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueArrayWithElementsNonTruncated() {
        val context = TraceContext()
        val arrayClassId = context.createAndRegisterClassDescriptor("[Ljava.lang.Object;").id
        val elements = listOf(
            TraceScalar(1),
            TraceNull,
            TraceString("elem"),
        )
        // captured.size == totalSize — every element of the runtime array is captured
        val fullyCaptured = TraceArraySnapshot(
            classDescriptor = context.classPool[arrayClassId],
            identity = 0xFACEL,
            totalSize = elements.size,
            capturedElements = elements,
        )
        assertRoundTrip(fullyCaptured, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueArrayWithElementsTruncated() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val arrayClassId = context.createAndRegisterClassDescriptor("[Ljava.lang.Object;").id
        val nestedObject = TraceObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identity = 0xCAFEL,
            rendered = null,
            fields = mapOf("x" to TraceScalar(7)),
        )
        // captured.size < totalSize — the runtime array was larger than what was captured
        val truncated = TraceArraySnapshot(
            classDescriptor = context.classPool[arrayClassId],
            identity = 0xBEEFL,
            totalSize = 100,
            capturedElements = listOf(
                TraceScalar(1),
                TraceNull,
                TraceString("elem"),
                nestedObject,
            ),
        )
        assertRoundTrip(truncated, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueArrayNestedInArray() {
        // Array-of-arrays — exercises the recursive `writeTraceValue` path through a
        // captured element that is itself a `TraceArray`.
        val context = TraceContext()
        val outerClassId = context.createAndRegisterClassDescriptor("[[I").id
        val innerClassId = context.createAndRegisterClassDescriptor("[I").id
        val inner = TraceArraySnapshot(
            classDescriptor = context.classPool[innerClassId],
            identity = 0xAAAL,
            totalSize = 2,
            capturedElements = listOf(
                TraceScalar(1),
                TraceScalar(2),
            ),
        )
        val outer = TraceArraySnapshot(
            classDescriptor = context.classPool[outerClassId],
            identity = 0xBBBL,
            totalSize = 1,
            capturedElements = listOf(inner),
        )
        assertRoundTrip(outer, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueObjectWithArrayField() {
        // Object whose field value is a `TraceArray` — exercises the recursive
        // `writeTraceValue` path through a field of an object.
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val arrayClassId = context.createAndRegisterClassDescriptor("[I").id
        val arrayField = TraceArraySnapshot(
            classDescriptor = context.classPool[arrayClassId],
            identity = 0xCCCL,
            totalSize = 3,
            capturedElements = listOf(
                TraceScalar(10),
                TraceScalar(20),
                TraceScalar(30),
            ),
        )
        val owner = TraceObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identity = 0xDDDL,
            rendered = null,
            fields = mapOf(
                "name" to TraceString("foo"),
                "buckets" to arrayField,
            ),
        )
        assertRoundTrip(owner, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueObjectWithToStringValue() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val obj = TraceObject(
            classDescriptor = context.classPool[fooClassId],
            identity = 0xDEADL,
            rendered = "Owner{id=1}",
        )
        assertRoundTrip(obj, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueObjectSnapshotWithToStringValue() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val snap = TraceObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identity = 0xBEEFL,
            rendered = "Snap[a=1, b=2]",
            fields = mapOf("a" to TraceScalar(1), "b" to TraceScalar(2)),
        )
        assertRoundTrip(snap, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueObjectWithToStringValueNullRoundTrips() {
        // Confirms the null bit on `writeNullableString` / `readNullableString` is wired correctly.
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val obj = TraceObject(
            classDescriptor = context.classPool[fooClassId],
            identity = 0xCAFEL,
            rendered = null,
        )
        assertRoundTrip(obj, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    @Test
    fun trValueNestedObjectSnapshotCarriesToStringPerLevel() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val grandchild = TraceObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identity = 0x11L,
            rendered = "g",
            fields = mapOf("leaf" to TraceScalar(true)),
        )
        val child = TraceObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identity = 0x22L,
            rendered = "c",
            fields = mapOf("grandchild" to grandchild),
        )
        val root = TraceObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identity = 0x33L,
            rendered = "r",
            fields = mapOf("child" to child),
        )
        assertRoundTrip(root, writer = { writeTraceValue(it) }, reader = { readTraceValue(context) })
    }

    // ======== Diff Status ========

    @Test
    fun diffStatus() {
        assertRoundTrip(null, DataOutput::writeDiffStatus, DataInput::readDiffStatus)
        for (status in DiffStatus.entries) {
            assertRoundTrip(status, DataOutput::writeDiffStatus, DataInput::readDiffStatus)
        }
    }

    @Test
    fun readDiffStatusRejectsOutOfRangeOrdinal() {
        val bytes = encodeBytes { writeByte(100) }
        val exception = assertThrows(IOException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use { it.readDiffStatus() }
        }
        assertTrue(
            "expected 'DiffStatus' in message but was: ${exception.message}",
            exception.message!!.contains("DiffStatus"),
        )
    }

    @Test
    fun diffStatusesSet() {
        val cases: List<EnumSet<DiffStatus>?> = listOf(
            null,
            EnumSet.noneOf(DiffStatus::class.java),
            EnumSet.of(DiffStatus.ADDED),
            EnumSet.of(DiffStatus.REMOVED, DiffStatus.ADDED),
            EnumSet.of(DiffStatus.UNCHANGED, DiffStatus.REMOVED, DiffStatus.ADDED, DiffStatus.EDITED_OLD, DiffStatus.EDITED_NEW),
        )
        for (statuses in cases) {
            assertRoundTrip(
                statuses,
                writer = { writeDiffStatusesSet(it) },
                reader = { readDiffStatusesSet() },
            )
        }
    }

    // ======== Trace Points ========

    @Test
    fun tracePointKind() {
        for (kind in TracePointKind.entries) {
            assertRoundTrip(kind, DataOutput::writeTraceTracePointKind, DataInput::readTraceTracePointKind)
        }
    }

    @Test
    fun readTraceTracePointKindRejectsOutOfRangeOrdinal() {
        val bytes = encodeBytes { writeByte(100) }
        val exception = assertThrows(IOException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use { it.readTraceTracePointKind() }
        }
        assertTrue(
            "expected 'TracePointKind' in message but was: ${exception.message}",
            exception.message!!.contains("TracePointKind"),
        )
    }

    // ======== Common Header ========

    @Test
    fun tracePointDiffStatusRoundTrip() {
        // `diffStatus` is part of the common header `writeTraceTracePoint` / `readTraceTracePoint`
        // emit for every kind. Exercise both a leaf (no `childrenDiffStatuses` follow-up byte)
        // and a container (children-diff-statuses set written immediately after).
        val context = TraceContext()
        val variableId = context.variablePool.register(VariableDescriptor(context, "x", Types.INT_TYPE))

        val cases: List<DiffStatus?> = listOf(null) + DiffStatus.entries

        for (status in cases) {
            val leaf = TraceReadLocalVariableTracePoint(
                context = context, threadId = 0, codeLocationId = 0,
                localVariableId = variableId, value = TraceNull, eventId = 0,
            ).also { if (status != null) it.diffStatus = status }
            assertRoundTrip(
                value = leaf,
                writer = { writeTraceTracePoint(it) },
                reader = { readTraceTracePoint(context) as TraceReadLocalVariableTracePoint },
            ) { a, b -> assertEquals(a.diffStatus, b.diffStatus) }

            val container = TraceLoopTracePoint(
                context = context, threadId = 0, codeLocationId = 0, loopId = 0,
            ).also { if (status != null) it.diffStatus = status }
            assertRoundTrip(
                value = container,
                writer = { writeTraceTracePoint(it) },
                reader = { readTraceTracePoint(context) as TraceLoopTracePoint },
            ) { a, b -> assertEquals(a.diffStatus, b.diffStatus) }
        }
    }

    @Test
    fun containerTracePointChildrenDiffStatusesRoundTrip() {
        // `childrenDiffStatuses` is part of the container-tracepoint header only.
        // Cover the same cases as `diffStatusesSet` (null, empty, single, multi).
        val context = TraceContext()
        val cases: List<EnumSet<DiffStatus>?> = listOf(
            null,
            EnumSet.noneOf(DiffStatus::class.java),
            EnumSet.of(DiffStatus.ADDED),
            EnumSet.of(DiffStatus.UNCHANGED, DiffStatus.REMOVED, DiffStatus.ADDED, DiffStatus.EDITED_OLD, DiffStatus.EDITED_NEW),
        )
        for (statuses in cases) {
            val container = TraceLoopTracePoint(
                context = context, threadId = 0, codeLocationId = 0, loopId = 0,
            ).also { it.childrenDiffStatuses = statuses }
            assertRoundTrip(
                value = container,
                writer = { writeTraceTracePoint(it) },
                reader = { readTraceTracePoint(context) as TraceLoopTracePoint },
            ) { a, b -> assertEquals(a.childrenDiffStatuses, b.childrenDiffStatuses) }
        }
    }

    // ======== Field Trace Points ========

    @Test
    fun writeFieldTracePoint() {
        val context = TraceContext()
        val threadId = 1
        val codeLocationId = 9
        val eventId = 201
        val classId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val fieldId = context.fieldPool.register(
            FieldDescriptor(context, classId, "x", Types.INT_TYPE, FieldKind.INSTANCE, isFinal = false, isVolatile = false)
        )
        val original = TraceWriteFieldTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            fieldId = fieldId,
            obj = TraceNull,
            value = TraceScalar(7),
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceWriteFieldTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.fieldId, b.fieldId)
            assertEquals(a.obj, b.obj)
            assertEquals(a.value, b.value)
        }
    }

    @Test
    fun readFieldTracePoint() {
        val context = TraceContext()
        val threadId = 1
        val codeLocationId = 9
        val eventId = 200
        val classId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val fieldId = context.fieldPool.register(
            FieldDescriptor(context, classId, "x", Types.INT_TYPE, FieldKind.INSTANCE, isFinal = false, isVolatile = false)
        )
        val original = TraceReadFieldTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            fieldId = fieldId,
            obj = TraceScalar(1),
            value = TraceScalar(42),
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceReadFieldTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.fieldId, b.fieldId)
            assertEquals(a.obj, b.obj)
            assertEquals(a.value, b.value)
        }
    }

    // ======== Array Trace Points ========

    @Test
    fun writeArrayTracePoint() {
        val context = TraceContext()
        val threadId = 1
        val codeLocationId = 9
        val eventId = 401
        val arrayClassId = context.createAndRegisterClassDescriptor("[I").id
        val array = TraceArraySnapshot(
            classDescriptor = context.classPool[arrayClassId],
            identity = 0xBEEFL,
            totalSize = 1,
            capturedElements = listOf(TraceScalar(0)),
        )
        val original = TraceWriteArrayTracePoint(
            context = context, threadId = threadId, codeLocationId = codeLocationId,
            array = array,
            index = 0,
            value = TraceScalar(7),
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceWriteArrayTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.array, b.array)
            assertEquals(a.index, b.index)
            assertEquals(a.value, b.value)
        }
    }

    @Test
    fun readArrayTracePoint() {
        val context = TraceContext()
        val threadId = 1
        val codeLocationId = 9
        val eventId = 400
        val arrayClassId = context.createAndRegisterClassDescriptor("[I").id
        val array = TraceArraySnapshot(
            classDescriptor = context.classPool[arrayClassId],
            identity = 0xCAFEL,
            totalSize = 3,
            capturedElements = listOf(
                TraceScalar(1),
                TraceScalar(2),
                TraceScalar(3),
            ),
        )
        val original = TraceReadArrayTracePoint(
            context = context, threadId = threadId, codeLocationId = codeLocationId,
            array = array,
            index = 1,
            value = TraceScalar(2),
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceReadArrayTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.array, b.array)
            assertEquals(a.index, b.index)
            assertEquals(a.value, b.value)
        }
    }

    // ======== Local Variable Trace Points ========

    @Test
    fun writeLocalVariableTracePoint() {
        val context = TraceContext()
        val threadId = 1
        val codeLocationId = 9
        val eventId = 301
        val variableId = context.variablePool.register(VariableDescriptor(context, "counter", Types.INT_TYPE))
        val original = TraceWriteLocalVariableTracePoint(
            context = context, threadId = threadId, codeLocationId = codeLocationId,
            localVariableId = variableId,
            value = TraceScalar(99),
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceWriteLocalVariableTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.localVariableId, b.localVariableId)
            assertEquals(a.value, b.value)
        }
    }

    @Test
    fun readLocalVariableTracePoint() {
        val context = TraceContext()
        val threadId = 1
        val codeLocationId = 9
        val eventId = 300
        val variableId = context.variablePool.register(VariableDescriptor(context, "counter", Types.INT_TYPE))
        val original = TraceReadLocalVariableTracePoint(
            context = context, threadId = threadId, codeLocationId = codeLocationId,
            localVariableId = variableId,
            value = TraceScalar(42),
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceReadLocalVariableTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.localVariableId, b.localVariableId)
            assertEquals(a.value, b.value)
        }
    }

    // ======== Method Call Trace Points ========

    @Test
    fun methodCallTracePoint() {
        val context = TraceContext()
        val threadId = 7
        val codeLocationId = 42
        val eventId = 12345
        val classId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val methodId = context.methodPool.register(
            MethodDescriptor(context, classId, MethodSignature("foo", Types.MethodType(Types.INT_TYPE, Types.OBJECT_TYPE)))
        )
        val original = TraceMethodCallTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            methodId = methodId,
            obj = TraceScalar(99),
            parameters = listOf(TraceString("arg"), TraceNull),
            flags = 1,
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceMethodCallTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.methodId, b.methodId)
            assertEquals(a.obj, b.obj)
            assertEquals(a.parameters, b.parameters)
            assertEquals(a.flags, b.flags)
        }
    }

    @Test
    fun methodCallTracePointNoArgs() {
        // No-arg method call — exercises the parameter-list length prefix at the zero boundary.
        val context = TraceContext()
        val classId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val methodId = context.methodPool.register(
            MethodDescriptor(context, classId, MethodSignature("noArgs", Types.MethodType(Types.VOID_TYPE)))
        )
        val original = TraceMethodCallTracePoint(
            context = context,
            threadId = 0,
            codeLocationId = 0,
            methodId = methodId,
            obj = TraceScalar(1),
            parameters = emptyList(),
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceMethodCallTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.methodId, b.methodId)
            assertEquals(a.obj, b.obj)
            assertEquals(a.parameters, b.parameters)
        }
    }

    @Test
    fun methodCallTracePointStaticReceiver() {
        // Static method call (`obj = null`) — exercises the `null` branch of TraceValue
        // serialization for the method-receiver slot.
        val context = TraceContext()
        val classId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val methodId = context.methodPool.register(
            MethodDescriptor(context, classId, MethodSignature("staticFoo", Types.MethodType(Types.VOID_TYPE)))
        )
        val original = TraceMethodCallTracePoint(
            context = context,
            threadId = 0,
            codeLocationId = 0,
            methodId = methodId,
            obj = TraceNull,
            parameters = listOf(TraceScalar(42)),
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceMethodCallTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.methodId, b.methodId)
            assertEquals(a.obj, b.obj)
            assertEquals(a.parameters, b.parameters)
        }
    }

    @Test
    fun methodCallTracePointFooter() {
        val context = TraceContext()
        val classId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val methodId = context.methodPool.register(
            MethodDescriptor(context, classId, MethodSignature("foo", Types.MethodType(Types.VOID_TYPE)))
        )
        fun makeTracePoint() = TraceMethodCallTracePoint(
            context = context,
            threadId = 0,
            codeLocationId = 0,
            methodId = methodId,
            obj = TraceNull,
            parameters = emptyList(),
        )
        val assertFooterEquality: (TraceMethodCallTracePoint, TraceMethodCallTracePoint) -> Unit = { a, b ->
            assertEquals(a.result, b.result)
            assertEquals(a.exceptionClassName, b.exceptionClassName)
        }

        fun roundTripFooter(
            result: TraceValue,
            exceptionClassName: String?,
            target: TraceMethodCallTracePoint = makeTracePoint(),
        ) {
            val source = makeTracePoint().also {
                it.result = result
                it.exceptionClassName = exceptionClassName
            }
            assertRoundTrip(
                value = source,
                writer = { writeMethodCallTracePointFooter(it) },
                reader = { readMethodCallTracePointFooter(context, target); target },
                assertEquality = assertFooterEquality,
            )
        }

        // Populated result + exception class name.
        roundTripFooter(
            result = TraceScalar(42),
            exceptionClassName = "java.lang.IllegalStateException",
        )

        // Void result — the common case for void-return methods.
        roundTripFooter(result = TraceVoid, exceptionClassName = null)

        // Sentinel results that mark unfinished / untracked method tracing.
        roundTripFooter(result = TraceUnfinishedMethodResult, exceptionClassName = null)
        roundTripFooter(result = TraceUntrackedMethodResult, exceptionClassName = null)

        // TraceNull result, null exception class name — the reader must clear a pre-populated target.
        val dirtyTarget = makeTracePoint().also {
            it.result = TraceScalar(999)
            it.exceptionClassName = "leftover"
        }
        roundTripFooter(result = TraceNull, exceptionClassName = null, target = dirtyTarget)
    }

    // ======== Loop Trace Points ========

    @Test
    fun loopTracePoint() {
        val context = TraceContext()
        val threadId = 3
        val codeLocationId = 17
        val eventId = 100
        val loopId = 5
        val original = TraceLoopTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            loopId = loopId,
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceLoopTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.loopId, b.loopId)
        }
    }

    @Test
    fun loopTracePointFooter() {
        val context = TraceContext()
        for (iterations in listOf(0, 1, 100, Int.MAX_VALUE)) {
            val source = TraceLoopTracePoint(context, threadId = 0, codeLocationId = 0, loopId = 0).also {
                it.iterations = iterations
            }
            val target = TraceLoopTracePoint(context, threadId = 0, codeLocationId = 0, loopId = 0)
            assertRoundTrip(
                value = source,
                writer = { writeLoopTracePointFooter(it) },
                reader = { readLoopTracePointFooter(target); target },
            ) { a, b ->
                assertEquals(a.iterations, b.iterations)
            }
        }
    }

    // ======== Loop Iteration Trace Points ========

    @Test
    fun loopIterationTracePoint() {
        val context = TraceContext()
        val threadId = 2
        val codeLocationId = 13
        val eventId = 101
        val loopId = 7
        val loopIteration = 42
        val original = TraceLoopIterationTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            loopId = loopId,
            loopIteration = loopIteration,
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceLoopIterationTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.loopId, b.loopId)
            assertEquals(a.loopIteration, b.loopIteration)
        }
    }

    // ======== Exception Processing Trace Points ========

    @Test
    fun throwTracePoint() {
        val context = TraceContext()
        val threadId = 1
        val codeLocationId = 9
        val eventId = 500
        val classId = context.createAndRegisterClassDescriptor("java.lang.RuntimeException").id
        val exception = TraceObject(
            classDescriptor = context.classPool[classId],
            identity = 0xDEADL,
            rendered = null,
        )
        val original = TraceThrowTracePoint(
            context = context, threadId = threadId, codeLocationId = codeLocationId,
            exception = exception,
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceThrowTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.exception, b.exception)
        }
    }

    @Test
    fun catchTracePoint() {
        val context = TraceContext()
        val threadId = 1
        val codeLocationId = 9
        val eventId = 501
        val classId = context.createAndRegisterClassDescriptor("java.io.IOException").id
        val exception = TraceObject(
            classDescriptor = context.classPool[classId],
            identity = 0xFACEL,
            rendered = null,
        )
        val original = TraceCatchTracePoint(
            context = context, threadId = threadId, codeLocationId = codeLocationId,
            exception = exception,
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceCatchTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.exception, b.exception)
        }
    }

    // ======== Snapshot Line Breakpoint Trace Points ========

    @Test
    fun snapshotLineBreakpointTracePoint() {
        val context = TraceContext()
        val threadId = 1
        val codeLocationId = 9
        val assertFieldsEquality: (TraceSnapshotLineBreakpointTracePoint, TraceSnapshotLineBreakpointTracePoint) -> Unit = { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.breakpointUuid, b.breakpointUuid)
            assertEquals(a.stackTraceCodeLocationIds, b.stackTraceCodeLocationIds)
            assertEquals(a.currentTimeMillis, b.currentTimeMillis)
            assertEquals(a.locals, b.locals)
            assertEquals(a.watches, b.watches)
            assertEquals(a.traceId, b.traceId)
        }

        // Populated case.
        val eventIdPopulated = 600
        val populated = TraceSnapshotLineBreakpointTracePoint(
            context = context,
            codeLocationId = codeLocationId,
            threadId = threadId,
            breakpointUuid = UUID(0x1234L, 0x5678L),
            stackTraceCodeLocationIds = listOf(1, 2, 3),
            currentTimeMillis = 1_700_000_000_000L,
            locals = listOf(
                TraceScalar(1),
                TraceNull,
                TraceString("hi"),
            ),
            watches = listOf(
                TraceString("watch"),
                TraceScalar(42),
            ),
            traceId = "trace-abc-123",
            eventId = eventIdPopulated,
        )
        assertRoundTrip(
            value = populated,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceSnapshotLineBreakpointTracePoint },
            assertEquality = assertFieldsEquality,
        )

        // Null-traceId / empty-stack / empty-locals case.
        val eventIdEmpty = 601
        val empty = TraceSnapshotLineBreakpointTracePoint(
            context = context, codeLocationId = codeLocationId, threadId = threadId,
            breakpointUuid = UUID(0L, 0L),
            stackTraceCodeLocationIds = emptyList(),
            currentTimeMillis = 0L,
            locals = emptyList(),
            watches = emptyList(),
            traceId = null,
            eventId = eventIdEmpty,
        )
        assertRoundTrip(
            value = empty,
            writer = { writeTraceTracePoint(it) },
            reader = { readTraceTracePoint(context) as TraceSnapshotLineBreakpointTracePoint },
            assertEquality = assertFieldsEquality,
        )
    }
}

/**
 * Asserts that `deserialize(serialize(value)) == value` —
 * i.e., that [writer] and [reader] are inverses of each other on [value].
 *
 * Uses [assertEquality] to assert equality between the original and deserialized values,
 * by default, uses [assertEquals] comparing via standard equality relation (i.e., via [equals] method).
 */
private inline fun <T> assertRoundTrip(
    value: T,
    writer: DataOutput.(T) -> Unit,
    reader: DataInput.() -> T,
    assertEquality: (T, T) -> Unit = { expected, actual -> assertEquals(expected, actual) },
) {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { it.writer(value) }

    val byteStream = ByteArrayInputStream(bytes.toByteArray())
    val decoded = DataInputStream(byteStream).use { it.reader() }

    assertEquality(value, decoded)
}

/**
 * Encodes via [block] to a fresh in-memory buffer and returns the resulting bytes.
 * Used when the writer takes no value (e.g. `writeTraceHeader`).
 */
private inline fun encodeBytes(block: DataOutput.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { it.block() }
    return bytes.toByteArray()
}

/**
 * Asserts equality of the four common-header fields that `writeTraceTracePoint` / `readTraceTracePoint`
 * emit for every tracepoint kind: [codeLocationId][TracePoint.codeLocationId],
 * [threadId][TracePoint.threadId], [eventId][TracePoint.eventId], and
 * [diffStatus][TracePoint.diffStatus].
 */
private fun assertCommonHeaderEqual(a: TracePoint, b: TracePoint) {
    assertEquals(a.codeLocationId, b.codeLocationId)
    assertEquals(a.threadId, b.threadId)
    assertEquals(a.eventId, b.eventId)
    assertEquals(a.diffStatus, b.diffStatus)
}
