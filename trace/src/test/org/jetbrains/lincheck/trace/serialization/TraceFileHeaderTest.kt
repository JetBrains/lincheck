/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.descriptors.MethodCallCodeLocation
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.trace.RUNTIME_JVM
import org.jetbrains.lincheck.trace.TRMethodCallTracePoint
import org.jetbrains.lincheck.trace.TRNull
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.createAndRegisterMethodDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The file reader surfaces the producing runtime, and finds the first record
 * after a header whose trailing runtime string is variable-length.
 */
class TraceFileHeaderTest {

    @Test
    fun lazyReaderReportsTheRuntimeAndSeeksPastTheHeader() {
        LazyTraceReader(recordOneCall().absolutePath).use { reader ->
            assertEquals(RUNTIME_JVM, reader.runtime)
            assertEquals(listOf(1), reader.readTopLevelTracePoints().map { it.size })
        }
    }

    /** Records a one-call trace to a temporary file and returns the data file. */
    private fun recordOneCall(): File {
        val context = TraceContext()
        val methodDescriptor = context.createAndRegisterMethodDescriptor(
            "com.example.SomeClass", "someMethod", Types.MethodType(Types.OBJECT_TYPE)
        )
        val codeLocationId = context.codeLocationsPool.register(
            MethodCallCodeLocation(
                StackTraceElement(methodDescriptor.className, methodDescriptor.methodName, "Example.java", 10),
                accessPath = null,
                argumentNames = null,
            )
        )
        val tracePoint = TRMethodCallTracePoint(
            context = context,
            threadId = 0,
            codeLocationId = codeLocationId,
            methodId = methodDescriptor.id,
            obj = TRNull,
            parameters = emptyList(),
        )

        val traceFile = File.createTempFile("trace_header_test", ".trace").apply { deleteOnExit() }
        File("${traceFile.absolutePath}.$INDEX_FILENAME_EXT").deleteOnExit()
        val collector = FileStreamingTraceCollecting(traceFile.absolutePath, context)
        collector.registerCurrentThread(tracePoint.threadId)
        collector.tracePointCreated(parent = null, tracePoint)
        collector.completeContainerTracePoint(Thread.currentThread(), tracePoint)
        collector.completeThread(Thread.currentThread())
        collector.traceEnded()
        return traceFile
    }
}
