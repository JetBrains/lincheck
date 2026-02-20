/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.livedebugger

import org.jetbrains.lincheck.tracer.TracerAgent
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.JavaAgentAttachType
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_BREAKPOINTS_FILE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FOPTION
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FORMAT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_JMX_MBEAN
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_MODE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_PACK
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.classUnderTraceDebugging
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.methodUnderTraceDebugging
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.traceDumpFilePath
import org.jetbrains.lincheck.jvm.agent.TracingEntryPointMethodVisitorProvider
import org.jetbrains.lincheck.trace.jmx.LiveDebuggerJmxController
import org.jetbrains.lincheck.trace.jmx.TracingJmxRegistrator
import org.jetbrains.lincheck.tracer.TracingMode
import org.jetbrains.lincheck.tracer.jmx.AbstractTracingJmxController
import org.jetbrains.lincheck.util.LIVE_DEBUGGER_MODE_PROPERTY
import java.lang.instrument.Instrumentation

/**
 * Live debugging JVM agent.
 *
 * Live debugging allows the insertion of non-suspending breakpoints
 * that capture a snapshot of the program's state at the specified code location.
 */
internal object LiveDebuggerAgent {

    // Allowed additional arguments
    private val ADDITIONAL_ARGS = listOf(
        ARGUMENT_MODE,
        ARGUMENT_FORMAT,
        ARGUMENT_FOPTION,
        ARGUMENT_PACK,
        ARGUMENT_JMX_MBEAN,
        ARGUMENT_BREAKPOINTS_FILE,
    )

    private val agent = object : TracerAgent() {
        override val instrumentationMode: InstrumentationMode = InstrumentationMode.LIVE_DEBUGGING

        override fun parseArguments(agentArgs: String?) {
            TraceAgentParameters.parseArgs(agentArgs, ADDITIONAL_ARGS)
            LiveDebugger.loadBreakpointsFromFile(TraceAgentParameters.breakpointsFilePath)
        }

        override fun validateArguments(attachType: JavaAgentAttachType) {
            TraceAgentParameters.validateMode()

            if (classUnderTraceDebugging.isNotBlank() || methodUnderTraceDebugging.isNotBlank()) {
                error("Class and method arguments are not allowed in live debugger mode")
            }
        }

        override val jmxRegistrator: TracingJmxRegistrator get() = jmxController

        private val jmxController = object : AbstractTracingJmxController(), LiveDebuggerJmxController {
            override val mbeanName: String = "org.jetbrains.lincheck:type=LiveDebugger"

            override fun addBreakpoints(breakpoints: List<String>) {
                LiveDebugger.addBreakpoints(breakpoints)
            }

            override fun removeBreakpoints(breakpoints: List<String>) {
                LiveDebugger.removeBreakpoints(breakpoints)
            }
        }

        override val tracingEntryPointMethodVisitorProvider: TracingEntryPointMethodVisitorProvider? = null
    }

    // entry point for a statically attached java agent
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        agent.premain(agentArgs, inst)

        val mode = TracingMode.parse(
            outputMode = TraceAgentParameters.getArg(ARGUMENT_FORMAT),
            outputOption = TraceAgentParameters.getArg(ARGUMENT_FOPTION),
            outputFilePath = traceDumpFilePath,
        )
        val packTrace = (TraceAgentParameters.getArg(ARGUMENT_PACK) ?: "true").toBoolean()

        LiveDebugger.startRecording(mode, traceDumpFilePath, packTrace)
    }

    // entry point for a dynamically attached java agent
    @JvmStatic
    fun agentmain(agentArgs: String?, inst: Instrumentation) {
        if (TraceAgentParameters.getArg(ARGUMENT_MODE) == null) {
            // set live debugger mode system property by default
            System.setProperty(LIVE_DEBUGGER_MODE_PROPERTY, "true")
        }

        agent.agentmain(agentArgs, inst)
    }
}