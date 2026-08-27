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
 * `TRValue.typeName(runtime)`.
 *
 * A descriptor-backed value reads the same in every runtime — the producer already named the type.
 * A descriptor-less wire kind is spelled per runtime,
 * and an unrecognised runtime must degrade to `null` rather than claim the JVM spelling.
 */
class TraceRecorderValueTypeNamesTest {
    private val context = TraceContext()

    /** Runtimes the lookup table knows nothing about — including one that is plausible-but-absent. */
    private val unknownRuntimes = listOf("python", "dotnet", "nonesuch", "")

    // ======== JVM spellings of the descriptor-less wire kinds ========

    @Test
    fun `JVM spells a string and the arbitrary-precision numbers as their boxed classes`() {
        assertEquals("java.lang.String", TRString("hello").typeName(RUNTIME_JVM))
        assertEquals("java.math.BigInteger", TRArbitraryInteger(BigInteger("42")).typeName(RUNTIME_JVM))
        assertEquals("java.math.BigDecimal", TRArbitraryDecimal(BigDecimal("3.14")).typeName(RUNTIME_JVM))
    }

    @Test
    fun `JVM spells each of the eight scalar encodings as its boxed class`() {
        assertEquals("java.lang.Boolean", TRScalar(true).typeName(RUNTIME_JVM))
        assertEquals("java.lang.Byte", TRScalar(1.toByte()).typeName(RUNTIME_JVM))
        assertEquals("java.lang.Short", TRScalar(2.toShort()).typeName(RUNTIME_JVM))
        assertEquals("java.lang.Character", TRScalar('c').typeName(RUNTIME_JVM))
        assertEquals("java.lang.Integer", TRScalar(3).typeName(RUNTIME_JVM))
        assertEquals("java.lang.Long", TRScalar(4L).typeName(RUNTIME_JVM))
        assertEquals("java.lang.Float", TRScalar(5.0f).typeName(RUNTIME_JVM))
        assertEquals("java.lang.Double", TRScalar(6.0).typeName(RUNTIME_JVM))
    }

    @Test
    fun `JVM spells a type reference by its flavour`() {
        assertEquals("java.lang.Class", TRTypeReference(String::class.java).typeName(RUNTIME_JVM))
        assertEquals("kotlin.reflect.KClass", TRKotlinTypeReference(String::class).typeName(RUNTIME_JVM))
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

    // ======== Descriptor-backed kinds: the producer's name, in any runtime ========

    @Test
    fun `descriptor-backed kinds report the producer's class name in the JVM runtime`() {
        for ((value, expected) in descriptorBackedValues) {
            assertEquals(value.subtypeTag(), expected, value.typeName(RUNTIME_JVM))
        }
    }

    @Test
    fun `descriptor-backed kinds report the producer's class name for an unknown runtime too`() {
        for (runtime in unknownRuntimes) {
            for ((value, expected) in descriptorBackedValues) {
                assertNotNull("${value.subtypeTag()} lost its name in runtime '$runtime'", value.typeName(runtime))
                assertEquals("${value.subtypeTag()} in runtime '$runtime'", expected, value.typeName(runtime))
            }
        }
    }

    // ======== Unknown runtimes and sentinels ========

    @Test
    fun `an unknown runtime has no spelling for the descriptor-less kinds`() {
        for (runtime in unknownRuntimes) {
            for (value in descriptorLessValues) {
                assertNull(
                    "${value.subtypeTag()} ($value) must not claim a JVM name in runtime '$runtime'",
                    value.typeName(runtime),
                )
            }
        }
    }

    @Test
    fun `sentinels and markers have no type name in any runtime`() {
        for (runtime in unknownRuntimes + RUNTIME_JVM) {
            for (value in sentinelValues) {
                assertNull("${value.subtypeTag()} in runtime '$runtime'", value.typeName(runtime))
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
