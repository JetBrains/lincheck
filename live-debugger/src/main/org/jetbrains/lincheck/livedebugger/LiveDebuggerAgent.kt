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
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_PACK
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_HEARTBEAT
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.classUnderTraceDebugging
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.methodUnderTraceDebugging
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.traceDumpFilePath
import org.jetbrains.lincheck.jvm.agent.TracingEntryPointMethodVisitorProvider
import org.jetbrains.lincheck.trace.controller.TracingNotification
import org.jetbrains.lincheck.trace.jmx.JmxNotificationData
import org.jetbrains.lincheck.trace.jmx.LiveDebuggerJmxMBean
import org.jetbrains.lincheck.trace.jmx.TracingJmxMBean
import org.jetbrains.lincheck.tracer.TraceOutputMode
import org.jetbrains.lincheck.tracer.jmx.AbstractTracingJmxMBean
import org.jetbrains.lincheck.util.LIVE_DEBUGGER_MODE_PROPERTY
import org.jetbrains.lincheck.util.cleanupUnsafeCaches
import sun.nio.ch.lincheck.BreakpointStorage
import java.lang.instrument.Instrumentation
import javax.management.Notification

/**
 * Live debugging JVM agent.
 *
 * Live debugging allows the insertion of non-suspending breakpoints
 * that capture a snapshot of the program's state at the specified code location.
 */
internal object LiveDebuggerAgent {

    // Allowed additional arguments
    private val ADDITIONAL_ARGS = listOf(
        ARGUMENT_FORMAT,
        ARGUMENT_FOPTION,
        ARGUMENT_PACK,
        ARGUMENT_JMX_MBEAN,
        ARGUMENT_BREAKPOINTS_FILE,
        ARGUMENT_HEARTBEAT,
    )

    private val agent = object : TracerAgent() {
        override val modeSystemPropertyName: String = LIVE_DEBUGGER_MODE_PROPERTY

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

        override val jmxMBeanName: String = "org.jetbrains.lincheck:type=LiveDebugger"
        override val jmxMBeanInterface: Class<out TracingJmxMBean> = LiveDebuggerJmxMBean::class.java

        override val jmxMBean: TracingJmxMBean = object : AbstractTracingJmxMBean(jmxMBeanName), LiveDebuggerJmxMBean {
            init {
                LiveDebugger.installNotificationListener { notification ->
                    sendNotification(notification)
                }
            }

            override fun onStreamingDisconnect() {
                LiveDebugger.removeAllBreakpoints()

                // clean up caches and other global structures
                cleanupUnsafeCaches()
                BreakpointStorage.clear()
            }

            override fun addBreakpoints(breakpoints: List<String>) {
                LiveDebugger.addBreakpoints(breakpoints)
            }

            override fun removeBreakpoints(breakpoints: List<String>) {
                LiveDebugger.removeBreakpoints(breakpoints)
            }

            override fun getJmxNotificationData(notification: TracingNotification): JmxNotificationData? =
                LiveDebuggerJmxMBean.getJmxNotificationData(notification)
                    ?: super.getJmxNotificationData(notification)
        }

        override val tracingEntryPointMethodVisitorProvider: TracingEntryPointMethodVisitorProvider? = null
    }

    // entry point for a statically attached java agent
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        agent.premain(agentArgs, inst)
        installCallbacks()

        val mode = TraceOutputMode.parse(
            outputMode = TraceAgentParameters.getArg(ARGUMENT_FORMAT),
            outputOption = TraceAgentParameters.getArg(ARGUMENT_FOPTION),
            outputFilePath = traceDumpFilePath,
        )
        val packTrace = (TraceAgentParameters.getArg(ARGUMENT_PACK) ?: "true").toBoolean()

        // start immediately at premain only if the trace dump file was specified,
        // otherwise assume tracing will be requested later dynamically via JMX controller
        if (traceDumpFilePath != null) {
            LiveDebugger.startRecording(mode, traceDumpFilePath, packTrace)
        }

        // start phone-home heartbeat if enabled
        if (TraceAgentParameters.heartBeatEnabled) {
            PhoneHomeHeartbeat.start()
        }
    }

    // entry point for a dynamically attached java agent
    @JvmStatic
    fun agentmain(agentArgs: String?, inst: Instrumentation) {
        agent.agentmain(agentArgs, inst)
        installCallbacks()
    }

    @JvmStatic
    private fun installCallbacks() {
        LiveDebugger.ensureHitLimitCallbackInstalled()
        LiveDebugger.ensureConditionUnsafetyCallbackInstalled()
    }
}