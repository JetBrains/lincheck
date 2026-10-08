package org.jetbrains.lincheck.trace

import org.junit.Assert
import org.junit.Test

/**
 * JBRes-7777: `TraceException` — the plain `TraceReferenceLike` subclass that captures only a
 * [Throwable]'s class and identity (the trace-recorder shape) — and its richer sibling
 * `TraceExceptionSnapshot`, which additionally captures the message and full stack trace
 * (the live-debugger snapshot shape).
 */
class TraceExceptionTest {

    @Test
    fun `TraceValue routes a Throwable to the plain TraceException`() {
        val context = TraceContext()
        val throwable = makeDeepStack(8)

        val value = TraceValue(context, throwable)

        Assert.assertTrue(
            "TraceValue(Throwable) should produce a plain TraceException, got ${value::class.simpleName}",
            value is TraceException,
        )
    }

    @Test
    fun `TraceException is a TraceReferenceLike — identity hash code is preserved`() {
        val context = TraceContext()
        val throwable = IllegalStateException("boom")

        val captured = TraceValue(context, throwable) as TraceException

        // The cast above relies on TraceException : TraceReferenceLike — this assertion
        // pins that hierarchy contract: a TraceException must always be reachable as
        // a TraceReferenceLike when traversing the generic TraceValue tree.
        val asReference: TraceReferenceLike = captured
        Assert.assertEquals(System.identityHashCode(throwable).toLong(), asReference.identity)
        Assert.assertEquals(throwable.javaClass.name, asReference.className)
    }

    @Test
    fun `plain TraceException carries neither message nor stack trace`() {
        val context = TraceContext()
        val throwable = IllegalStateException("boom")

        // The plain TraceException is the trace-recorder route — class + identity only,
        // so it renders as `Class@id` exactly like any other reference object.
        val captured = TraceValue(context, throwable) as TraceException

        Assert.assertEquals("java.lang.IllegalStateException", captured.className)
        // toString renders the adorned (simple) class name, like every other TraceReferenceLike.
        Assert.assertEquals(
            "Plain TraceException must render as Class@id with no inline message",
            "IllegalStateException@" + captured.identity,
            captured.toString(),
        )
    }

    @Test
    fun `TraceExceptionSnapshot captures message and full stack trace`() {
        val context = TraceContext()
        val throwable = makeDeepStack(8)
        val expectedFrames = throwable.stackTrace.map { it.toString() }

        val captured = TraceExceptionSnapshot(context, throwable)

        Assert.assertEquals(TraceString("deep"), captured.message)
        Assert.assertEquals(
            "Whole stackTrace must be captured (no truncation)",
            expectedFrames.size,
            captured.stackTrace.size,
        )
        expectedFrames.zip(captured.stackTrace).forEachIndexed { i, (expected, actual) ->
            Assert.assertEquals("Frame #$i mismatch", expected, actual)
        }
    }

    @Test
    fun `TraceExceptionSnapshot stack trace is not truncated for deep Throwables`() {
        val context = TraceContext()
        val throwable = makeDeepStack(50)

        val captured = TraceExceptionSnapshot(context, throwable)

        Assert.assertTrue(
            "Test stack must be deeper than 10 to exercise the no-truncation path",
            captured.stackTrace.size > 10,
        )
        Assert.assertEquals(throwable.stackTrace.size, captured.stackTrace.size)
    }

    @Test
    fun `TraceExceptionSnapshot null message is captured as null`() {
        val context = TraceContext()
        val throwable = RuntimeException()

        val captured = TraceExceptionSnapshot(context, throwable)

        // RuntimeException() has a null message.
        Assert.assertEquals(TraceNull, captured.message)
    }

    @Test
    fun `plain TraceException and snapshot agree on class and identity`() {
        val context = TraceContext()
        val throwable = IllegalArgumentException("foo")

        val plain = TraceException(context, throwable)
        val snapshot = TraceExceptionSnapshot(context, throwable)

        Assert.assertEquals(plain.className, snapshot.className)
        Assert.assertEquals(plain.identity, snapshot.identity)
    }

    @Test
    fun `plain TraceException toString is Class@id without message`() {
        val context = TraceContext()
        val throwable = IllegalStateException("boom")

        val captured = TraceValue(context, throwable) as TraceException
        val rendered = captured.toString()

        Assert.assertTrue(
            "toString should mention the simple class name; got: $rendered",
            rendered.contains("IllegalStateException"),
        )
        Assert.assertTrue(
            "toString should include the identity hash code; got: $rendered",
            rendered.contains(captured.identity.toString()),
        )
        Assert.assertTrue(
            "Plain TraceException toString must not include the message; got: $rendered",
            !rendered.contains("boom"),
        )
    }

    @Test
    fun `TraceExceptionSnapshot toString includes class, identity and message`() {
        val context = TraceContext()
        val throwable = IllegalStateException("boom")

        val captured = TraceExceptionSnapshot(context, throwable)
        val rendered = captured.toString()

        Assert.assertTrue(
            "toString should mention the simple class name; got: $rendered",
            rendered.contains("IllegalStateException"),
        )
        Assert.assertTrue(
            "toString should include the identity hash code; got: $rendered",
            rendered.contains(captured.identity.toString()),
        )
        Assert.assertTrue(
            "toString should include the message; got: $rendered",
            rendered.contains("boom"),
        )
        Assert.assertTrue(
            "Non-redacted messages should retain the prior quoted rendering; got: $rendered",
            rendered.endsWith("(\"boom\")"),
        )
    }

    @Test
    fun `descriptor is registered in the context`() {
        val context = TraceContext()
        val throwable = IllegalStateException("boom")

        val captured = TraceValue(context, throwable) as TraceException

        // The descriptor for IllegalStateException must be reachable via the same context.
        val resolved = context.classPool[captured.classDescriptor.id]
        Assert.assertNotNull(resolved)
        Assert.assertSame(captured.classDescriptor, resolved)
    }

    private fun makeDeepStack(depth: Int): Throwable =
        if (depth <= 1) RuntimeException("deep") else makeDeepStack(depth - 1)
}
