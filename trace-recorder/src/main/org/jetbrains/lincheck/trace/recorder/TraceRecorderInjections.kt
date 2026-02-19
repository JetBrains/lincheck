/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.recorder

import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FOPTION
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FORMAT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_PACK
import org.jetbrains.lincheck.tracer.TracingMode
import org.jetbrains.lincheck.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * This object is glue between bytecode injections into a method under tracing and actual trace recording code.
 *
 * Call to [startTraceRecorder] should be injected as a first instruction of the method under tracing.
 * Call to [stopTraceRecorderAndDumpTrace] should be injected on each exit point of the method under tracing
 * (for either normal exit via `return` or exceptional exit via `throw` or via propagated exception).
 *
 * This is effectively an implementation of the following Java code:
 *
 * ```java
 * methodInQuestion() {
 *   TraceRecorderInjections.startTraceRecorder(...);
 *   try {
 *     <original method code>
 *   } finally {
 *     TraceRecorderInjections.stopTraceRecorderAndDumpTrace();
 *   }
 * }
 * ```
 *
 * This class is used to avoid coupling between instrumented code and `bootstrap.jar`,
 * to enable very early instrumentation before `bootstrap.jar` is added to class.
 */
internal object TraceRecorderInjections {

    private val startCount = AtomicInteger(0)

    @JvmStatic
    fun startTraceRecorder(startingCodeLocationId: Int) {
        try {
            val className = TraceAgentParameters.classUnderTraceDebugging
            val methodName = TraceAgentParameters.methodUnderTraceDebugging
            val thread = Thread.currentThread()

            val startCount = startCount.incrementAndGet()
            Logger.info {
                "Start recording injection from $className::$methodName in thread ${thread.name} is called (startCount=$startCount)" +
                if (startCount > 1) ": skipping start as it was already started earlier" else ""
            }
            if (startCount > 1) return

            Tracer.startTracing(
                TracingMode.parse(
                    outputMode = TraceAgentParameters.getArg(ARGUMENT_FORMAT),
                    outputOption = TraceAgentParameters.getArg(ARGUMENT_FOPTION),
                    outputFilePath = TraceAgentParameters.traceDumpFilePath,
                ),
                TracingSession.StartMode.FromMethod(thread, className, methodName, startingCodeLocationId),
            )
            .ensureNotNull()
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot start Trace Recorder" }
        }
    }

    @JvmStatic
    fun stopTraceRecorderAndDumpTrace() {
        // This method should never throw an exception, or tracer state is undetermined
        try {
            val className = TraceAgentParameters.classUnderTraceDebugging
            val methodName = TraceAgentParameters.methodUnderTraceDebugging
            val thread = Thread.currentThread()

            val traceDumpPath = TraceAgentParameters.traceDumpFilePath ?: error("Trace dump path is not set")
            val pack = (TraceAgentParameters.getArg(ARGUMENT_PACK) ?: "true").toBoolean()

            val startCount = startCount.decrementAndGet()
            Logger.info {
                "Stop recording injection was called from $className::$methodName in thread ${thread.name} is called (startCount=$startCount)" +
                if (startCount > 0) ": skipping stop as recording is still in progress" else ""
            }
            if (startCount < 0) {
                Logger.error { "Recording has been stopped more times than started" }
                return
            }
            if (startCount > 0) return

            Tracer.stopTracing().ensureNotNull()
            Tracer.dumpTrace(traceDumpPath, pack).ensureTrue()
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot stop Trace Recorder"}
        }
    }
}
