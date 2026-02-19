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

import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.TraceAgentTransformer
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_MODE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_EXCLUDE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_INCLUDE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_JMX_MBEAN
import org.jetbrains.lincheck.util.isInLiveDebuggerMode
import org.jetbrains.lincheck.util.TRACE_RECORDER_MODE_PROPERTY
import org.jetbrains.lincheck.trace.recorder.jmx.TraceRecorderJmxController
import org.jetbrains.lincheck.util.isInTraceRecorderMode
import java.lang.instrument.Instrumentation

/**
 * Agent that is set as `premain` entry class for fat trace debugger jar archive.
 * This archive when attached to the jvm process expects also an option
 * `-Dlincheck.traceDebuggerMode=true`, `-Dlincheck.traceRecorderMode=true`, or `-Dlincheck.liveDebuggerMode=true`
 * in order to enable trace debugging plugin, trace recorder functionality, or live debugger functionality accordingly.
 */
internal object TraceRecorderAgent {
    const val ARGUMENT_FORMAT = "format"
    const val ARGUMENT_FOPTION = "formatOption"
    const val ARGUMENT_PACK = "pack"

    // Allowed additional arguments
    private val ADDITIONAL_ARGS = listOf(
        ARGUMENT_MODE,
        ARGUMENT_FORMAT,
        ARGUMENT_FOPTION,
        ARGUMENT_INCLUDE,
        ARGUMENT_EXCLUDE,
        ARGUMENT_PACK,
        ARGUMENT_JMX_MBEAN,
    )

    // entry point for a statically attached java agent
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        // parse and validate arguments and system properties
        parseArguments(agentArgs)

        TraceAgentParameters.validateMode()
        if (!isInLiveDebuggerMode) {
            TraceAgentParameters.validateClassAndMethodArgumentsAreProvided()
        }

        // attach java agent
        LincheckInstrumentation.attachJavaAgentStatically(inst)

        // register JMX MBean if the specified argument was passed
        registerJmxMBeanIfRequested()

        // install trace entry points transformer and instrumentation
        installTraceEntryPointTransformer()
        installInstrumentation()
    }

    // entry point for a dynamically attached java agent
    @JvmStatic
    fun agentmain(agentArgs: String?, inst: Instrumentation) {
        // parse and validate arguments and system properties
        parseArguments(agentArgs)

        if (TraceAgentParameters.getArg(ARGUMENT_MODE) == null) {
            // set trace recorder mode system property by default
            System.setProperty(TRACE_RECORDER_MODE_PROPERTY, "true")
        }
        TraceAgentParameters.validateMode()

        // attach java agent
        LincheckInstrumentation.attachJavaAgentDynamically(inst)

        // register JMX MBean if the specified argument was passed
        registerJmxMBeanIfRequested()

        // install instrumentation and re-transform already loaded classes
        installInstrumentation()
        // TODO: Re-transform already loaded classes if needed
    }

    @JvmStatic
    private fun parseArguments(agentArgs: String?) {
        TraceAgentParameters.parseArgs(agentArgs, ADDITIONAL_ARGS)
    }

    @JvmStatic
    private fun registerJmxMBeanIfRequested() {
        if (TraceAgentParameters.jmxMBeanEnabled) {
            TraceRecorderJmxController.register()
        }
    }

    @JvmStatic
    private fun installTraceEntryPointTransformer() {
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
    }

    @JvmStatic
    private fun installInstrumentation() {
        val mode = when {
            isInTraceRecorderMode -> InstrumentationMode.TRACE_RECORDING
            isInLiveDebuggerMode -> InstrumentationMode.LIVE_DEBUGGING
            else -> error("Unexpected instrumentation mode")
        }
        LincheckInstrumentation.install(mode)
    }
}