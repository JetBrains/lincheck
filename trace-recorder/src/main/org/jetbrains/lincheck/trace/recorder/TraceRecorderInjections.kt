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
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.util.Logger

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

    @JvmStatic
    fun startTraceRecorder(startingCodeLocationId: Int) {
        try {
            TraceRecorder.install(
                format = TraceAgentParameters.getArg(TraceRecorderAgent.ARGUMENT_FORMAT),
                formatOption = TraceAgentParameters.getArg(TraceRecorderAgent.ARGUMENT_FOPTION),
                traceDumpFilePath = TraceAgentParameters.traceDumpFilePath,
                context = LincheckInstrumentation.context
            )
            TraceRecorder.startRecording(
                className = TraceAgentParameters.classUnderTraceDebugging,
                methodName = TraceAgentParameters.methodUnderTraceDebugging,
                startingCodeLocationId = startingCodeLocationId
            )
        } catch (t: Throwable) {
            Logger.error { "Cannot start Trace Recorder: $t"}
        }
    }

    @JvmStatic
    fun stopTraceRecorderAndDumpTrace() {
        // This method should never throw an exception, or tracer state is undetermined
        try {
            val traceDumpPath = TraceAgentParameters.traceDumpFilePath ?: error("Trace dump path is not set")
            val pack = (TraceAgentParameters.getArg(TraceRecorderAgent.ARGUMENT_PACK) ?: "true").toBoolean()

            TraceRecorder.stopRecording()
            TraceRecorder.dumpTrace(traceDumpPath, pack)
            TraceRecorder.uninstall()
        } catch (t: Throwable) {
            Logger.error { "Cannot stop Trace Recorder: $t"}
        }
    }
}
