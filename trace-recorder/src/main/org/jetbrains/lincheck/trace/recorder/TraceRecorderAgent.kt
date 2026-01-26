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
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_LINE_BREAKPOINT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_EXCLUDE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_INCLUDE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_JMX_SERVER
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_JMX_HOST
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_JMX_PORT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_RMI_PORT
import org.jetbrains.lincheck.trace.recorder.jmx.TraceRecorderJmxServer
import org.jetbrains.lincheck.trace.recorder.jmx.TraceRecorderJmxController
import org.jetbrains.lincheck.util.TRACE_RECORDER_MODE_PROPERTY
import org.jetbrains.lincheck.util.isInLiveDebuggerMode
import org.jetbrains.lincheck.util.isInTraceDebuggerMode
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
        ARGUMENT_FORMAT,
        ARGUMENT_FOPTION,
        ARGUMENT_INCLUDE,
        ARGUMENT_EXCLUDE,
        ARGUMENT_PACK,
        ARGUMENT_LINE_BREAKPOINT,
        ARGUMENT_JMX_SERVER,
        ARGUMENT_JMX_HOST,
        ARGUMENT_JMX_PORT,
        ARGUMENT_RMI_PORT,
    )

    // entry point for a statically attached java agent
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        // parse and validate arguments and system properties
        parseArguments(agentArgs)
        validateTraceRecorderMode()
        TraceAgentParameters.validateClassAndMethodArgumentsAreProvided()

        // attach java agent
        LincheckInstrumentation.attachJavaAgentStatically(inst)

        // start the JMX server if the specified argument was passed
        setupJmxServerIfRequested()

        // install trace entry points transformer and instrumentation
        installTraceEntryPointTransformer()
        installInstrumentation()
    }

    // entry point for a dynamically attached java agent
    @JvmStatic
    fun agentmain(agentArgs: String?, inst: Instrumentation) {
        // parse and validate arguments and system properties
        parseArguments(agentArgs)

        System.setProperty(TRACE_RECORDER_MODE_PROPERTY, "true")
        validateTraceRecorderMode()

        // attach java agent
        LincheckInstrumentation.attachJavaAgentDynamically(inst)

        // start the JMX server if the specified argument was passed
        setupJmxServerIfRequested()

        // install instrumentation and re-transform already loaded classes
        installInstrumentation()
        // TODO: Re-transform already loaded classes if needed
    }

    @JvmStatic
    private fun parseArguments(agentArgs: String?) {
        TraceAgentParameters.parseArgs(agentArgs, ADDITIONAL_ARGS)
    }

    @JvmStatic
    private fun validateTraceRecorderMode() {
        /*
         * Static agent requires one of: Trace Recorder mode, Trace Debugger mode, or Live Debugger mode.
         * For now, the mode is selected by system property.
         * If you want to run Trace Recorder, you must set `-Dlincheck.traceRecorderMode=true`.
         * If you want to run Live Debugger, you must set `-Dlincheck.liveDebuggerMode=true`.
         *
         * It is an error not to set any mode.
         */

        // Check if one of the required parameters is set.
        check(isInTraceRecorderMode || isInLiveDebuggerMode) {
            """
            When lincheck agent is attached to process,
            mode should be selected by VM parameter: 
            `lincheck.traceRecorderMode` or `lincheck.liveDebuggerMode`.
            One of them is expected to be `true`.
            Rerun with `-Dlincheck.traceRecorderMode=true` or `-Dlincheck.liveDebuggerMode=true`.
            """.trimIndent()
        }
        // Check that only one parameter is set
        val modesEnabled = listOf(isInTraceDebuggerMode, isInTraceRecorderMode, isInLiveDebuggerMode).count { it }
        check(modesEnabled == 1) {
            """
            When lincheck agent is attached to process,
            mode should be selected by one of VM parameters: 
            `lincheck.traceDebuggerMode`, `lincheck.traceRecorderMode`, or `lincheck.liveDebuggerMode`. 
            Only one of them expected to be `true`. 
            Rerun with exactly exactly one mode flag set.
            """.trimIndent()
        }
    }

    @JvmStatic
    private fun setupJmxServerIfRequested() {
        val jmxServerArg = TraceAgentParameters.getArg(ARGUMENT_JMX_SERVER)
        if (jmxServerArg == "on") {
            TraceRecorderJmxServer.start(
                jmxHost = TraceAgentParameters.jmxHost,
                jmxPort = TraceAgentParameters.jmxPort,
                rmiPort = TraceAgentParameters.rmiPort,
            )
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
        LincheckInstrumentation.install(InstrumentationMode.TRACE_RECORDING)
    }
}