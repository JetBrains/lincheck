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

import org.jetbrains.lincheck.settings.CompiledRedactionPolicy
import org.jetbrains.lincheck.settings.PolicyOwner
import org.jetbrains.lincheck.settings.RedactionRule
import org.jetbrains.lincheck.settings.RedactionTemplate
import org.jetbrains.lincheck.settings.RedactionTemplateRegistry
import org.jetbrains.lincheck.trace.serialization.writeTRValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger

private class RedactionFields(
    val password: String,
    val nonSecret: String,
    val repeatedSecret: String,
)

private class ScalarSecretFields(
    val secretPin: Int,
    val secretFlag: Boolean,
    val nonSecret: String,
)

private enum class RedactionEnum { TOP_SECRET }

private open class ParentHiddenField {
    @Suppress("unused")
    private val hiddenSecret: String = "parent-secret"
}

private class ChildHiddenField : ParentHiddenField() {
    @Suppress("unused")
    private val hiddenSecret: String = "child-secret"
}

private class ExplodingMessageException : IllegalStateException() {
    override val message: String
        get() = error("message must not be rendered")
}

private class ExplodingField(val password: Throwable)

class SnapshotCapturerTest {
    @Test
    fun `an empty policy selects the plain capturer and captures values unchanged`() {
        val capturer = SnapshotCapturer(TraceContext(), CompiledRedactionPolicy.EMPTY)
        assertTrue(capturer is PlainSnapshotCapturer)

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf("password-shaped-but-no-policy", 42),
            names = listOf("password", "pin"),
            declaringClassName = "example.Controller",
        )

        assertEquals(TRString("password-shaped-but-no-policy"), captured[0])
        assertEquals(TRPrimitive(42), captured[1])
    }

    @Test
    fun `a non-empty policy selects the redacting capturer`() {
        assertTrue(capturer(RedactionRule.ByName("*password*")) is RedactingSnapshotCapturer)
    }

    @Test
    fun `root name match redacts only that slot and does not expand its object`() {
        val capturer = capturer(RedactionRule.ByName("*password*"))
        val secretObject = RedactionFields(
            password = "fixture-secret",
            nonSecret = "visible",
            repeatedSecret = "fixture-secret",
        )

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf(secretObject, "control"),
            names = listOf("customerPassword", "username"),
            declaringClassName = "example.Controller",
        )

        assertTrue(captured[0] is TRRedacted)
        assertEquals(RedactionFields::class.java.name, captured[0].className)
        assertEquals(TRString("control"), captured[1])
    }

    @Test
    fun `field name match happens independently and preserves sibling fields`() {
        val capturer = capturer(
            RedactionRule.ByName(
                variableGlob = "password",
                className = RedactionFields::class.java.name,
            ),
        )

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf(
                RedactionFields(password = "fixture-secret", nonSecret = "visible", repeatedSecret = "other"),
            ),
            names = listOf("customer"),
            declaringClassName = "example.Controller",
        ).single() as TRObjectSnapshot

        assertTrue(captured.fields["password"] is TRRedacted)
        assertEquals(TRString("visible"), captured.fields["nonSecret"])
        assertEquals(TRString("other"), captured.fields["repeatedSecret"])
    }

    @Test
    fun `name rules redact non-string scalar fields`() {
        val capturer = capturer(RedactionRule.ByName("secret*"))

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf(ScalarSecretFields(secretPin = 1234, secretFlag = true, nonSecret = "visible")),
            names = listOf("holder"),
            declaringClassName = "example.Controller",
        ).single() as TRObjectSnapshot

        val pin = captured.fields.getValue("secretPin") as TRRedacted
        val flag = captured.fields.getValue("secretFlag") as TRRedacted
        assertEquals("int", pin.capturedClassName)
        assertEquals("boolean", flag.capturedClassName)
        assertEquals(TRString("visible"), captured.fields["nonSecret"])
    }

    @Test
    fun `value match redacts every scalar occurrence in objects and arrays`() {
        val capturer = capturer(RedactionRule.ByValue("fixture-secret"))
        val fields = capturer.captureNamedExpressionValues(
            values = arrayOf(
                RedactionFields(password = "fixture-secret", nonSecret = "visible", repeatedSecret = "fixture-secret"),
            ),
            names = listOf("customer"),
            declaringClassName = "example.Controller",
        ).single() as TRObjectSnapshot
        val array = capturer.captureNamedExpressionValues(
            values = arrayOf(arrayOf("fixture-secret", "visible", "fixture-secret")),
            names = listOf("values"),
            declaringClassName = "example.Controller",
        ).single() as TRArraySnapshot

        assertTrue(fields.fields["password"] is TRRedacted)
        assertTrue(fields.fields["repeatedSecret"] is TRRedacted)
        assertEquals(TRString("visible"), fields.fields["nonSecret"])
        assertTrue(array.capturedElements[0] is TRRedacted)
        assertEquals(TRString("visible"), array.capturedElements[1])
        assertTrue(array.capturedElements[2] is TRRedacted)
    }

    @Test
    fun `value matching covers non-string scalar types`() {
        val capturer = capturer(RedactionRule.ByValue("""^(4111111111111111|true)$"""))

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf<Any?>(4111111111111111L, true, 42, false),
            names = listOf("cardNumber", "consent", "count", "verified"),
            declaringClassName = "example.Controller",
        )

        assertTrue(captured[0] is TRRedacted)
        assertTrue(captured[1] is TRRedacted)
        assertEquals(TRPrimitive(42), captured[2])
        assertEquals(TRPrimitive(false), captured[3])
    }

    @Test
    fun `an email regex matches addresses embedded in captured strings`() {
        val capturer = capturer(
            RedactionRule.ByValue("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
        )

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf(
                "contact: alice.smith+labels@corp.example.com",
                "no address in here",
            ),
            names = listOf("contact", "note"),
            declaringClassName = "example.Controller",
        )

        assertTrue(captured[0] is TRRedacted)
        assertEquals(TRString("no address in here"), captured[1])
    }

    @Test
    fun `a telephone-number regex matches common phone formats`() {
        val capturer = capturer(
            RedactionRule.ByValue("""\+?\(?[0-9][0-9 ()./-]{7,}[0-9]"""),
        )

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf(
                "+48 601 123 456",
                "(555) 867-5309",
                "room 12",
            ),
            names = listOf("mobile", "landline", "note"),
            declaringClassName = "example.Controller",
        )

        assertTrue(captured[0] is TRRedacted)
        assertTrue(captured[1] is TRRedacted)
        assertEquals(TRString("room 12"), captured[2])
    }

    @Test
    fun `exception redacts only its message`() {
        val capturer = capturer(RedactionRule.ByValue("fixture-secret"))
        val throwable = IllegalStateException("fixture-secret")

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf(throwable),
            names = listOf("failure"),
            declaringClassName = "example.Controller",
        ).single() as TRExceptionSnapshot

        assertTrue(captured.message is TRRedacted)
        assertEquals(IllegalStateException::class.java.name, captured.className)
        assertTrue(captured.stackTrace.isNotEmpty())
    }

    @Test
    fun `absent name metadata fails closed only when name rules are active`() {
        val nameCapturer = capturer(RedactionRule.ByName("*password*"))
        val valueCapturer = capturer(RedactionRule.ByValue("fixture-secret"))

        val missingNameMetadata = nameCapturer.captureNamedExpressionValues(
            values = arrayOf("visible"),
            names = null,
            declaringClassName = "example.Controller",
        )
        val valueOnlyPolicy = valueCapturer.captureNamedExpressionValues(
            values = arrayOf("visible"),
            names = null,
            declaringClassName = "example.Controller",
        )

        val redacted = missingNameMetadata.single() as TRRedacted
        assertEquals("java.lang.String", redacted.capturedClassName)
        assertEquals(null, redacted.templateUuid)
        assertEquals(TRString("visible"), valueOnlyPolicy.single())
    }

    @Test
    fun `mismatched slot-name counts violate the capture precondition`() {
        val values = arrayOf<Any?>("a", "b")
        val misalignedNames = listOf("onlyOne")

        assertThrows(IllegalArgumentException::class.java) {
            capturer(RedactionRule.ByName("*password*"))
                .captureNamedExpressionValues(values, misalignedNames, "example.Controller")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotCapturer(TraceContext(), CompiledRedactionPolicy.EMPTY)
                .captureNamedExpressionValues(values, misalignedNames, "example.Controller")
        }
    }

    @Test
    fun `CharSequence is matched before a content-bearing TRValue is constructed`() {
        val capturer = capturer(RedactionRule.ByValue("fixture-secret"))

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf(StringBuilder("fixture-secret")),
            names = listOf("builder"),
            declaringClassName = "example.Controller",
        ).single()

        assertTrue(captured is TRRedacted)
        assertEquals(StringBuilder::class.java.name, captured.className)
    }

    @Test
    fun `value rules cover primitive big-number enum and class scalar families`() {
        val capturer = capturer(
            RedactionRule.ByValue(
                """(?:^42$|^12345678901234567890$|TOP_SECRET|java\.lang\.String)""",
            ),
        )

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf<Any?>(
                42,
                BigInteger("12345678901234567890"),
                RedactionEnum.TOP_SECRET,
                String::class.java,
                String::class,
            ),
            names = listOf("primitive", "bigInteger", "enum", "javaClass", "kotlinClass"),
            declaringClassName = "example.Controller",
        )

        assertTrue(captured.all { it is TRRedacted })
        assertEquals(
            listOf(
                Int::class.javaObjectType.name,
                BigInteger::class.java.name,
                RedactionEnum::class.java.name,
                Class::class.java.name,
                kotlin.reflect.KClass::class.java.name,
            ),
            captured.map { it.className },
        )
    }

    @Test
    fun `string value matching and stored content share the same bounded representation`() {
        val capturer = capturer(RedactionRule.ByValue("TAIL_SECRET"))
        val prefix = "x".repeat(50)

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf(prefix + "TAIL_SECRET"),
            names = listOf("value"),
            declaringClassName = "example.Controller",
        ).single()

        assertEquals(TRString("$prefix..."), captured)
        assertTrue("Content beyond the capture bound must not be stored", "TAIL_SECRET" !in captured.toString())
    }

    @Test
    fun `big-number values are matched and stored untruncated`() {
        // Truncating a BigInteger's textual form would corrupt the stored value (parse-back),
        // so unlike strings its full representation is used for both matching and storage.
        // Both fixtures exceed the string-capture bound: an anchored match on the secret's
        // 70th digit can only succeed against the untruncated representation.
        val secret = BigInteger("1" + "2".repeat(69))
        val other = BigInteger("9".repeat(80))
        val capturer = capturer(RedactionRule.ByValue("^$secret$"))

        val redacted = capturer.captureNamedExpressionValues(
            values = arrayOf<Any?>(secret),
            names = listOf("secretNumber"),
            declaringClassName = "example.Controller",
        ).single()
        val kept = capturer.captureNamedExpressionValues(
            values = arrayOf<Any?>(other),
            names = listOf("otherNumber"),
            declaringClassName = "example.Controller",
        ).single()

        assertTrue(redacted is TRRedacted)
        assertEquals(TRBigInteger(other), kept)
    }

    @Test
    fun `field name matching precedes a failing leaf representation`() {
        val capturer = capturer(RedactionRule.ByName("password"))

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf(ExplodingField(ExplodingMessageException())),
            names = listOf("holder"),
            declaringClassName = "example.Controller",
        ).single() as TRObjectSnapshot
        val password = captured.fields.getValue("password") as TRRedacted

        assertEquals(Throwable::class.java.name, password.capturedClassName)
        assertEquals("test policy", password.templateName)
    }

    @Test
    fun `a scoped redaction is not overwritten by a hidden parent field`() {
        val capturer = capturer(
            RedactionRule.ByName(
                variableGlob = "hiddenSecret",
                className = ChildHiddenField::class.java.name,
            ),
        )

        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf(ChildHiddenField()),
            names = listOf("holder"),
            declaringClassName = "example.Controller",
        ).single() as TRObjectSnapshot

        assertTrue(captured.fields.getValue("hiddenSecret") is TRRedacted)
    }

    @Test
    fun `raw serialized values contain no matched fixture bytes`() {
        val secret = "UNIQUE_REDACTION_SECRET_829104"
        val capturer = capturer(RedactionRule.ByValue(secret))
        val captured = capturer.captureNamedExpressionValues(
            values = arrayOf(
                RedactionFields(password = secret, nonSecret = "visible", repeatedSecret = secret),
                arrayOf(secret, "visible"),
                IllegalArgumentException(secret),
                StringBuilder(secret),
            ),
            names = listOf("fields", "array", "failure", "builder"),
            declaringClassName = "example.Controller",
        )
        val bytes = ByteArrayOutputStream().use { byteStream ->
            DataOutputStream(byteStream).use { output ->
                captured.forEach(output::writeTRValue)
            }
            byteStream.toByteArray()
        }

        assertTrue(
            "Matched fixture bytes must not enter serialized trace values",
            !bytes.containsSubsequence(secret.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun capturer(vararg rules: RedactionRule): SnapshotCapturer {
        val template = RedactionTemplate.of("test policy", rules.toList())
        val policy = RedactionTemplateRegistry().apply {
            replace(PolicyOwner.STARTUP_FILE, listOf(template))
        }.snapshot()
        return SnapshotCapturer(TraceContext(), policy)
    }
}

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
    if (candidate.isEmpty()) return true
    if (size < candidate.size) return false
    for (start in 0..size - candidate.size) {
        if (candidate.indices.all { offset -> this[start + offset] == candidate[offset] }) return true
    }
    return false
}
