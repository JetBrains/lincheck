/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * `TRValue.typeName(spellings)`.
 *
 * A descriptor-backed value reads the same whichever table is passed — the producer already named
 * the type. A descriptor-less wire kind is spelled by the table, and a table that declines a kind
 * must degrade to `null` rather than fall back to the JVM's name for it.
 */
class TraceRecorderValueTypeNamesTest {
    private val context = TraceContext()

    /**
     * A foreign runtime's table: it names the slots every runtime must have and declines the rest.
     *
     * The interface makes `string`, `boolean`, the four numeric widths and `arbitraryInteger`
     * non-null — every runtime has those concepts — while `byte` / `short` / `char`,
     * `arbitraryDecimal` and the two reflection flavours are nullable, since a runtime may have no
     * such type. A declined slot must read as `null` rather than fall back to the JVM's name.
     */
    private object ForeignSpellings : TypeSpellings {
        override val string: String get() = "foreign-string"
        override val boolean: String get() = "foreign-bool"
        override val byte: String? get() = null
        override val short: String? get() = null
        override val char: String? get() = null
        override val int: String get() = "foreign-int"
        override val long: String get() = "foreign-int"
        override val float: String get() = "foreign-float"
        override val double: String get() = "foreign-float"
        override val arbitraryInteger: String get() = "foreign-int"
        override val arbitraryDecimal: String? get() = null
        override val javaClass: String? get() = null
        override val kotlinClass: String? get() = null
    }

    /** The kinds [ForeignSpellings] declines, so `typeName` must report no type for them. */
    private val declinedByForeignSpellings: List<TRValue> get() = listOf(
        TRScalar(1.toByte()),
        TRScalar(2.toShort()),
        TRScalar('c'),
        TRArbitraryDecimal(BigDecimal("3.14")),
        TRTypeReference(String::class.java),
        TRKotlinTypeReference(String::class),
    )

    // ======== JVM spellings of the descriptor-less wire kinds ========

    @Test
    fun `JVM spells a string and the arbitrary-precision numbers as their boxed classes`() {
        assertEquals("java.lang.String", TRString("hello").typeName(JvmTypeSpellings))
        assertEquals("java.math.BigInteger", TRArbitraryInteger(BigInteger("42")).typeName(JvmTypeSpellings))
        assertEquals("java.math.BigDecimal", TRArbitraryDecimal(BigDecimal("3.14")).typeName(JvmTypeSpellings))
    }

    @Test
    fun `JVM spells each of the eight scalar encodings as its boxed class`() {
        assertEquals("java.lang.Boolean", TRScalar(true).typeName(JvmTypeSpellings))
        assertEquals("java.lang.Byte", TRScalar(1.toByte()).typeName(JvmTypeSpellings))
        assertEquals("java.lang.Short", TRScalar(2.toShort()).typeName(JvmTypeSpellings))
        assertEquals("java.lang.Character", TRScalar('c').typeName(JvmTypeSpellings))
        assertEquals("java.lang.Integer", TRScalar(3).typeName(JvmTypeSpellings))
        assertEquals("java.lang.Long", TRScalar(4L).typeName(JvmTypeSpellings))
        assertEquals("java.lang.Float", TRScalar(5.0f).typeName(JvmTypeSpellings))
        assertEquals("java.lang.Double", TRScalar(6.0).typeName(JvmTypeSpellings))
    }

    @Test
    fun `JVM spells a type reference by its flavour`() {
        assertEquals("java.lang.Class", TRTypeReference(String::class.java).typeName(JvmTypeSpellings))
        assertEquals("kotlin.reflect.KClass", TRKotlinTypeReference(String::class).typeName(JvmTypeSpellings))
    }

    @Test
    fun `a rendered leaf reports no type whichever table reads it`() {
        // A RENDERED value is a leaf with a display string and no type — a client
        // shows the string and no type. Reading a null here as "unknown" and printing it is the bug
        // this pins (`getDebuggerVariableType` used to render the literal text `null`).
        for (spellings in listOf(ForeignSpellings, JvmTypeSpellings)) {
            assertNull(TRRenderedValue("<Api object at 0x7f2a>").typeName(spellings))
        }
    }

    // ======== Coverage gate ========

    /**
     * Every [TRValue] subtype is claimed by one of the three representative-instance lists,
     * so the per-kind expectations in this class speak for the whole hierarchy.
     *
     * The [subtypeTag] `when` is exhaustive, so a new subtype breaks compilation here until someone
     * decides whether it is descriptor-less, descriptor-backed, or a sentinel, and adds an instance.
     */
    @Test
    fun `every TRValue subtype has a representative instance`() {
        val values = descriptorLessValues + descriptorBackedValues.map { it.first } + sentinelValues
        assertEquals(
            "every TRValue subtype needs a representative instance",
            allSubtypeTags,
            values.map { it.subtypeTag() }.toSet(),
        )
    }

    // ======== Descriptor-backed kinds: the producer's name, whichever table reads them ========

    @Test
    fun `descriptor-backed kinds report the producer's class name under the JVM table`() {
        for ((value, expected) in descriptorBackedValues) {
            assertEquals(value.subtypeTag(), expected, value.typeName(JvmTypeSpellings))
        }
    }

    @Test
    fun `descriptor-backed kinds keep the producer's name under a foreign table`() {
        for ((value, expected) in descriptorBackedValues) {
            assertNotNull("${value.subtypeTag()} lost its name", value.typeName(ForeignSpellings))
            assertEquals(value.subtypeTag(), expected, value.typeName(ForeignSpellings))
        }
    }

    // ======== A declining table, and the sentinels ========

    @Test
    fun `a declined slot reads as no type rather than falling back to the JVM's name`() {
        for (value in declinedByForeignSpellings) {
            assertNull(
                "${value.subtypeTag()} ($value) must not fall back to a JVM name",
                value.typeName(ForeignSpellings),
            )
        }
    }

    @Test
    fun `a foreign table supplies the slots every runtime must have`() {
        assertEquals("foreign-string", TRString("hello").typeName(ForeignSpellings))
        assertEquals("foreign-bool", TRScalar(true).typeName(ForeignSpellings))
        assertEquals("foreign-int", TRScalar(3).typeName(ForeignSpellings))
        assertEquals("foreign-float", TRScalar(6.0).typeName(ForeignSpellings))
        assertEquals("foreign-int", TRArbitraryInteger(BigInteger("42")).typeName(ForeignSpellings))
    }

    @Test
    fun `sentinels and markers have no type name whichever table reads them`() {
        for (spellings in listOf(ForeignSpellings, JvmTypeSpellings)) {
            for (value in sentinelValues) {
                assertNull("${value.subtypeTag()} with ${spellings::class.simpleName}", value.typeName(spellings))
            }
        }
    }

    // ======== Representative instances ========

    /** Values identified by their wire kind alone, one per spelling a runtime has to supply itself. */
    private val descriptorLessValues: List<TRValue> get() = listOf(
        TRString("hello"),
        TRScalar(true),
        TRScalar(1.toByte()),
        TRScalar(2.toShort()),
        TRScalar('c'),
        TRScalar(3),
        TRScalar(4L),
        TRScalar(5.0f),
        TRScalar(6.0),
        TRArbitraryInteger(BigInteger("42")),
        TRArbitraryDecimal(BigDecimal("3.14")),
        TRTypeReference(String::class.java),
        TRKotlinTypeReference(String::class),
    )

    /** Values whose type the producing runtime already named, paired with the name it reported. */
    private val descriptorBackedValues: List<Pair<TRValue, String>> get() = listOf(
        TRObject(context, Any()) to "java.lang.Object",
        TRObjectSnapshot(context, ArrayList<String>(), mapOf("size" to 0)) to "java.util.ArrayList",
        TRArray(context, intArrayOf(1, 2, 3)) to "[I",
        TRArraySnapshot(context, arrayOf<Any?>("a"), size = 1, elements = listOf("a")) to "[Ljava.lang.Object;",
        TRMapSnapshot(
            context.createAndRegisterClassDescriptor("java.util.LinkedHashMap"),
            identity = 1L,
            totalSize = 1,
            capturedEntries = listOf(TRString("k") to TRString("v")),
        ) to "java.util.LinkedHashMap",
        TRTextSnapshot(context, StringBuilder("hi")) to "java.lang.StringBuilder",
        TRException(context, IllegalStateException("boom")) to "java.lang.IllegalStateException",
        TRExceptionSnapshot(context, IllegalStateException("boom")) to "java.lang.IllegalStateException",
        TREnum(context, Season.SUMMER) to Season::class.java.name,
        TRRedacted(
            classDescriptor = context.createAndRegisterClassDescriptor("java.lang.String"),
            templateUuid = null,
            templateName = null,
        ) to "java.lang.String",
    )

    /** No value at all, or no type by design. */
    private val sentinelValues: List<TRValue> get() = listOf(
        TRNull,
        TRVoid,
        TRUnit,
        TRUnfinishedMethodResult,
        TRUntrackedMethodResult,
        TRRenderedValue("<Check: backups>"),
    )
}

private enum class Season { SUMMER }

/**
 * Names the [TRValue] subtype for coverage bookkeeping and assertion messages.
 *
 * Exhaustive by construction: a new subtype fails to compile here.
 */
private fun TRValue.subtypeTag(): String = when (this) {
    is TRNull -> "TRNull"
    is TRVoid -> "TRVoid"
    is TRUnit -> "TRUnit"
    is TRScalar -> "TRScalar"
    is TRString -> "TRString"
    is TREnum -> "TREnum"
    is TRArbitraryInteger -> "TRArbitraryInteger"
    is TRArbitraryDecimal -> "TRArbitraryDecimal"
    is TRObject -> "TRObject"
    is TRObjectSnapshot -> "TRObjectSnapshot"
    is TRArray -> "TRArray"
    is TRArraySnapshot -> "TRArraySnapshot"
    is TRMapSnapshot -> "TRMapSnapshot"
    is TRTextSnapshot -> "TRTextSnapshot"
    is TRException -> "TRException"
    is TRExceptionSnapshot -> "TRExceptionSnapshot"
    is TRTypeReference -> "TRTypeReference"
    is TRRedacted -> "TRRedacted"
    is TRRenderedValue -> "TRRenderedValue"
    is TRUnfinishedMethodResult -> "TRUnfinishedMethodResult"
    is TRUntrackedMethodResult -> "TRUntrackedMethodResult"
}

/** Every tag [subtypeTag] can produce; the coverage gate asserts the representative instances cover all of them. */
private val allSubtypeTags = setOf(
    "TRNull",
    "TRVoid",
    "TRUnit",
    "TRScalar",
    "TRString",
    "TREnum",
    "TRArbitraryInteger",
    "TRArbitraryDecimal",
    "TRObject",
    "TRObjectSnapshot",
    "TRArray",
    "TRArraySnapshot",
    "TRMapSnapshot",
    "TRTextSnapshot",
    "TRException",
    "TRExceptionSnapshot",
    "TRTypeReference",
    "TRRedacted",
    "TRRenderedValue",
    "TRUnfinishedMethodResult",
    "TRUntrackedMethodResult",
)
