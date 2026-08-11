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

import org.jetbrains.lincheck.descriptors.ClassDescriptor
import org.jetbrains.lincheck.settings.CompiledRedactionPolicy
import org.jetbrains.lincheck.settings.LiveDebuggerSettings
import org.jetbrains.lincheck.settings.RedactionMatch
import org.jetbrains.lincheck.util.allDeclaredInstanceFields
import org.jetbrains.lincheck.util.findArrayLength
import org.jetbrains.lincheck.util.findElementsForArray
import org.jetbrains.lincheck.util.findFieldsForObject
import org.jetbrains.lincheck.util.readFieldSafely
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass

/** Captures the value snapshots of one live-debugger tracepoint hit. */
internal interface SnapshotCapturer {
    /**
     * Captures the values of named expression slots (locals or watch expressions).
     *
     * [names] is `null` when the caller has no name metadata for these slots; otherwise it must
     * align 1:1 with [values]. A size mismatch is a violation of an internal
     * instrumentation/runtime invariant and throws [IllegalArgumentException] —
     * the caller is expected to skip the whole tracepoint and report the error.
     */
    fun captureNamedExpressionValues(
        values: Array<Any?>,
        names: List<String>?,
        declaringClassName: String,
    ): List<TRValue>

    /** Captures a single value as a [TRValue] under this capturer's policy. */
    fun captureValue(value: Any?): TRValue
}

/** Selects the capturer for [policy]: the plain implementation when no redaction rules are set. */
internal fun SnapshotCapturer(context: TraceContext, policy: CompiledRedactionPolicy): SnapshotCapturer =
    if (policy.isEmpty) PlainSnapshotCapturer(context) else RedactingSnapshotCapturer(context, policy)

private fun requireAlignedNames(values: Array<Any?>, names: List<String>?) {
    require(names == null || names.size == values.size) {
        "Captured ${values.size} value(s) but ${names?.size} slot name(s); names must align 1:1 with values"
    }
}

/** Capture with no active redaction policy: every value delegates to the [TRValue] factories unchanged. */
internal class PlainSnapshotCapturer(private val context: TraceContext) : SnapshotCapturer {

    override fun captureNamedExpressionValues(
        values: Array<Any?>,
        names: List<String>?,
        declaringClassName: String,
    ): List<TRValue> {
        requireAlignedNames(values, names)
        return values.map(::captureValue)
    }

    override fun captureValue(value: Any?): TRValue = when {
        value == null -> TRNull

        value is Enum<*> -> TRValue(context, value)
        value is Throwable -> TRExceptionSnapshot(context, value)

        value::class.java.isArray -> {
            val arraySize = findArrayLength(value)
            val elementsToRead = minOf(LiveDebuggerSettings.MAX_ARRAY_ELEMENTS, arraySize)
            val elements = findElementsForArray(value, elementsToRead)
            TRArraySnapshot(context, value, arraySize, elements)
        }

        else -> {
            val objectFields = findFieldsForObject(value)
            when {
                objectFields.isNotEmpty() -> TRObjectSnapshot(context, value, objectFields)
                else -> TRValue(context, value)
            }
        }
    }
}

/** Capture that enforces one immutable redaction-policy snapshot on every captured slot. */
internal class RedactingSnapshotCapturer(
    private val context: TraceContext,
    private val policy: CompiledRedactionPolicy,
) : SnapshotCapturer {

    override fun captureNamedExpressionValues(
        values: Array<Any?>,
        names: List<String>?,
        declaringClassName: String,
    ): List<TRValue> {
        requireAlignedNames(values, names)
        // No name metadata at all (e.g. no active-locals info for this code location):
        // with name rules active we cannot prove any slot is safe, so fail closed.
        if (names == null && policy.hasNameRules) {
            return values.map { unattributedRedaction(it) }
        }
        return values.mapIndexed { index, value ->
            val name = names?.get(index)
            val nameRedaction = if (name == null) null else {
                redactionForName(name, declaringClassName, runtimeClassName(value))
            }
            nameRedaction ?: captureValue(value)
        }
    }

    override fun captureValue(value: Any?): TRValue = try {
        captureValueOrThrow(value)
    } catch (_: Throwable) {
        unattributedRedaction(value)
    }

    private fun captureValueOrThrow(value: Any?): TRValue {
        captureScalar(value)?.let { return it }
        if (value is Throwable) return captureException(value)
        val nonNullValue = value ?: return TRNull
        if (nonNullValue.javaClass.isArray) {
            val arraySize = findArrayLength(nonNullValue)
            val elementsToRead = minOf(LiveDebuggerSettings.MAX_ARRAY_ELEMENTS, arraySize)
            val elements = findElementsForArray(nonNullValue, elementsToRead).map(::captureLeafValue)
            val descriptor = context.createAndRegisterClassDescriptor(nonNullValue.javaClass.name)
            return TRArraySnapshot(
                descriptor,
                System.identityHashCode(nonNullValue),
                arraySize,
                elements,
            )
        }

        val fields = captureObjectFields(nonNullValue)
        return if (fields.isNotEmpty()) {
            val descriptor = context.createAndRegisterClassDescriptor(nonNullValue.javaClass.name)
            TRObjectSnapshot(descriptor, System.identityHashCode(nonNullValue), fields)
        } else {
            TRValue(context, nonNullValue)
        }
    }

    /**
     * Captures one object level. Name rules are evaluated against the declaring field before the
     * field is read, so a matched field's value never enters the capture pipeline.
     */
    private fun captureObjectFields(value: Any): Map<String, TRValue> {
        val clazz = value.javaClass
        if (clazz.isPrimitive || clazz == String::class.java) return emptyMap()
        return buildMap {
            for (field in clazz.allDeclaredInstanceFields) {
                val redacted = redactionForName(
                    variableName = field.name,
                    declaringClassName = field.declaringClass.name,
                    capturedClassName = field.type.name,
                )
                if (redacted != null) {
                    put(field.name, redacted)
                    continue
                }
                // A parent may hide a same-named subclass field. Never replace an already-redacted
                // child with a less restrictive capture from a differently scoped declaration.
                if (get(field.name) is TRRedacted) continue
                val fieldRead = readFieldSafely(value, field)
                if (fieldRead.isFailure) continue
                put(field.name, captureLeafValue(fieldRead.getOrNull()))
            }
        }
    }

    /** Captures a nested field/array element without recursively traversing another object level. */
    private fun captureLeafValue(value: Any?): TRValue {
        return try {
            captureScalar(value)
                ?: if (value is Throwable) captureException(value) else TRValue(context, value)
        } catch (_: Throwable) {
            unattributedRedaction(value)
        }
    }

    private fun captureScalar(value: Any?): TRValue? = try {
        captureScalarOrThrow(value)
    } catch (_: Throwable) {
        unattributedRedaction(value)
    }

    /**
     * Value rules are matched against exactly the textual content a capture would store.
     * Only string-like values are truncated before matching ([truncateForCapture]) — for numeric
     * and other parse-back types truncation would corrupt the stored value, and their bounded
     * textual forms are already short.
     */
    private fun captureScalarOrThrow(value: Any?): TRValue? = when (value) {
        null -> TRNull
        is String -> {
            val bounded = value.truncateForCapture()
            redactionForValue(bounded, String::class.java.name) ?: TRString(bounded)
        }
        is Boolean,
        is Byte,
        is Short,
        is Int,
        is Long,
        is Float,
        is Double,
        is Char,
        -> redactionForValue(value.toString(), value.javaClass.name) ?: TRPrimitive(value)
        is BigInteger -> {
            if (value.javaClass != BigInteger::class.java) {
                null
            } else {
                redactionForValue(value.toString(), BigInteger::class.java.name) ?: TRBigInteger(value)
            }
        }
        is BigDecimal -> {
            if (value.javaClass != BigDecimal::class.java) {
                null
            } else {
                redactionForValue(value.toString(), BigDecimal::class.java.name) ?: TRBigDecimal(value)
            }
        }
        is Enum<*> -> redactionForValue(value.name, value.javaClass.name) ?: TREnum(context, value)
        is CharSequence -> {
            val content = capturedCharSequenceContent(value, truncate = true)
            val className = value.javaClass.name
            redactionForValue(content, className) ?: TRCharSequence(
                context.createAndRegisterClassDescriptor(className),
                System.identityHashCode(value),
                content,
            )
        }
        is Class<*> -> redactionForValue(value.name, Class::class.java.name) ?: TRJavaClass(value)
        is KClass<*> -> redactionForValue(value.java.name, KClass::class.java.name) ?: TRKotlinClass(value)
        else -> null
    }

    private fun captureException(throwable: Throwable): TRExceptionSnapshot {
        val descriptor = context.createAndRegisterClassDescriptor(throwable.javaClass.name)
        val message = try {
            throwable.message
                ?.truncateForCapture()
                ?.let { redactionForValue(it, String::class.java.name) ?: TRString(it) }
                ?: TRNull
        } catch (_: Throwable) {
            redactedMarker(String::class.java.name, match = null)
        }
        val stackTrace = runCatching {
            throwable.stackTrace?.map { it.toString() } ?: emptyList()
        }.getOrElse { emptyList() }
        return TRExceptionSnapshot(
            descriptor,
            System.identityHashCode(throwable),
            message,
            stackTrace,
        )
    }

    private fun redactionForName(
        variableName: String,
        declaringClassName: String?,
        capturedClassName: String?,
    ): TRRedacted? = try {
        policy.matchName(variableName, declaringClassName)?.let { redactedMarker(capturedClassName, it) }
    } catch (_: Throwable) {
        redactedMarker(capturedClassName, match = null)
    }

    private fun redactionForValue(
        content: String,
        capturedClassName: String?,
    ): TRRedacted? = try {
        policy.matchValue(content)?.let { redactedMarker(capturedClassName, it) }
    } catch (_: Throwable) {
        redactedMarker(capturedClassName, match = null)
    }

    private fun unattributedRedaction(value: Any?): TRRedacted =
        redactedMarker(runtimeClassName(value), match = null)

    private fun redactedMarker(capturedClassName: String?, match: RedactionMatch?): TRRedacted =
        TRRedacted(classDescriptorOrNull(capturedClassName), match?.templateUuid, match?.templateName)

    private fun classDescriptorOrNull(className: String?): ClassDescriptor? = try {
        className?.let(context::createAndRegisterClassDescriptor)
    } catch (_: Throwable) {
        null
    }

    private fun runtimeClassName(value: Any?): String? = value?.javaClass?.name
}
