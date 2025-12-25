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
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_EXCLUDE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_INCLUDE
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
        check(isInTraceRecorderMode) {
            """
            When trace recorder agent is attached to process mode should be selected by VM parameter. 
            Rerun with `-Dlincheck.traceRecorderMode=true`.
            """
            .trimIndent()
        }
        check(!isInTraceDebuggerMode) {
            """ 
            When trace recorder agent is attached to process, trace debugger mode should be disabled.
            It looks like you have enabled it via `-Dlincheck.traceDebuggerMode=true`.
            Rerun with `-Dlincheck.traceRecorderMode=true` only instead.
            """
            .trimIndent()
        }
        TraceAgentParameters.parseArgs(agentArgs, ADDITIONAL_ARGS)
        LincheckInstrumentation.attachJavaAgentStatically(inst)

        // This transformer adds tracing turn-on and turn-off at the given method entry/exit.
        LincheckInstrumentation.instrumentation.addTransformer(
            /* transformer = */ TraceAgentTransformer(
                LincheckInstrumentation.context,
                ::TraceRecorderMethodTransformer,
                classUnderTracing = TraceAgentParameters.classUnderTraceDebugging,
                methodUnderTracing = TraceAgentParameters.methodUnderTraceDebugging,
            ),
            /* canRetransform = */ true
        )

        // This prepares instrumentation of all future classes
        TraceRecorderInjections.prepareTraceRecorder()
    }
}