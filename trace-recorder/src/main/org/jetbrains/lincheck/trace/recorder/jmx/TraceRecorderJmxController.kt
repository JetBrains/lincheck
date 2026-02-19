/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.recorder.jmx

import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FOPTION
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_FORMAT
import org.jetbrains.lincheck.trace.jmx.TracingJmxController
import org.jetbrains.lincheck.trace.recorder.Tracer
import org.jetbrains.lincheck.tracer.TracingSession
import org.jetbrains.lincheck.tracer.TracingMode
import org.jetbrains.lincheck.util.Logger
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import javax.management.StandardMBean

/**
 * Provides a JMX controller interface implementation for the [Tracer].
 */
object TraceRecorderJmxController : TracingJmxController {

    fun register() {
        // register JMX MBean
        try {
            val mbs = ManagementFactory.getPlatformMBeanServer()
            val objectName = ObjectName(MBEAN_OBJECT_NAME)

            if (mbs.isRegistered(objectName)) {
                Logger.error { "JMX MBean already registered at $MBEAN_OBJECT_NAME" }
                return
            }

            val mbean = StandardMBean(this, TracingJmxController::class.java)
            mbs.registerMBean(mbean, objectName)

            Logger.info { "JMX MBean registered successfully at $MBEAN_OBJECT_NAME" }
        } catch (e: Exception) {
            Logger.error(e) { "Failed to register JMX MBean at $MBEAN_OBJECT_NAME" }
        }

        // register shutdown hook
        try {
            Runtime.getRuntime().addShutdownHook(Thread(this::shutdownHook))
        } catch (e: Exception) {
            Logger.error(e) { "Failed to register shutdown hook for JMX MBean at $MBEAN_OBJECT_NAME" }
        }
    }

    override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) {
        try {
            val session = Tracer.startTracing(
                recordingMode = TracingMode.parse(
                    outputMode = TraceAgentParameters.getArg(ARGUMENT_FORMAT),
                    outputOption = TraceAgentParameters.getArg(ARGUMENT_FOPTION),
                    outputFilePath = traceDumpFilePath,
                ),
                startMode = TracingSession.StartMode.Dynamic,
            )
            Logger.info { "File-based trace session has been started" }

            session.installOnFinishHook {
                dumpTrace(traceDumpFilePath, packTrace)
            }
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot start trace recording" }
        }
    }

    override fun startTcpTracing() {
        try {
            Tracer.startTracing(
                recordingMode = TracingMode.BinaryTcpStream,
                startMode = TracingSession.StartMode.Dynamic,
            )
            Logger.info { "TCP trace streaming session has been started" }
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot start TCP trace streaming" }
        }
    }

    override fun stopTracing() {
        try {
            Tracer.stopTracing()
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot stop trace recording" }
        }
    }

    private fun shutdownHook() {
        stopTracing()
    }

    override fun addBreakpoints(breakpoints: List<String>) {
        Tracer.addBreakpoints(breakpoints)
    }

    override fun removeBreakpoints(breakpoints: List<String>) {
        Tracer.removeBreakpoints(breakpoints)
    }
}

private const val MBEAN_OBJECT_NAME = "org.jetbrains.lincheck:type=TracingController"