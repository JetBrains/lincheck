package org.jetbrains.lincheck.trace

import org.junit.Assert
import org.junit.Test

/**
 * JBRes-7777: `TRException` — the plain `TRReferenceLike` subclass that captures only a
 * [Throwable]'s class and identity (the trace-recorder shape) — and its richer sibling
 * `TRExceptionSnapshot`, which additionally captures the message and full stack trace
 * (the live-debugger snapshot shape).
 */
class TRExceptionTest {

    @Test
    fun `TRValue routes a Throwable to the plain TRException`() {
        val context = TraceContext()
        val throwable = makeDeepStack(8)

        val value = TRValue(context, throwable)

        Assert.assertTrue(
            "TRValue(Throwable) should produce a plain TRException, got ${value::class.simpleName}",
            value is TRException,
        )
    }

    @Test
    fun `TRException is a TRReferenceLike — identity hash code is preserved`() {
        val context = TraceContext()
        val throwable = IllegalStateException("boom")

        val captured = TRValue(context, throwable) as TRException

        // The cast above relies on TRException : TRReferenceLike — this assertion
        // pins that hierarchy contract: a TRException must always be reachable as
        // a TRReferenceLike when traversing the generic TRValue tree.
        val asReference: TRReferenceLike = captured
        Assert.assertEquals(System.identityHashCode(throwable), asReference.identityHashCode)
        Assert.assertEquals(throwable.javaClass.name, asReference.className)
    }

    @Test
    fun `plain TRException carries neither message nor stack trace`() {
        val context = TraceContext()
        val throwable = IllegalStateException("boom")

        // The plain TRException is the trace-recorder route — class + identity only,
        // so it renders as `Class@id` exactly like any other reference object.
        val captured = TRValue(context, throwable) as TRException

        Assert.assertEquals("java.lang.IllegalStateException", captured.className)
        // toString renders the adorned (simple) class name, like every other TRReferenceLike.
        Assert.assertEquals(
            "Plain TRException must render as Class@id with no inline message",
            "IllegalStateException@" + captured.identityHashCode,
            captured.toString(),
        )
    }

    @Test
    fun `TRExceptionSnapshot captures message and full stack trace`() {
        val context = TraceContext()
        val throwable = makeDeepStack(8)
        val expectedFrames = throwable.stackTrace.map { it.toString() }

        val captured = TRExceptionSnapshot(context, throwable)

        Assert.assertEquals(TRString("deep"), captured.message)
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
    fun `TRExceptionSnapshot stack trace is not truncated for deep Throwables`() {
        val context = TraceContext()
        val throwable = makeDeepStack(50)

        val captured = TRExceptionSnapshot(context, throwable)

        Assert.assertTrue(
            "Test stack must be deeper than 10 to exercise the no-truncation path",
            captured.stackTrace.size > 10,
        )
        Assert.assertEquals(throwable.stackTrace.size, captured.stackTrace.size)
    }

    @Test
    fun `TRExceptionSnapshot null message is captured as null`() {
        val context = TraceContext()
        val throwable = RuntimeException()

        val captured = TRExceptionSnapshot(context, throwable)

        // RuntimeException() has a null message.
        Assert.assertEquals(TRNull, captured.message)
    }

    @Test
    fun `plain TRException and snapshot agree on class and identity`() {
        val context = TraceContext()
        val throwable = IllegalArgumentException("foo")

        val plain = TRException(context, throwable)
        val snapshot = TRExceptionSnapshot(context, throwable)

        Assert.assertEquals(plain.className, snapshot.className)
        Assert.assertEquals(plain.identityHashCode, snapshot.identityHashCode)
    }

    @Test
    fun `plain TRException toString is Class@id without message`() {
        val context = TraceContext()
        val throwable = IllegalStateException("boom")

        val captured = TRValue(context, throwable) as TRException
        val rendered = captured.toString()

        Assert.assertTrue(
            "toString should mention the simple class name; got: $rendered",
            rendered.contains("IllegalStateException"),
        )
        Assert.assertTrue(
            "toString should include the identity hash code; got: $rendered",
            rendered.contains(captured.identityHashCode.toString()),
        )
        Assert.assertTrue(
            "Plain TRException toString must not include the message; got: $rendered",
            !rendered.contains("boom"),
        )
    }

    @Test
    fun `TRExceptionSnapshot toString includes class, identity and message`() {
        val context = TraceContext()
        val throwable = IllegalStateException("boom")

        val captured = TRExceptionSnapshot(context, throwable)
        val rendered = captured.toString()

        Assert.assertTrue(
            "toString should mention the simple class name; got: $rendered",
            rendered.contains("IllegalStateException"),
        )
        Assert.assertTrue(
            "toString should include the identity hash code; got: $rendered",
            rendered.contains(captured.identityHashCode.toString()),
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

        val captured = TRValue(context, throwable) as TRException

        // The descriptor for IllegalStateException must be reachable via the same context.
        val resolved = context.classPool[captured.classDescriptor.id]
        Assert.assertNotNull(resolved)
        Assert.assertSame(captured.classDescriptor, resolved)
    }

    private fun makeDeepStack(depth: Int): Throwable =
        if (depth <= 1) RuntimeException("deep") else makeDeepStack(depth - 1)
}
