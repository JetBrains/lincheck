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

import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.trace.jmx.TracingJmxController
import org.jetbrains.lincheck.trace.recorder.TraceRecorder
import org.jetbrains.lincheck.util.Logger
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import javax.management.StandardMBean

/**
 * Provides a JMX controller interface implementation for the [TraceRecorder].
 */
object TraceRecorderJmxController : TracingJmxController {

    fun register() {
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
            Logger.error { "Failed to register JMX MBean" }
            Logger.error(e)
        }
    }
    
    override fun install(format: String?, formatOption: String?, traceDumpFilePath: String?) {
        TraceRecorder.install(format, formatOption, traceDumpFilePath, LincheckInstrumentation.context)
    }

    override fun startTracing() {
        try {
            TraceRecorder.startRecording()
        } catch (t: Throwable) {
            Logger.error { "Cannot start trace recording" }
            Logger.error(t)
        }
    }

    override fun stopTracing(traceDumpFilePath: String, packTrace: Boolean) {
        try {
            TraceRecorder.stopRecording()
        } catch (t: Throwable) {
            Logger.error { "Cannot stop trace recording" }
            Logger.error(t)
        }
    }

    override fun dumpTrace(traceDumpFilePath: String, packTrace: Boolean) {
        try {
            TraceRecorder.dumpTrace(traceDumpFilePath, packTrace)
        } catch (t: Throwable) {
            Logger.error { "Cannot dump trace"}
            Logger.error(t)
        }
    }

    override fun uninstall() {
        TraceRecorder.uninstall()
    }
}

private const val MBEAN_OBJECT_NAME = "org.jetbrains.lincheck:type=TracingController"