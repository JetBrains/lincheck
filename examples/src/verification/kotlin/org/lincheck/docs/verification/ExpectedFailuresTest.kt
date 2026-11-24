package org.lincheck.docs.verification

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExpectedFailuresTest {
    private val expected = ExpectedFailure(IllegalStateException::class.java, "documented failure")

    @Test
    fun acceptsDocumentedFailure() {
        expected.verify { error("documented failure") }
    }

    @Test
    fun rejectsUnexpectedSuccess() {
        assertFailsWith<AssertionError> { expected.verify {} }
    }

    @Test
    fun rejectsWrongFailureType() {
        val failure = IllegalArgumentException("documented failure")
        val assertion = assertFailsWith<AssertionError> { expected.verify { throw failure } }
        assertSame(failure, assertion.cause)
    }

    @Test
    fun rejectsWrongFailureReason() {
        assertFailsWith<AssertionError> { expected.verify { error("unrelated failure") } }
    }

    @Test
    fun expectedFailuresReferToDiscoveredTests() {
        ExpectedFailures.failures.keys.forEach { method ->
            assertTrue(method.isAnnotationPresent(Test::class.java), "$method is no longer a JUnit test")
        }
    }
}
