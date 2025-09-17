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
import org.jetbrains.lincheck.jvm.agent.LincheckJavaAgent

internal object TraceRecorderInjections {
    // Method `stopTraceRecorderAndDumpTrace` is called in the finally block of the wrapped test:
    // try { startTraceRecorder(); test() } finally { stopTraceRecorderAndDumpTrace() }
    // and the way try-finally block is instrumented allows for double call of `stopTraceRecorderAndDumpTrace`
    // in case if exception is thrown from it.
    // We want to disallow that explicitly and the simplest way to do that is to add a flag.
    private var alreadyStopped: Boolean = false

    @JvmStatic
    fun prepareTraceRecorder() {
        // Must be first or classes will not be found
        LincheckJavaAgent.install(InstrumentationMode.TRACE_RECORDING)
        // Retransform classes for event tracking
        LincheckJavaAgent.ensureClassHierarchyIsTransformed(TraceAgentParameters.classUnderTraceDebugging)
    }

    @JvmStatic
    fun startTraceRecorder() {
        TraceRecorder.installAndStartTrace(
            className = TraceAgentParameters.classUnderTraceDebugging,
            methodName = TraceAgentParameters.methodUnderTraceDebugging,
            traceFileName = TraceAgentParameters.traceDumpFilePath,
            format = TraceAgentParameters.getArg(TraceRecorderAgent.ARGUMENT_FORMAT),
            formatOption = TraceAgentParameters.getArg(TraceRecorderAgent.ARGUMENT_FOPTION),
            pack = (TraceAgentParameters.getArg(TraceRecorderAgent.ARGUMENT_PACK) ?: "true").toBoolean()
        )
    }

    @JvmStatic
    fun stopTraceRecorderAndDumpTrace() {
        if (alreadyStopped) return
        alreadyStopped = true
        TraceRecorder.finishTraceAndDumpResults()
    }
}
