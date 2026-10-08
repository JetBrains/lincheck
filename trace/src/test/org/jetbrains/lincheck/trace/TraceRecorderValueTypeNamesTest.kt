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
 * `TraceValue.typeName(spellings)`.
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
    private val declinedByForeignSpellings: List<TraceValue> get() = listOf(
        TraceScalar(1.toByte()),
        TraceScalar(2.toShort()),
        TraceScalar('c'),
        TraceArbitraryDecimal(BigDecimal("3.14")),
        TraceTypeReference(String::class.java),
        TraceKotlinTypeReference(String::class),
    )

    // ======== JVM spellings of the descriptor-less wire kinds ========

    @Test
    fun `JVM spells a string and the arbitrary-precision numbers as their boxed classes`() {
        assertEquals("java.lang.String", TraceString("hello").typeName(JvmTypeSpellings))
        assertEquals("java.math.BigInteger", TraceArbitraryInteger(BigInteger("42")).typeName(JvmTypeSpellings))
        assertEquals("java.math.BigDecimal", TraceArbitraryDecimal(BigDecimal("3.14")).typeName(JvmTypeSpellings))
    }

    @Test
    fun `JVM spells each of the eight scalar encodings as its boxed class`() {
        assertEquals("java.lang.Boolean", TraceScalar(true).typeName(JvmTypeSpellings))
        assertEquals("java.lang.Byte", TraceScalar(1.toByte()).typeName(JvmTypeSpellings))
        assertEquals("java.lang.Short", TraceScalar(2.toShort()).typeName(JvmTypeSpellings))
        assertEquals("java.lang.Character", TraceScalar('c').typeName(JvmTypeSpellings))
        assertEquals("java.lang.Integer", TraceScalar(3).typeName(JvmTypeSpellings))
        assertEquals("java.lang.Long", TraceScalar(4L).typeName(JvmTypeSpellings))
        assertEquals("java.lang.Float", TraceScalar(5.0f).typeName(JvmTypeSpellings))
        assertEquals("java.lang.Double", TraceScalar(6.0).typeName(JvmTypeSpellings))
    }

    @Test
    fun `JVM spells a type reference by its flavour`() {
        assertEquals("java.lang.Class", TraceTypeReference(String::class.java).typeName(JvmTypeSpellings))
        assertEquals("kotlin.reflect.KClass", TraceKotlinTypeReference(String::class).typeName(JvmTypeSpellings))
    }

    @Test
    fun `a rendered leaf reports no type whichever table reads it`() {
        // A RENDERED value is a leaf with a display string and no type — a client
        // shows the string and no type. Reading a null here as "unknown" and printing it is the bug
        // this pins (`getDebuggerVariableType` used to render the literal text `null`).
        for (spellings in listOf(ForeignSpellings, JvmTypeSpellings)) {
            assertNull(TraceRenderedValue("<Api object at 0x7f2a>").typeName(spellings))
        }
    }

    // ======== Coverage gate ========

    /**
     * Every [TraceValue] subtype is claimed by one of the three representative-instance lists,
     * so the per-kind expectations in this class speak for the whole hierarchy.
     *
     * The [subtypeTag] `when` is exhaustive, so a new subtype breaks compilation here until someone
     * decides whether it is descriptor-less, descriptor-backed, or a sentinel, and adds an instance.
     */
    @Test
    fun `every TraceValue subtype has a representative instance`() {
        val values = descriptorLessValues + descriptorBackedValues.map { it.first } + sentinelValues
        assertEquals(
            "every TraceValue subtype needs a representative instance",
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
        assertEquals("foreign-string", TraceString("hello").typeName(ForeignSpellings))
        assertEquals("foreign-bool", TraceScalar(true).typeName(ForeignSpellings))
        assertEquals("foreign-int", TraceScalar(3).typeName(ForeignSpellings))
        assertEquals("foreign-float", TraceScalar(6.0).typeName(ForeignSpellings))
        assertEquals("foreign-int", TraceArbitraryInteger(BigInteger("42")).typeName(ForeignSpellings))
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
    private val descriptorLessValues: List<TraceValue> get() = listOf(
        TraceString("hello"),
        TraceScalar(true),
        TraceScalar(1.toByte()),
        TraceScalar(2.toShort()),
        TraceScalar('c'),
        TraceScalar(3),
        TraceScalar(4L),
        TraceScalar(5.0f),
        TraceScalar(6.0),
        TraceArbitraryInteger(BigInteger("42")),
        TraceArbitraryDecimal(BigDecimal("3.14")),
        TraceTypeReference(String::class.java),
        TraceKotlinTypeReference(String::class),
    )

    /** Values whose type the producing runtime already named, paired with the name it reported. */
    private val descriptorBackedValues: List<Pair<TraceValue, String>> get() = listOf(
        TraceObject(context, Any()) to "java.lang.Object",
        TraceObjectSnapshot(context, ArrayList<String>(), mapOf("size" to 0)) to "java.util.ArrayList",
        TraceArray(context, intArrayOf(1, 2, 3)) to "[I",
        TraceArraySnapshot(context, arrayOf<Any?>("a"), size = 1, elements = listOf("a")) to "[Ljava.lang.Object;",
        TraceMapSnapshot(
            context.createAndRegisterClassDescriptor("java.util.LinkedHashMap"),
            identity = 1L,
            totalSize = 1,
            capturedEntries = listOf(TraceString("k") to TraceString("v")),
        ) to "java.util.LinkedHashMap",
        TraceTextSnapshot(context, StringBuilder("hi")) to "java.lang.StringBuilder",
        TraceException(context, IllegalStateException("boom")) to "java.lang.IllegalStateException",
        TraceExceptionSnapshot(context, IllegalStateException("boom")) to "java.lang.IllegalStateException",
        TraceEnum(context, Season.SUMMER) to Season::class.java.name,
        TraceRedacted(
            classDescriptor = context.createAndRegisterClassDescriptor("java.lang.String"),
            templateUuid = null,
            templateName = null,
        ) to "java.lang.String",
    )

    /** No value at all, or no type by design. */
    private val sentinelValues: List<TraceValue> get() = listOf(
        TraceNull,
        TraceVoid,
        TraceUnit,
        TraceUnfinishedMethodResult,
        TraceUntrackedMethodResult,
        TraceRenderedValue("<Check: backups>"),
    )
}

private enum class Season { SUMMER }

/**
 * Names the [TraceValue] subtype for coverage bookkeeping and assertion messages.
 *
 * Exhaustive by construction: a new subtype fails to compile here.
 */
private fun TraceValue.subtypeTag(): String = when (this) {
    is TraceNull -> "TraceNull"
    is TraceVoid -> "TraceVoid"
    is TraceUnit -> "TraceUnit"
    is TraceScalar -> "TraceScalar"
    is TraceString -> "TraceString"
    is TraceEnum -> "TraceEnum"
    is TraceArbitraryInteger -> "TraceArbitraryInteger"
    is TraceArbitraryDecimal -> "TraceArbitraryDecimal"
    is TraceObject -> "TraceObject"
    is TraceObjectSnapshot -> "TraceObjectSnapshot"
    is TraceArray -> "TraceArray"
    is TraceArraySnapshot -> "TraceArraySnapshot"
    is TraceMapSnapshot -> "TraceMapSnapshot"
    is TraceTextSnapshot -> "TraceTextSnapshot"
    is TraceException -> "TraceException"
    is TraceExceptionSnapshot -> "TraceExceptionSnapshot"
    is TraceTypeReference -> "TraceTypeReference"
    is TraceRedacted -> "TraceRedacted"
    is TraceRenderedValue -> "TraceRenderedValue"
    is TraceUnfinishedMethodResult -> "TraceUnfinishedMethodResult"
    is TraceUntrackedMethodResult -> "TraceUntrackedMethodResult"
}

/** Every tag [subtypeTag] can produce; the coverage gate asserts the representative instances cover all of them. */
private val allSubtypeTags = setOf(
    "TraceNull",
    "TraceVoid",
    "TraceUnit",
    "TraceScalar",
    "TraceString",
    "TraceEnum",
    "TraceArbitraryInteger",
    "TraceArbitraryDecimal",
    "TraceObject",
    "TraceObjectSnapshot",
    "TraceArray",
    "TraceArraySnapshot",
    "TraceMapSnapshot",
    "TraceTextSnapshot",
    "TraceException",
    "TraceExceptionSnapshot",
    "TraceTypeReference",
    "TraceRedacted",
    "TraceRenderedValue",
    "TraceUnfinishedMethodResult",
    "TraceUntrackedMethodResult",
)
