/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.trace.recorder

import org.jetbrains.kotlinx.lincheck.trace.agent.TraceAgentParameters
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent

internal object TraceRecorderInjections {

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
            outputMode = TraceAgentParameters.getRestOfArgs().getOrNull(0),
            outputOption = TraceAgentParameters.getRestOfArgs().getOrNull(1)
        )
    }

    @JvmStatic
    fun stopTraceRecorderAndDumpTrace() {
        TraceRecorder.finishTraceAndDumpResults()
    }
}
