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

internal object TraceRecorderInjections {
    @JvmStatic
    fun prepareTraceRecorder() {
        // Must be first or classes will not be found
        LincheckInstrumentation.install(InstrumentationMode.TRACE_RECORDING)
    }

    @JvmStatic
    fun startTraceRecorder(startingCodeLocationId: Int) {
        try {
            TraceRecorder.install(
                traceFileName = TraceAgentParameters.traceDumpFilePath,
                format = TraceAgentParameters.getArg(TraceRecorderAgent.ARGUMENT_FORMAT),
                formatOption = TraceAgentParameters.getArg(TraceRecorderAgent.ARGUMENT_FOPTION),
                pack = (TraceAgentParameters.getArg(TraceRecorderAgent.ARGUMENT_PACK) ?: "true").toBoolean(),
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
            TraceRecorder.stopRecording()
            TraceRecorder.dumpTrace()
            TraceRecorder.uninstall()

            LincheckInstrumentation.reportStatistics()
        } catch (t: Throwable) {
            Logger.error { "Cannot stop Trace Recorder: $t"}
        }
    }
}
