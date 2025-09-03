/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.debugger

import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.jvm.agent.TraceAgentTransformer
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.LincheckJavaAgent
import org.jetbrains.lincheck.jvm.agent.isInstrumentationInitialized
import org.jetbrains.lincheck.jvm.agent.isTraceJavaAgentAttached
import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import org.jetbrains.lincheck.util.isInTraceRecorderMode
import java.lang.instrument.Instrumentation

object TraceDebuggerAgent {
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        /*
         * Static agent requires Trace Debugger mode.
         * For now, the mode is selected by system property.
         * If you want to run Trace Debugger, you must set `-Dlincheck.traceDebuggerMode=true`.
         *
         * It is an error not to set it.
         */
        // Check if one of the required parameters is set.
        check(isInTraceDebuggerMode) {
            "When lincheck agent is attached to process, " +
            "mode should be selected by VM parameter `lincheck.traceDebuggerMode`. It is expected to be `true`. " +
            "Rerun with `-Dlincheck.traceDebuggerMode=true`"
        }
        // Check that only one parameter is set: one of two must be `false`
        check(!isInTraceDebuggerMode || !isInTraceRecorderMode) {
            "When lincheck agent is attached to process, " +
            "mode should be selected by one of VM parameters `lincheck.traceDebuggerMode` or " +
            "`lincheck.traceRecorderMode`. Only one of them expected to be `true`. " +
            "Rerun with `-Dlincheck.traceDebuggerMode=true` or `-Dlincheck.traceRecorderMode=true` but not both."
        }
        TraceAgentParameters.parseArgs(agentArgs, emptyList())
        LincheckJavaAgent.instrumentation = inst
        isTraceJavaAgentAttached = true
        isInstrumentationInitialized = true
        // We are in Trace debugger mode
        LincheckJavaAgent.instrumentation.addTransformer(TraceAgentTransformer(::TraceDebuggerMethodTransformer), true)
        LincheckJavaAgent.install(InstrumentationMode.TRACE_DEBUGGING)
    }
}