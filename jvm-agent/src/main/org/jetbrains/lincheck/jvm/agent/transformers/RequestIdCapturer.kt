/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method

/**
 * Registry of all supported request id types.
 * Currently only open telemetry.
 */
internal class SupportedRequestIdCapturers {
    private val capturers: List<RequestIdCapturer> = listOf(
        OpenTelemetryTraceIdCapturer()
    )

    fun loadRequestIdIfAvailable(adapter: GeneratorAdapter) {
        val loaded = capturers.any { it.loadTraceIdIfAvailable(adapter) }
        if (loaded) return
        adapter.visitInsn(Opcodes.ACONST_NULL)
    }
}

/**
 * Abstract base class for capturing distributed tracing request/trace IDs.
 * 
 * This class provides a mechanism to conditionally load trace IDs from various
 * distributed tracing systems (like OpenTelemetry) by generating appropriate
 * bytecode instructions. The availability of the tracing system is checked lazily
 * by verifying that required classes are present on the classpath.
 */
internal abstract class RequestIdCapturer {

    /**
     * List of class names that must be present on the classpath for this capturer to be available.
     * Used to determine if the distributed tracing system is available at runtime.
     */
    protected abstract val shouldContainClasses: List<String>

    /**
     * Generates bytecode instructions to load the trace ID onto the stack.
     * 
     * @param adapter the GeneratorAdapter used to emit bytecode instructions
     */
    protected abstract fun loadTraceId(adapter: GeneratorAdapter)

    /**
     * Conditionally loads the trace ID if the tracing system is available.
     * 
     * @param adapter the GeneratorAdapter used to emit bytecode instructions
     * @return true if the tracing system is available and trace ID was loaded, false otherwise
     */
    fun loadTraceIdIfAvailable(adapter: GeneratorAdapter): Boolean {
        if (isAvailable) loadTraceId(adapter) 
        return isAvailable
    }
    
    private val isAvailable: Boolean by lazy {
        runCatching {
            shouldContainClasses.forEach { className ->
                Class.forName(className, false, this.javaClass.classLoader)
            }
            true
        }.getOrNull() ?: false
    }
}

/**
 * Request ID capturer implementation for OpenTelemetry distributed tracing.
 * 
 * Captures the trace ID from the current OpenTelemetry span context by calling:
 * - Span.current() to get the current span
 * - span.getSpanContext() to get the span context
 * - spanContext.getTraceId() to retrieve the trace ID as a String
 */
internal class OpenTelemetryTraceIdCapturer() : RequestIdCapturer() {
    override val shouldContainClasses: List<String> = listOf("io.opentelemetry.api.trace.Span")
    override fun loadTraceId(adapter: GeneratorAdapter) {
        // Call Span.current() - using visitMethodInsn for interface static method
        adapter.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "io/opentelemetry/api/trace/Span",
            "current",
            "()Lio/opentelemetry/api/trace/Span;",
            true
        )

        // Call span.getSpanContext()
        adapter.invokeInterface(
            Type.getObjectType("io/opentelemetry/api/trace/Span"),
            Method("getSpanContext", "()Lio/opentelemetry/api/trace/SpanContext;")
        )

        // Call spanContext.getTraceId()
        adapter.invokeInterface(
            Type.getObjectType("io/opentelemetry/api/trace/SpanContext"),
            Method("getTraceId", "()Ljava/lang/String;")
        ) 
    }
}