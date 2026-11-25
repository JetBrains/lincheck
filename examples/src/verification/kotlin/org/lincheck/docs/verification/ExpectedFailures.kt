package org.lincheck.docs.verification

import org.jetbrains.lincheck.LincheckAssertionError
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.lincheck.docs.*
import java.lang.reflect.Method
import kotlin.reflect.jvm.javaMethod

/**
 * Checks intentional failures without changing the runnable snippets shown in the user guide.
 */
class ExpectedFailures : InvocationInterceptor {
    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        val expected = failures[invocationContext.executable]
        if (expected == null) {
            invocation.proceed()
        } else {
            // Bug demonstrations need the full search budget to reliably find their intentional failures.
            val defaults = listOf(
                "lincheck.defaultRandomScenarios",
                "lincheck.defaultInvocationsPerScenario",
                "lincheck.defaultInvocations",
            )
                .associateWith { System.clearProperty(it) }
            try {
                expected.verify { invocation.proceed() }
            } finally {
                defaults.forEach { (name, value) ->
                    if (value == null) System.clearProperty(name) else System.setProperty(name, value)
                }
            }
        }
    }

    companion object {
        private val invalidResults = ExpectedFailure(LincheckAssertionError::class.java, "Invalid execution results")
        private val lostIncrement = ExpectedFailure(LincheckAssertionError::class.java, "expected: <2> but was: <1>")

        internal val failures = mapOf(
            CounterTest::test.javaMethod!! to lostIncrement,
            CounterTestWithInvocations::test.javaMethod!! to lostIncrement,
            CounterStructureTest::test.javaMethod!! to invalidResults,
            CounterStructureTest::testWithRandomScenarios.javaMethod!! to invalidResults,
            CounterStructureTest::stressTest.javaMethod!! to invalidResults,
            UnsafeCounterConcurrentTest::modelCheckingTest.javaMethod!! to invalidResults,
            MultiMapTest::modelCheckingTest.javaMethod!! to invalidResults,
            TreiberStackTest::modelCheckingTest.javaMethod!! to invalidResults,
            ConcurrentQueueTest::linearizabilityTest.javaMethod!! to invalidResults,
            ConcurrentHashMapDeadlockTest::test.javaMethod!! to ExpectedFailure(
                LincheckAssertionError::class.java, "All unfinished threads are in deadlock",
            ),
            ConcurrentHashMapTest::modelCheckingTest.javaMethod!! to ExpectedFailure(
                LincheckAssertionError::class.java, "The algorithm should be non-blocking",
            ),
            ValidationTest::test.javaMethod!! to ExpectedFailure(
                LincheckAssertionError::class.java, "Validation function validate has failed",
            ),
            ThreadLocalVariableTest::modelCheckingTest.javaMethod!! to ExpectedFailure(
                LincheckAssertionError::class.java, "java.lang.IllegalStateException: Check failed.",
            ),
            FilesCreateTempFileTest::modelChecking.javaMethod!! to ExpectedFailure(
                LincheckAssertionError::class.java, "File operations are not supported in Lincheck",
            ),
        )
    }
}

internal class ExpectedFailure(private val type: Class<out Throwable>, private val message: String) {
    fun verify(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            if (!type.isInstance(failure) || !failure.message.orEmpty().contains(message)) {
                throw AssertionError("Expected ${type.simpleName} containing '$message', got $failure", failure)
            }
            return
        }
        throw AssertionError("Expected ${type.simpleName} containing '$message', but the example passed")
    }
}
