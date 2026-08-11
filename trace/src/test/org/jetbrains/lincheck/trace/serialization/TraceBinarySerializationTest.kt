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
import org.jetbrains.lincheck.trace.TRArray
import org.jetbrains.lincheck.trace.TRArraySnapshot
import org.jetbrains.lincheck.trace.TRCatchTracePoint
import org.jetbrains.lincheck.trace.TRLoopIterationTracePoint
import org.jetbrains.lincheck.trace.TRLoopTracePoint
import org.jetbrains.lincheck.trace.TRMethodCallTracePoint
import org.jetbrains.lincheck.trace.TRObject
import org.jetbrains.lincheck.trace.TRObjectSnapshot
import org.jetbrains.lincheck.trace.TRPrimitive
import org.jetbrains.lincheck.trace.TRReadArrayTracePoint
import org.jetbrains.lincheck.trace.TRCharSequence
import org.jetbrains.lincheck.trace.TRJavaClass
import org.jetbrains.lincheck.trace.TRKotlinClass
import org.jetbrains.lincheck.trace.TRNull
import org.jetbrains.lincheck.trace.TRString
import org.jetbrains.lincheck.trace.TRBigDecimal
import org.jetbrains.lincheck.trace.TRBigInteger
import org.jetbrains.lincheck.trace.TRException
import org.jetbrains.lincheck.trace.TRExceptionSnapshot
import org.jetbrains.lincheck.trace.TRReadLocalVariableTracePoint
import org.jetbrains.lincheck.trace.TRReadFieldTracePoint
import org.jetbrains.lincheck.trace.TRRedacted
import org.jetbrains.lincheck.trace.TRSnapshotLineBreakpointTracePoint
import org.jetbrains.lincheck.trace.TRThrowTracePoint
import org.jetbrains.lincheck.trace.TRTracePoint
import org.jetbrains.lincheck.trace.TRUnfinishedMethodResult
import org.jetbrains.lincheck.trace.TRUntrackedMethodResult
import org.jetbrains.lincheck.trace.TRValue
import org.jetbrains.lincheck.trace.TRUnit
import org.jetbrains.lincheck.trace.TRVoid
import org.jetbrains.lincheck.trace.TRWriteArrayTracePoint
import org.jetbrains.lincheck.trace.TRWriteLocalVariableTracePoint
import org.jetbrains.lincheck.trace.TRWriteFieldTracePoint
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.createAndRegisterClassDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        val bytes = encodeBytes { writeTraceHeader() }
        DataInputStream(ByteArrayInputStream(bytes)).use { it.checkTraceHeader() }
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
        val bytes = encodeBytes { writeTraceHeader() }
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

        // The wire format only stores `isStatic`, not the full FieldKind, so we drive the matrix
        // by the boolean and rebuild the FieldKind here — making static/instance coverage explicit.
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
        // TRPrimitives whose backing `primitiveValue` is a JVM primitive type.
        val cases: List<TRValue> = listOf(
            TRPrimitive(0.toByte()),
            TRPrimitive(Byte.MIN_VALUE),
            TRPrimitive(Byte.MAX_VALUE),

            TRPrimitive(1234.toShort()),
            TRPrimitive(Short.MIN_VALUE),
            TRPrimitive(Short.MAX_VALUE),

            TRPrimitive(0),
            TRPrimitive(42),
            TRPrimitive(Int.MIN_VALUE),
            TRPrimitive(Int.MAX_VALUE),

            TRPrimitive(0L),
            TRPrimitive(Long.MIN_VALUE),
            TRPrimitive(Long.MAX_VALUE),

            TRPrimitive(3.14f),
            TRPrimitive(Float.MIN_VALUE),
            TRPrimitive(Float.MAX_VALUE),

            TRPrimitive(2.71828),
            TRPrimitive(Double.MIN_VALUE),
            TRPrimitive(Double.MAX_VALUE),

            TRPrimitive('A'),
            TRPrimitive('ж'), // non-ASCII
            TRPrimitive(' '),

            TRPrimitive(true),
            TRPrimitive(false),
        )

        for (value in cases) {
            assertRoundTrip(value, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
        }
    }

    @Test
    fun trValueString() {
        val context = TraceContext()
        val cases: List<TRValue> = listOf(
            TRString("hello"),
            TRString(""),
            TRString("Юникод 🎉"),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
        }
    }

    @Test
    fun trValueBigNumber() {
        val context = TraceContext()
        val cases: List<TRValue> = listOf(
            TRBigInteger("12345"),
            TRBigInteger(""),
            TRBigDecimal("3.14"),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
        }
    }

    @Test
    fun trValueClassReference() {
        val context = TraceContext()
        val cases: List<TRValue> = listOf(
            TRJavaClass("MyClass.class"),
            TRJavaClass("java.lang.String.class"),
            TRJavaClass(""),
            TRKotlinClass("MyClass.kclass"),
            TRKotlinClass("kotlin.String.kclass"),
            TRKotlinClass(""),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
        }
    }

    @Test
    fun trValueCharSequence() {
        val context = TraceContext()
        val sbCd = context.createAndRegisterClassDescriptor("java.lang.StringBuilder")
        val cbCd = context.createAndRegisterClassDescriptor("java.nio.CharBuffer")
        val cases: List<TRValue> = listOf(
            TRCharSequence(sbCd, 0xCAFE, "builder contents"),
            TRCharSequence(sbCd, 0, ""),
            TRCharSequence(cbCd, 0xBEEF, "buffer text"),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
        }
    }

    @Test
    fun trValueException() {
        val context = TraceContext()
        val cd = context.createAndRegisterClassDescriptor("java.lang.IllegalStateException")
        val cases: List<TRValue> = listOf(
            // Plain TRException — the trace-recorder shape: class descriptor + identity only.
            TRException(cd, 0),
            TRException(cd, 0xCAFE),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
        }
    }

    @Test
    fun trValueExceptionSnapshot() {
        val context = TraceContext()
        val cd = context.createAndRegisterClassDescriptor("java.lang.IllegalStateException")
        val cases: List<TRValue> = listOf(
            // No message, no frames — minimum-shape snapshot.
            TRExceptionSnapshot(cd, 0, message = TRNull, stackTrace = emptyList()),
            // Typical shape — message present, a couple of rendered frames.
            TRExceptionSnapshot(
                cd, 0xCAFE,
                message = TRString("boom"),
                stackTrace = listOf(
                    "com.example.Foo.bar(Foo.java:42)",
                    "com.example.Foo.main(Foo.java:7)",
                ),
            ),
            // Empty-string message and one frame — exercises non-null empty path.
            TRExceptionSnapshot(
                cd,
                1234,
                message = TRString(""),
                stackTrace = listOf("com.example.Foo.tail(Foo.java:1)"),
            ),
            TRExceptionSnapshot(
                cd,
                5678,
                message = TRRedacted(
                    classDescriptor = context.createAndRegisterClassDescriptor("java.lang.String"),
                    templateUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                    templateName = "GDPR defaults",
                ),
                stackTrace = emptyList(),
            ),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
        }
    }

    @Test
    fun trValueUnit() {
        val context = TraceContext()
        // [TRUnit] denotes the Kotlin `Unit` singleton; it round-trips through `TRValueKind.UNIT`.
        assertRoundTrip(TRUnit, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
    }

    @Test
    fun trValueRedacted() {
        val context = TraceContext()
        val cases = listOf(
            TRRedacted(
                context.createAndRegisterClassDescriptor("java.lang.String"),
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "GDPR defaults",
            ),
            TRRedacted(context.createAndRegisterClassDescriptor("int"), null, null),
            TRRedacted(null, null, null),
        )
        for (value in cases) {
            assertRoundTrip(value, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
        }
    }

    @Test
    fun trValueNull() {
        val context = TraceContext()
        // [TRNull] denotes a captured `null` reference; it round-trips through `TRValueKind.NULL`.
        assertRoundTrip(TRNull, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
    }

    @Test
    fun trValueVoid() {
        val context = TraceContext()
        // TRVoid singleton — also a special marker.
        assertRoundTrip(TRVoid, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
    }

    @Test
    fun trValueObjectWithoutFields() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        // Regular TRObject reference (classNameId >= 0, no captured fields).
        val emptyObject = TRObject(
            classDescriptor = context.classPool[fooClassId],
            identityHashCode = 0xDEAD,
        )
        assertRoundTrip(emptyObject, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
    }

    @Test
    fun trValueObjectWithSingleField() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val singleField = TRObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identityHashCode = 0xCAFE,
            fields = mapOf("x" to TRPrimitive(1)),
        )
        assertRoundTrip(singleField, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
    }

    @Test
    fun trValueObjectWithMultipleMixedFields() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val nestedChild = TRObject(
            classDescriptor = context.classPool[fooClassId],
            identityHashCode = 0xDEAD,
        )
        // mix of primitive, Unit, null, and nested-object field values
        val mixedFields = TRObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identityHashCode = 0xBEEF,
            fields = mapOf(
                "i" to TRPrimitive(1),
                "s" to TRString("hi"),
                "u" to TRUnit,
                "child" to nestedChild,
                "missing" to TRNull,
            ),
        )
        assertRoundTrip(mixedFields, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
    }

    @Test
    fun trValueObjectDeeplyNested() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        // 3-level nested chain: root → child → grandchild → primitive leaf
        val grandchild = TRObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identityHashCode = 0x11,
            fields = mapOf("leaf" to TRPrimitive(true)),
        )
        val child = TRObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identityHashCode = 0x22,
            fields = mapOf("grandchild" to grandchild),
        )
        val root = TRObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identityHashCode = 0x33,
            fields = mapOf("child" to child),
        )
        assertRoundTrip(root, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
    }

    @Test
    fun trValueArrayGenuinelyEmpty() {
        val context = TraceContext()
        val arrayClassId = context.createAndRegisterClassDescriptor("[Ljava.lang.Object;").id
        // captured elements is empty AND totalSize == 0 (genuinely empty array)
        val genuinelyEmpty = TRArray(
            classDescriptor = context.classPool[arrayClassId],
            identityHashCode = 0xF00D,
            totalSize = 0,
        )
        assertRoundTrip(genuinelyEmpty, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
    }

    @Test
    fun trValueArrayNoneCaptured() {
        val context = TraceContext()
        val arrayClassId = context.createAndRegisterClassDescriptor("[Ljava.lang.Object;").id
        // captured elements is empty but totalSize > 0 (array had elements at runtime, none captured)
        val noneCaptured = TRArray(
            classDescriptor = context.classPool[arrayClassId],
            identityHashCode = 0xFACE,
            totalSize = 50,
        )
        assertRoundTrip(noneCaptured, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
    }

    @Test
    fun trValueArrayWithElementsNonTruncated() {
        val context = TraceContext()
        val arrayClassId = context.createAndRegisterClassDescriptor("[Ljava.lang.Object;").id
        val elements = listOf(
            TRPrimitive(1),
            TRNull,
            TRString("elem"),
        )
        // captured.size == totalSize — every element of the runtime array is captured
        val fullyCaptured = TRArraySnapshot(
            classDescriptor = context.classPool[arrayClassId],
            identityHashCode = 0xFACE,
            totalSize = elements.size,
            capturedElements = elements,
        )
        assertRoundTrip(fullyCaptured, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
    }

    @Test
    fun trValueArrayWithElementsTruncated() {
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val arrayClassId = context.createAndRegisterClassDescriptor("[Ljava.lang.Object;").id
        val nestedObject = TRObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identityHashCode = 0xCAFE,
            fields = mapOf("x" to TRPrimitive(7)),
        )
        // captured.size < totalSize — the runtime array was larger than what was captured
        val truncated = TRArraySnapshot(
            classDescriptor = context.classPool[arrayClassId],
            identityHashCode = 0xBEEF,
            totalSize = 100,
            capturedElements = listOf(
                TRPrimitive(1),
                TRNull,
                TRString("elem"),
                nestedObject,
            ),
        )
        assertRoundTrip(truncated, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
    }

    @Test
    fun trValueArrayNestedInArray() {
        // Array-of-arrays — exercises the recursive `writeTRValue` path through a
        // captured element that is itself a `TRArray`.
        val context = TraceContext()
        val outerClassId = context.createAndRegisterClassDescriptor("[[I").id
        val innerClassId = context.createAndRegisterClassDescriptor("[I").id
        val inner = TRArraySnapshot(
            classDescriptor = context.classPool[innerClassId],
            identityHashCode = 0xAAA,
            totalSize = 2,
            capturedElements = listOf(
                TRPrimitive(1),
                TRPrimitive(2),
            ),
        )
        val outer = TRArraySnapshot(
            classDescriptor = context.classPool[outerClassId],
            identityHashCode = 0xBBB,
            totalSize = 1,
            capturedElements = listOf(inner),
        )
        assertRoundTrip(outer, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
    }

    @Test
    fun trValueObjectWithArrayField() {
        // Object whose field value is a `TRArray` — exercises the recursive
        // `writeTRValue` path through a field of an object.
        val context = TraceContext()
        val fooClassId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val arrayClassId = context.createAndRegisterClassDescriptor("[I").id
        val arrayField = TRArraySnapshot(
            classDescriptor = context.classPool[arrayClassId],
            identityHashCode = 0xCCC,
            totalSize = 3,
            capturedElements = listOf(
                TRPrimitive(10),
                TRPrimitive(20),
                TRPrimitive(30),
            ),
        )
        val owner = TRObjectSnapshot(
            classDescriptor = context.classPool[fooClassId],
            identityHashCode = 0xDDD,
            fields = mapOf(
                "name" to TRString("foo"),
                "buckets" to arrayField,
            ),
        )
        assertRoundTrip(owner, writer = { writeTRValue(it) }, reader = { readTRValue(context) })
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
        for (kind in TRTracePointKind.entries) {
            assertRoundTrip(kind, DataOutput::writeTRTracePointKind, DataInput::readTRTracePointKind)
        }
    }

    @Test
    fun readTRTracePointKindRejectsOutOfRangeOrdinal() {
        val bytes = encodeBytes { writeByte(100) }
        val exception = assertThrows(IOException::class.java) {
            DataInputStream(ByteArrayInputStream(bytes)).use { it.readTRTracePointKind() }
        }
        assertTrue(
            "expected 'TRTracePointKind' in message but was: ${exception.message}",
            exception.message!!.contains("TRTracePointKind"),
        )
    }

    // ======== Common Header ========

    @Test
    fun tracePointDiffStatusRoundTrip() {
        // `diffStatus` is part of the common header `writeTRTracePoint` / `readTRTracePoint`
        // emit for every kind. Exercise both a leaf (no `childrenDiffStatuses` follow-up byte)
        // and a container (children-diff-statuses set written immediately after).
        val context = TraceContext()
        val variableId = context.variablePool.register(VariableDescriptor(context, "x", Types.INT_TYPE))

        val cases: List<DiffStatus?> = listOf(null) + DiffStatus.entries

        for (status in cases) {
            val leaf = TRReadLocalVariableTracePoint(
                context = context, threadId = 0, codeLocationId = 0,
                localVariableId = variableId, value = TRNull, eventId = 0,
            ).also { if (status != null) it.diffStatus = status }
            assertRoundTrip(
                value = leaf,
                writer = { writeTRTracePoint(it) },
                reader = { readTRTracePoint(context) as TRReadLocalVariableTracePoint },
            ) { a, b -> assertEquals(a.diffStatus, b.diffStatus) }

            val container = TRLoopTracePoint(
                context = context, threadId = 0, codeLocationId = 0, loopId = 0,
            ).also { if (status != null) it.diffStatus = status }
            assertRoundTrip(
                value = container,
                writer = { writeTRTracePoint(it) },
                reader = { readTRTracePoint(context) as TRLoopTracePoint },
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
            val container = TRLoopTracePoint(
                context = context, threadId = 0, codeLocationId = 0, loopId = 0,
            ).also { it.childrenDiffStatuses = statuses }
            assertRoundTrip(
                value = container,
                writer = { writeTRTracePoint(it) },
                reader = { readTRTracePoint(context) as TRLoopTracePoint },
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
        val original = TRWriteFieldTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            fieldId = fieldId,
            obj = TRNull,
            value = TRPrimitive(7),
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRWriteFieldTracePoint },
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
        val original = TRReadFieldTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            fieldId = fieldId,
            obj = TRPrimitive(1),
            value = TRPrimitive(42),
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRReadFieldTracePoint },
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
        val array = TRArraySnapshot(
            classDescriptor = context.classPool[arrayClassId],
            identityHashCode = 0xBEEF,
            totalSize = 1,
            capturedElements = listOf(TRPrimitive(0)),
        )
        val original = TRWriteArrayTracePoint(
            context = context, threadId = threadId, codeLocationId = codeLocationId,
            array = array,
            index = 0,
            value = TRPrimitive(7),
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRWriteArrayTracePoint },
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
        val array = TRArraySnapshot(
            classDescriptor = context.classPool[arrayClassId],
            identityHashCode = 0xCAFE,
            totalSize = 3,
            capturedElements = listOf(
                TRPrimitive(1),
                TRPrimitive(2),
                TRPrimitive(3),
            ),
        )
        val original = TRReadArrayTracePoint(
            context = context, threadId = threadId, codeLocationId = codeLocationId,
            array = array,
            index = 1,
            value = TRPrimitive(2),
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRReadArrayTracePoint },
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
        val original = TRWriteLocalVariableTracePoint(
            context = context, threadId = threadId, codeLocationId = codeLocationId,
            localVariableId = variableId,
            value = TRPrimitive(99),
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRWriteLocalVariableTracePoint },
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
        val original = TRReadLocalVariableTracePoint(
            context = context, threadId = threadId, codeLocationId = codeLocationId,
            localVariableId = variableId,
            value = TRPrimitive(42),
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRReadLocalVariableTracePoint },
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
        val original = TRMethodCallTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            methodId = methodId,
            obj = TRPrimitive(99),
            parameters = listOf(TRString("arg"), TRNull),
            flags = 1,
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRMethodCallTracePoint },
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
        val original = TRMethodCallTracePoint(
            context = context,
            threadId = 0,
            codeLocationId = 0,
            methodId = methodId,
            obj = TRPrimitive(1),
            parameters = emptyList(),
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRMethodCallTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.methodId, b.methodId)
            assertEquals(a.obj, b.obj)
            assertEquals(a.parameters, b.parameters)
        }
    }

    @Test
    fun methodCallTracePointStaticReceiver() {
        // Static method call (`obj = null`) — exercises the `null` branch of TRValue
        // serialization for the method-receiver slot.
        val context = TraceContext()
        val classId = context.createAndRegisterClassDescriptor("com.example.Foo").id
        val methodId = context.methodPool.register(
            MethodDescriptor(context, classId, MethodSignature("staticFoo", Types.MethodType(Types.VOID_TYPE)))
        )
        val original = TRMethodCallTracePoint(
            context = context,
            threadId = 0,
            codeLocationId = 0,
            methodId = methodId,
            obj = TRNull,
            parameters = listOf(TRPrimitive(42)),
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRMethodCallTracePoint },
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
        fun makeTracePoint() = TRMethodCallTracePoint(
            context = context,
            threadId = 0,
            codeLocationId = 0,
            methodId = methodId,
            obj = TRNull,
            parameters = emptyList(),
        )
        val assertFooterEquality: (TRMethodCallTracePoint, TRMethodCallTracePoint) -> Unit = { a, b ->
            assertEquals(a.result, b.result)
            assertEquals(a.exceptionClassName, b.exceptionClassName)
        }

        fun roundTripFooter(
            result: TRValue,
            exceptionClassName: String?,
            target: TRMethodCallTracePoint = makeTracePoint(),
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
            result = TRPrimitive(42),
            exceptionClassName = "java.lang.IllegalStateException",
        )

        // Void result — the common case for void-return methods.
        roundTripFooter(result = TRVoid, exceptionClassName = null)

        // Sentinel results that mark unfinished / untracked method tracing.
        roundTripFooter(result = TRUnfinishedMethodResult, exceptionClassName = null)
        roundTripFooter(result = TRUntrackedMethodResult, exceptionClassName = null)

        // TRNull result, null exception class name — the reader must clear a pre-populated target.
        val dirtyTarget = makeTracePoint().also {
            it.result = TRPrimitive(999)
            it.exceptionClassName = "leftover"
        }
        roundTripFooter(result = TRNull, exceptionClassName = null, target = dirtyTarget)
    }

    // ======== Loop Trace Points ========

    @Test
    fun loopTracePoint() {
        val context = TraceContext()
        val threadId = 3
        val codeLocationId = 17
        val eventId = 100
        val loopId = 5
        val original = TRLoopTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            loopId = loopId,
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRLoopTracePoint },
        ) { a, b ->
            assertCommonHeaderEqual(a, b)
            assertEquals(a.loopId, b.loopId)
        }
    }

    @Test
    fun loopTracePointFooter() {
        val context = TraceContext()
        for (iterations in listOf(0, 1, 100, Int.MAX_VALUE)) {
            val source = TRLoopTracePoint(context, threadId = 0, codeLocationId = 0, loopId = 0).also {
                it.iterations = iterations
            }
            val target = TRLoopTracePoint(context, threadId = 0, codeLocationId = 0, loopId = 0)
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
        val original = TRLoopIterationTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            loopId = loopId,
            loopIteration = loopIteration,
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRLoopIterationTracePoint },
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
        val exception = TRObject(
            classDescriptor = context.classPool[classId],
            identityHashCode = 0xDEAD,
        )
        val original = TRThrowTracePoint(
            context = context, threadId = threadId, codeLocationId = codeLocationId,
            exception = exception,
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRThrowTracePoint },
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
        val exception = TRObject(
            classDescriptor = context.classPool[classId],
            identityHashCode = 0xFACE,
        )
        val original = TRCatchTracePoint(
            context = context, threadId = threadId, codeLocationId = codeLocationId,
            exception = exception,
            eventId = eventId,
        )
        assertRoundTrip(
            value = original,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRCatchTracePoint },
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
        val assertFieldsEquality: (TRSnapshotLineBreakpointTracePoint, TRSnapshotLineBreakpointTracePoint) -> Unit = { a, b ->
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
        val populated = TRSnapshotLineBreakpointTracePoint(
            context = context,
            codeLocationId = codeLocationId,
            threadId = threadId,
            breakpointUuid = UUID(0x1234L, 0x5678L),
            stackTraceCodeLocationIds = listOf(1, 2, 3),
            currentTimeMillis = 1_700_000_000_000L,
            locals = listOf(
                TRPrimitive(1),
                TRNull,
                TRString("hi"),
            ),
            watches = listOf(
                TRString("watch"),
                TRPrimitive(42),
            ),
            traceId = "trace-abc-123",
            eventId = eventIdPopulated,
        )
        assertRoundTrip(
            value = populated,
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRSnapshotLineBreakpointTracePoint },
            assertEquality = assertFieldsEquality,
        )

        // Null-traceId / empty-stack / empty-locals case.
        val eventIdEmpty = 601
        val empty = TRSnapshotLineBreakpointTracePoint(
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
            writer = { writeTRTracePoint(it) },
            reader = { readTRTracePoint(context) as TRSnapshotLineBreakpointTracePoint },
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
 * Asserts equality of the four common-header fields that `writeTRTracePoint` / `readTRTracePoint`
 * emit for every tracepoint kind: [codeLocationId][TRTracePoint.codeLocationId],
 * [threadId][TRTracePoint.threadId], [eventId][TRTracePoint.eventId], and
 * [diffStatus][TRTracePoint.diffStatus].
 */
private fun assertCommonHeaderEqual(a: TRTracePoint, b: TRTracePoint) {
    assertEquals(a.codeLocationId, b.codeLocationId)
    assertEquals(a.threadId, b.threadId)
    assertEquals(a.eventId, b.eventId)
    assertEquals(a.diffStatus, b.diffStatus)
}
