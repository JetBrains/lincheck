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

import org.jetbrains.lincheck.jvm.agent.TransformationConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method

/**
 * Regression tests for JBRes-7949: the trace-ID availability probe must use the
 * **user-code class loader** (the loader of the class being instrumented), not
 * the agent's own class loader. The instrumented bytecode references e.g.
 * `io/opentelemetry/api/trace/Span` symbolically, so the JVM resolves it at
 * runtime through the instrumented class's loader; a probe through any other
 * loader can miss a tracer the application has, or — worse — emit OT calls the
 * application can't actually resolve, tripping `NoClassDefFoundError` inside
 * the breakpoint hit.
 */
class TraceIdCapturerTest {

    @Test
    fun emitsOpenTelemetryCallsWhenUserCodeLoaderResolvesSpan() {
        val emitted = emitTraceIdProbe(LoaderWithFakeOtSpan)

        assertTrue(
            "expected the OpenTelemetry probe to emit `Span.current()`, got: ${emitted.toBytecodeText()}",
            emitted.contains(BytecodeMarker.SPAN_CURRENT),
        )
    }

    @Test
    fun emitsNullWhenUserCodeLoaderCannotResolveSpan() {
        val emitted = emitTraceIdProbe(LoaderWithoutFakeOtSpan)

        assertEquals(
            "expected only ACONST_NULL when the user-code loader has no OT tracer, got: ${emitted.toBytecodeText()}",
            listOf(BytecodeMarker.ACONST_NULL),
            emitted,
        )
    }

    /**
     * Drives [TraceIdCapturerRegistry] through a synthetic method body and returns
     * a sequence of markers describing the bytecode it emitted, so we can assert
     * on the *shape* of the output instead of pinning to exact instruction frames.
     */
    private fun emitTraceIdProbe(userCodeClassLoader: ClassLoader): List<BytecodeMarker> {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "Probe",
            null,
            "java/lang/Object",
            null,
        )
        val probeMethod = Method("probe", "()Ljava/lang/Object;")
        val access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
        val rawMv = cw.visitMethod(access, probeMethod.name, probeMethod.descriptor, null, null)
        val adapter = GeneratorAdapter(rawMv, access, probeMethod.name, probeMethod.descriptor)
        adapter.visitCode()

        val configuration = TransformationConfiguration().apply { trackTraceIds = true }
        val registry = TraceIdCapturerRegistry(configuration, userCodeClassLoader)
        registry.loadTraceIdIfAvailable(adapter)

        adapter.returnValue()
        adapter.endMethod()
        cw.visitEnd()

        return cw.toByteArray().collectProbeMarkers()
    }

    private enum class BytecodeMarker {
        ACONST_NULL,
        SPAN_CURRENT,
    }

    private fun ByteArray.collectProbeMarkers(): List<BytecodeMarker> {
        val markers = mutableListOf<BytecodeMarker>()
        ClassReader(this).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String?, desc: String?, sig: String?, ex: Array<out String>?,
            ): MethodVisitor {
                if (name != "probe") return super.visitMethod(access, name, desc, sig, ex)
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitInsn(opcode: Int) {
                        if (opcode == Opcodes.ACONST_NULL) markers += BytecodeMarker.ACONST_NULL
                    }

                    override fun visitMethodInsn(
                        opcode: Int, owner: String?, name: String?, descriptor: String?, isInterface: Boolean,
                    ) {
                        if (opcode == Opcodes.INVOKESTATIC &&
                            owner == "io/opentelemetry/api/trace/Span" &&
                            name == "current"
                        ) markers += BytecodeMarker.SPAN_CURRENT
                    }
                }
            }
        }, 0)
        return markers
    }

    private fun List<BytecodeMarker>.toBytecodeText(): String =
        joinToString(prefix = "[", postfix = "]") { it.name }

    /**
     * Class loader that resolves only `io.opentelemetry.api.trace.Span` to a
     * synthetic empty interface, mimicking an application that bundles
     * OpenTelemetry but is loaded by a child loader the agent doesn't share.
     */
    private object LoaderWithFakeOtSpan : ClassLoader(LoaderWithFakeOtSpan::class.java.classLoader) {
        private val fakeSpanBytes: ByteArray = run {
            val cw = ClassWriter(0)
            cw.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                "io/opentelemetry/api/trace/Span",
                null,
                "java/lang/Object",
                null,
            )
            cw.visitEnd()
            cw.toByteArray()
        }

        override fun findClass(name: String): Class<*> {
            if (name == "io.opentelemetry.api.trace.Span") {
                return defineClass(name, fakeSpanBytes, 0, fakeSpanBytes.size)
            }
            throw ClassNotFoundException(name)
        }
    }

    /**
     * Class loader that explicitly hides `io.opentelemetry.api.trace.Span` even
     * if a parent loader could see it — models the application-doesn't-have-OT
     * half of the JBRes-7949 mismatch.
     */
    private object LoaderWithoutFakeOtSpan : ClassLoader(LoaderWithoutFakeOtSpan::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == "io.opentelemetry.api.trace.Span") {
                throw ClassNotFoundException(name)
            }
            return super.loadClass(name, resolve)
        }
    }
}
