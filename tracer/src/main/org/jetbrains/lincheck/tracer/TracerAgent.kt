/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.tracer

import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.JavaAgentAttachType
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.jvm.agent.TracingEntryPointMethodVisitorProvider
import org.jetbrains.lincheck.jvm.agent.TracingEntryPointTransformer
import org.jetbrains.lincheck.trace.jmx.TracingJmxMBean
import org.jetbrains.lincheck.tracer.jmx.TracingJmxRegistrator
import java.lang.instrument.Instrumentation

/**
 * Abstract class for managing the lifecycle of a tracing JMV agent.
 *
 * This class provides entry points for both statically and dynamically attached agents,
 * handles initialization procedures, and manages the optional
 * registration of JMX MBeans for monitoring purposes.
 */
abstract class TracerAgent {

    // entry point for a statically attached java agent
    fun premain(agentArgs: String?, inst: Instrumentation) {
        setupMode()

        // parse and validate arguments and system properties
        parseArguments(agentArgs)
        validateArguments(JavaAgentAttachType.STATIC)

        // attach java agent
        LincheckInstrumentation.attachJavaAgentStatically(inst)

        // register JMX MBean if the specified argument was passed
        registerJmxMBeanIfRequested()

        // install trace entry points transformer and instrumentation if requested
        installTraceEntryPointTransformerIfRequested()

        // install instrumentation
        installInstrumentation()
    }

    // entry point for a dynamically attached java agent
    fun agentmain(agentArgs: String?, inst: Instrumentation) {
        setupMode()

        // parse and validate arguments and system properties
        parseArguments(agentArgs)
        validateArguments(JavaAgentAttachType.DYNAMIC)

        // attach java agent
        LincheckInstrumentation.attachJavaAgentDynamically(inst)

        // register JMX MBean if the specified argument was passed
        registerJmxMBeanIfRequested()

        // install instrumentation and re-transform already loaded classes
        installInstrumentation()
    }

    protected abstract val modeSystemPropertyName: String

    private fun setupMode() {
        System.setProperty(modeSystemPropertyName, "true")
    }

    protected abstract fun parseArguments(agentArgs: String?)
    protected abstract fun validateArguments(attachType: JavaAgentAttachType)

    protected abstract val jmxMBean: TracingJmxMBean?

    private fun registerJmxMBeanIfRequested() {
        if (TraceAgentParameters.jmxMBeanEnabled) {
             jmxMBean?.let { TracingJmxRegistrator.register(it) }
        }
    }

    protected abstract val tracingEntryPointMethodVisitorProvider: TracingEntryPointMethodVisitorProvider?

    private fun installTraceEntryPointTransformerIfRequested() {
        val provider = tracingEntryPointMethodVisitorProvider ?: return
        // This transformer adds tracing turn-on and turn-off at the given method entry/exit.
        LincheckInstrumentation.instrumentation.addTransformer(
            /* transformer = */ TracingEntryPointTransformer(
                LincheckInstrumentation.context,
                provider,
                classUnderTracing = TraceAgentParameters.classUnderTraceDebugging,
                methodUnderTracing = TraceAgentParameters.methodUnderTraceDebugging,
            ),
            /* canRetransform = */ true
        )
    }

    protected abstract val instrumentationMode: InstrumentationMode

    private fun installInstrumentation() {
        LincheckInstrumentation.install(instrumentationMode)
    }
}