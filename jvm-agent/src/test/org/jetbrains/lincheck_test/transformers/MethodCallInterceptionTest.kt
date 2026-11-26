/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.transformers

import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.InstrumentationStrategy
import org.jetbrains.lincheck.jvm.agent.LincheckClassFileTransformer
import org.jetbrains.lincheck.jvm.agent.TransformationConfiguration
import org.jetbrains.lincheck.jvm.agent.TransformationProfile
import org.jetbrains.lincheck.trace.TraceContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import sun.nio.ch.lincheck.EventTracker
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.ResultInterceptor
import sun.nio.ch.lincheck.ThreadDescriptor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

class MethodCallInterceptionTest {
    private val context = TraceContext()
    private val events = mutableListOf<String>()
    private val metadata = Any()

    private val interceptors = mutableListOf<ResultInterceptor>()
    private var before: (String, ResultInterceptor?) -> Unit = { _, _ -> }
    private var afterReturn: (String, Any?, ResultInterceptor?) -> Unit = { _, _, _ -> }
    private var afterException: (String, Throwable, ResultInterceptor?) -> Unit = { _, _, _ -> }

    private lateinit var descriptor: ThreadDescriptor
    private lateinit var fixture: Any

    private val tracker = Proxy.newProxyInstance(
        EventTracker::class.java.classLoader, arrayOf(EventTracker::class.java)
    ) { _, method, args ->
        if (method.name == "shouldInvokeBeforeEvent") return@newProxyInstance false
        val arguments = requireNotNull(args)
        val entering = method.name == "beforeMethodCall"
        val methodId = arguments[if (entering) 2 else 1] as Int
        val name = context.methodPool[methodId].methodName
        val interceptor = arguments.last() as ResultInterceptor?
        when (method.name) {
            "beforeMethodCall" -> {
                events += "before $name"
                if (interceptor != null) {
                    assertFalse(interceptor.isIntercepted)
                    assertNull(interceptor.eventTrackerData)
                    interceptor.eventTrackerData = metadata
                    interceptors += interceptor
                }
                before(name, interceptor)
            }
            "afterMethodCallReturn" -> {
                if (interceptor != null) assertSame(metadata, interceptor.eventTrackerData)
                events += "return $name: ${render(arguments[4])}"
                afterReturn(name, arguments[4], interceptor)
            }
            "afterMethodCallException" -> {
                if (interceptor != null) assertSame(metadata, interceptor.eventTrackerData)
                val exception = arguments[4] as Throwable
                events += "exception $name: ${render(exception)}"
                afterException(name, exception, interceptor)
            }
            else -> error("Unexpected callback: ${method.name}")
        }
        null
    } as EventTracker

    @Before
    fun setUp() {
        fixture = newFixture()
        descriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: ThreadDescriptor(Thread.currentThread()).also {
            ThreadDescriptor.setCurrentThreadDescriptor(it)
        }
        descriptor.eventTracker = tracker
        Injections.enableThreadLocalEventTracking()
        descriptor.enableAnalysis()
    }

    @After
    fun tearDown() {
        descriptor.disableAnalysis()
        descriptor.eventTracker = null
        Injections.disableThreadLocalEventTracking()
    }

    @Test
    fun `successful calls keep their results without an override`() {
        assertEquals(1, call("callInt"))
        assertEquals(2L, call("callLong"))
        assertEquals(3.0, call("callDouble"))
        assertEquals("original", call("callObject"))
        assertNull(call("callVoid"))
        assertEquals(5, calls())
    }

    @Test
    fun `after call replaces primitive reference and null results`() {
        val replacements = mapOf("intResult" to 42, "longResult" to 43L, "doubleResult" to 44.5, "objectResult" to null)
        afterReturn = { name, _, interceptor -> interceptor!!.interceptResult(replacements[name]) }
        assertEquals(42, call("callInt"))
        assertEquals(43L, call("callLong"))
        assertEquals(44.5, call("callDouble"))
        assertNull(call("callObject"))
        afterReturn = { _, _, interceptor -> interceptor!!.interceptResult(metadata) }
        assertSame(metadata, call("callObject"))
        assertEquals(5, calls())
    }

    @Test
    fun `a result intercepted before invocation cannot be intercepted again`() {
        before = { _, interceptor -> interceptor!!.interceptResult(7) }
        afterReturn = { _, result, interceptor ->
            assertEquals(7, result)
            interceptor!!.interceptResult(42)
        }
        val error = failure("callInt")
        assertTrue(error is IllegalStateException)
        assertTrue(error.message!!.startsWith("Result is already intercepted"))
        assertEquals(0, calls())
        assertRecycled()
    }

    @Test
    fun `successful and void calls can become exceptions without a second completion callback`() {
        val replacement = IllegalArgumentException("replacement")
        afterReturn = { _, _, interceptor -> interceptor!!.interceptException(replacement) }
        assertSame(replacement, failure("callInt"))
        assertSame(replacement, failure("callVoid"))
        assertEquals(listOf("before intResult", "return intResult: 1", "before voidResult", "return voidResult: void"), events)
        assertEquals(2, calls())
        assertRecycled()
    }

    @Test
    fun `void override discards a substituted value`() {
        afterReturn = { _, result, interceptor ->
            assertSame(Injections.VOID_RESULT, result)
            interceptor!!.interceptResult(metadata)
        }
        assertNull(call("callVoid"))
        assertEquals(1, calls())
    }

    @Test
    fun `exception can be retained or replaced`() {
        val original = fixture.javaClass.getField("failure").get(fixture)
        assertSame(original, failure("callThrowing"))
        val replacement = IllegalArgumentException("replacement")
        afterException = { _, actual, interceptor ->
            assertSame(original, actual)
            interceptor!!.interceptException(replacement)
        }
        assertSame(replacement, failure("callThrowing"))
        assertEquals(2, calls())
        assertRecycled()
    }

    @Test
    fun `an exception intercepted before invocation cannot be intercepted again`() {
        val initial = IllegalArgumentException("before")
        val replacement = IllegalArgumentException("after")
        before = { _, interceptor -> interceptor!!.interceptException(initial) }
        afterException = { _, actual, interceptor ->
            assertSame(initial, actual)
            interceptor!!.interceptException(replacement)
        }
        val error = failure("callInt")
        assertTrue(error is IllegalStateException)
        assertTrue(error.message!!.startsWith("Result is already intercepted"))
        assertEquals(0, calls())
        assertRecycled()
    }

    @Test
    fun `exception cannot become a normal result`() {
        afterException = { _, _, interceptor -> interceptor!!.interceptResult(42) }
        val error = failure("callThrowing")
        assertTrue(error is IllegalStateException)
        assertEquals("A method call exception cannot be intercepted with a normal result", error.message)
        assertRecycled()
    }

    @Test
    fun `callback failures recycle the interceptor and are not reported twice`() {
        val callbackFailure = IllegalStateException("callback")
        afterReturn = { _, _, _ -> throw callbackFailure }
        assertSame(callbackFailure, failure("callInt"))
        assertEquals(2, events.size)
        assertRecycled()
        afterException = { _, _, _ -> throw callbackFailure }
        assertSame(callbackFailure, failure("callThrowing"))
        assertEquals(4, events.size)
        assertRecycled()
    }

    @Test
    fun `nested calls have independent interceptors and preserve their metadata`() {
        afterReturn = { name, result, interceptor ->
            interceptor!!.interceptResult((result as Int) + if (name == "intResult") 10 else 100)
        }
        assertEquals(111, call("callNested"))
        assertNotSame(interceptors[0], interceptors[1])
        assertEquals(1, calls())
        assertRecycled()
    }

    @Test
    fun `disabled interception preserves results and supplies no interceptor`() {
        descriptor.disableAnalysis()
        fixture = newFixture(intercept = false)
        descriptor.enableAnalysis()
        before = { _, interceptor -> assertNull(interceptor) }
        afterReturn = { _, _, interceptor -> assertNull(interceptor) }
        afterException = { _, _, interceptor -> assertNull(interceptor) }
        assertEquals(1, call("callInt"))
        assertNull(call("callVoid"))
        assertSame(fixture.javaClass.getField("failure").get(fixture), failure("callThrowing"))
    }

    @Test
    fun `uninstrumented threads preserve original outcomes`() {
        descriptor.disableAnalysis()
        assertEquals(1, call("callInt"))
        assertNull(call("callVoid"))
        assertSame(fixture.javaClass.getField("failure").get(fixture), failure("callThrowing"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `constructors are tracked without interception`() {
        before = { _, interceptor -> assertNull(interceptor) }
        afterReturn = { _, _, interceptor -> assertNull(interceptor) }
        assertTrue(call("callConstructor") is StringBuilder)
        assertEquals(2, events.size)
        events.clear()
        fixture.javaClass.getConstructor().newInstance()
        assertEquals(4, events.size)
    }

    @Test
    fun `completion trace represents substituted values and exceptions`() {
        afterReturn = { name, _, interceptor ->
            if (name == "intResult") interceptor!!.interceptResult(42)
            else interceptor!!.interceptException(IllegalArgumentException("void replacement"))
        }
        afterException = { _, _, interceptor -> interceptor!!.interceptException(IllegalStateException("replacement")) }
        events += "caller: ${call("callInt")}"
        events += "caller: ${render(failure("callVoid"))}"
        events += "caller: ${render(failure("callThrowing"))}"
        // Compared line by line: a Windows checkout brings the baseline in with CRLF endings,
        // which a whole-text comparison against the recorded events would report as a diff.
        val expected = javaClass.getResource("/representation/method-call-interception.txt")!!
            .readText().trimEnd().lines()
        assertEquals(expected, events.toList())
    }

    private fun assertRecycled() {
        val reused = Injections.createResultInterceptor(descriptor)
        assertSame(interceptors.first(), reused)
        assertFalse(reused.isIntercepted)
        assertNull(reused.eventTrackerData)
        val nested = Injections.createResultInterceptor(descriptor)
        assertNotSame(reused, nested)
        Injections.recycleResultInterceptor(descriptor, nested)
        Injections.recycleResultInterceptor(descriptor, reused)
    }

    private fun calls(): Int = fixture.javaClass.getField("calls").getInt(fixture)

    private fun call(name: String): Any? = try {
        fixture.javaClass.getMethod(name).invoke(fixture)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    private fun failure(name: String): Throwable {
        try {
            call(name)
        } catch (e: Throwable) {
            return e
        }
        error("Expected $name to throw")
    }

    private fun render(value: Any?): String = when (value) {
        Injections.VOID_RESULT -> "void"
        is Throwable -> "${value.javaClass.simpleName}(${value.message})"
        else -> value.toString()
    }

    private fun newFixture(intercept: Boolean = true): Any {
        val name = MethodCallInterceptionFixture::class.java.name
        val internalName = name.replace('.', '/')
        val bytes = javaClass.getResourceAsStream("/$internalName.class")!!.use { it.readBytes() }
        val profile = object : TransformationProfile {
            override fun shouldTransform(className: String, loader: ClassLoader?) = className == name
            override fun getMethodConfiguration(className: String, methodName: String, descriptor: String) =
                TransformationConfiguration(
                    trackMethodCalls = methodName.startsWith("call") || methodName == "<init>",
                    interceptMethodCallResults = intercept,
                    interceptMethodCallResultsAfterInvocation = intercept,
                    trackConstructorCalls = true,
                )
        }
        val transformer = LincheckClassFileTransformer(
            InstrumentationMode.MODEL_CHECKING, InstrumentationStrategy.EAGER, profile, mutableMapOf(), context
        )
        val transformed = transformer.doTransform(javaClass.classLoader, internalName, bytes)
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun defineFixture(): Class<*> = defineClass(name, transformed, 0, transformed.size)
        }
        return loader.defineFixture().getConstructor().newInstance()
    }
}
