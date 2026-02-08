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
import org.jetbrains.lincheck.trace.TcpTraceServer
import org.jetbrains.lincheck.trace.jmx.TracingJmxController
import org.jetbrains.lincheck.trace.recorder.TraceRecorder
import org.jetbrains.lincheck.trace.recorder.TraceRecorderAgent
import org.jetbrains.lincheck.trace.recorder.TraceRecorderSession
import org.jetbrains.lincheck.trace.recorder.TraceRecordingMode
import org.jetbrains.lincheck.util.Logger
import java.lang.management.ManagementFactory
import java.util.IdentityHashMap
import javax.management.ObjectName
import javax.management.StandardMBean

/**
 * Provides a JMX controller interface implementation for the [TraceRecorder].
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
            val session = TraceRecorder.startRecording(
                recordingMode = TraceRecordingMode.parse(
                    outputMode = TraceAgentParameters.getArg(TraceRecorderAgent.ARGUMENT_FORMAT),
                    outputOption = TraceAgentParameters.getArg(TraceRecorderAgent.ARGUMENT_FOPTION),
                    outputFilePath = traceDumpFilePath,
                ),
                startMode = TraceRecorderSession.StartMode.Dynamic,
            )
            session?.installOnFinishHook {
                dumpTrace(traceDumpFilePath, packTrace)
            }
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot start trace recording" }
        }
    }

    override fun startTcpTracing() {
        try {
            val session = TraceRecorder.startRecording(
                recordingMode = TraceRecordingMode.BinaryTcpStream,
                startMode = TraceRecorderSession.StartMode.Dynamic,
            )

            if (session == null) {
                Logger.warn { "TCP trace streaming session was not started (recording already in progress)" }
                return
            }
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot start TCP trace streaming" }
        }
    }

    override fun stopTracing() {
        try {
            TraceRecorder.stopRecording()
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot stop trace recording" }
        }
    }

    private fun shutdownHook() {
        if (TraceRecorder.isRecording()) {
            stopTracing()
        }
    }
}

private const val MBEAN_OBJECT_NAME = "org.jetbrains.lincheck:type=TracingController"