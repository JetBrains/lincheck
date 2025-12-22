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
import org.jetbrains.lincheck.jvm.agent.TraceAgentTransformer
import org.jetbrains.lincheck.jvm.agent.LincheckJavaAgent
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_EXCLUDE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_INCLUDE
import org.jetbrains.lincheck.jvm.agent.isInstrumentationInitialized
import org.jetbrains.lincheck.jvm.agent.isTraceJavaAgentAttached
import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import org.jetbrains.lincheck.util.isInTraceRecorderMode
import java.lang.instrument.Instrumentation

/**
 * Agent that is set as `premain` entry class for fat trace debugger jar archive.
 * This archive when attached to the jvm process expects also an option
 * `-Dlincheck.traceDebuggerMode=true` or `-Dlincheck.traceRecorderMode=true`
 * in order to enable trace debugging plugin or trace recorder functionality accordingly.
 */
internal object TraceRecorderAgent {
    const val ARGUMENT_FORMAT = "format"
    const val ARGUMENT_FOPTION = "formatOption"
    const val ARGUMENT_PACK = "pack"

    // Allowed additional arguments
    private val ADDITIONAL_ARGS = listOf(
        ARGUMENT_FORMAT,
        ARGUMENT_FOPTION,
        ARGUMENT_INCLUDE,
        ARGUMENT_EXCLUDE,
        ARGUMENT_PACK,
    )

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
        TraceAgentParameters.parseArgs(agentArgs, ADDITIONAL_ARGS)
        LincheckJavaAgent.instrumentation = inst
        isTraceJavaAgentAttached = true
        isInstrumentationInitialized = true
        // We are in Trace Recorder mode (by exclusion)
        // This adds turn-on and turn-off of tracing to the method in question
        LincheckJavaAgent.instrumentation.addTransformer(TraceAgentTransformer(LincheckJavaAgent.context, ::TraceRecorderMethodTransformer), true)
        // This prepares instrumentation of all future classes
        TraceRecorderInjections.prepareTraceRecorder()
    }
}