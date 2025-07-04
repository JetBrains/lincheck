/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.traceagent

import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.transformation.isInstrumentationInitialized
import org.jetbrains.kotlinx.lincheck.transformation.isTraceJavaAgentAttached
import org.jetbrains.kotlinx.lincheck.util.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.util.isInTraceRecorderMode
import java.lang.instrument.Instrumentation

/**
 * Agent that is set as `premain` entry class for fat trace debugger jar archive.
 * This archive when attached to the jvm process expects also a `-Dlincheck.traceDebuggerMode=true` or
 * `-Dlincheck.traceRecorderMode=true` in order to enable trace debugging plugin or trace recorder functionality
 * accordingly.
 */
internal object TraceAgent {
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        /*
         * Static agent requires Trace Recorder mode.
         * For now, the mode is selected by system property.
         * If you want to run Trace Recorder, you must set `-Dlincheck.traceRecorderMode=true`.
         *
         * It is an error not to set it.
         */
        // Check if one of the required parameters is set.
        check(isInTraceRecorderMode) {
            "When lincheck agent is attached to process, " +
            "mode should be selected by VM parameter `lincheck.traceRecorderMode`. It is expected to be `true`. " +
            "Rerun with `-Dlincheck.traceRecorderMode=true`."
        }
        // Check that only one parameter is set: one of two must be `false`
        check(!isInTraceDebuggerMode || !isInTraceRecorderMode) {
            "When lincheck agent is attached to process, " +
            "mode should be selected by one of VM parameters `lincheck.traceDebuggerMode` or " +
            "`lincheck.traceRecorderMode`. Only one of them expected to be `true`. " +
            "Rerun with `-Dlincheck.traceDebuggerMode=true` or `-Dlincheck.traceRecorderMode=true` but not both."
        }
        TraceAgentParameters.parseArgs(agentArgs)
        LincheckJavaAgent.instrumentation = inst
        isTraceJavaAgentAttached = true
        isInstrumentationInitialized = true
        // We are in Trace Recorder mode (by exclusion)
        // This adds turn-on and turn-off of tracing to the method in question
        LincheckJavaAgent.instrumentation.addTransformer(TraceAgentTransformer(::TraceRecorderMethodTransformer), true)
        // This prepares instrumentation of all future classes
        TraceRecorderInjections.prepareTraceRecorder()
    }
}