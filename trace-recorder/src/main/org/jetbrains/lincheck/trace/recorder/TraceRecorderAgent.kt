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
import org.jetbrains.lincheck.jvm.agent.JavaAgentAttachType
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_BREAKPOINTS_FILE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_EXCLUDE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FOPTION
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FORMAT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_INCLUDE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_JMX_MBEAN
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_PACK
import org.jetbrains.lincheck.jvm.agent.TracingEntryPointMethodVisitorProvider
import org.jetbrains.lincheck.trace.jmx.TracingJmxRegistrator
import org.jetbrains.lincheck.tracer.TraceOutputMode
import org.jetbrains.lincheck.tracer.TracerAgent
import org.jetbrains.lincheck.tracer.jmx.AbstractTracingJmxController
import org.jetbrains.lincheck.util.TRACE_RECORDER_MODE_PROPERTY
import java.lang.instrument.Instrumentation

/**
 * Trace recorder JVM agent.
 *
 * Trace recorder captures the execution trace of a program and saves it into a file.
 */
internal object TraceRecorderAgent {

    // Allowed additional arguments
    private val ADDITIONAL_ARGS = listOf(
        ARGUMENT_FORMAT,
        ARGUMENT_FOPTION,
        ARGUMENT_INCLUDE,
        ARGUMENT_EXCLUDE,
        ARGUMENT_PACK,
        ARGUMENT_JMX_MBEAN,
        ARGUMENT_BREAKPOINTS_FILE,
    )

    private val agent = object : TracerAgent() {
        override val modeSystemPropertyName: String = TRACE_RECORDER_MODE_PROPERTY

        override val instrumentationMode: InstrumentationMode = InstrumentationMode.TRACE_RECORDING

        override fun parseArguments(agentArgs: String?) {
            TraceAgentParameters.parseArgs(agentArgs, ADDITIONAL_ARGS)
        }

        override fun validateArguments(attachType: JavaAgentAttachType) {
            TraceAgentParameters.validateMode()
        }

        override fun afterInstrumentationInstalled(attachType: JavaAgentAttachType) {
            // start recording at program start if trace dump file path was provided
            if (TraceAgentParameters.traceDumpFilePath != null) {
                val mode = TraceOutputMode.parse(
                    outputMode = TraceAgentParameters.getArg(ARGUMENT_FORMAT),
                    outputOption = TraceAgentParameters.getArg(ARGUMENT_FOPTION),
                    outputFilePath = TraceAgentParameters.traceDumpFilePath,
                )
                val packTrace = (TraceAgentParameters.getArg(ARGUMENT_PACK) ?: "true").toBoolean()
                ProgramScopedTraceRecorder.startRecording(mode, TraceAgentParameters.traceDumpFilePath, packTrace)
            }
        }

        override val jmxRegistrator: TracingJmxRegistrator get() = jmxController

        private val jmxController = object : AbstractTracingJmxController() {
            override val mbeanName = "org.jetbrains.lincheck:type=TraceRecorder"
            override fun onStreamingDisconnect() {}
        }

        override val tracingEntryPointMethodVisitorProvider: TracingEntryPointMethodVisitorProvider?
            get() = null
    }

    // entry point for a statically attached java agent
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        agent.premain(agentArgs, inst)
    }

    // entry point for a dynamically attached java agent
    @JvmStatic
    fun agentmain(agentArgs: String?, inst: Instrumentation) {
        agent.agentmain(agentArgs, inst)
    }
}
