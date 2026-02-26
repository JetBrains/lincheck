/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.tracer.jmx

import org.jetbrains.lincheck.trace.jmx.TracingJmxController
import org.jetbrains.lincheck.trace.jmx.TracingJmxRegistrator
import org.jetbrains.lincheck.tracer.Tracer
import org.jetbrains.lincheck.tracer.TraceOutputMode
import org.jetbrains.lincheck.tracer.TracingSession
import org.jetbrains.lincheck.util.Logger
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import javax.management.StandardMBean

/**
 * A base abstract class for implementing tracing JMX controller interface for the [Tracer].
 */
abstract class AbstractTracingJmxController : TracingJmxRegistrator, TracingJmxController {

    protected open val mbeanInterface: Class<out TracingJmxController>
        get() = TracingJmxController::class.java
    
    abstract fun onStreamingDisconnect()

    override fun register() {
        // register JMX MBean
        try {
            val mbs = ManagementFactory.getPlatformMBeanServer()
            val objectName = ObjectName(mbeanName)

            if (mbs.isRegistered(objectName)) {
                Logger.error { "JMX MBean already registered at $mbeanName" }
                return
            }

            @Suppress("UNCHECKED_CAST")
            val mbean = StandardMBean(this, mbeanInterface as Class<TracingJmxController>)
            mbs.registerMBean(mbean, objectName)

            Logger.info { "JMX MBean registered successfully at $mbeanName" }
        } catch (e: Exception) {
            Logger.error(e) { "Failed to register JMX MBean at $mbeanName" }
        }

        // register shutdown hook
        try {
            Runtime.getRuntime().addShutdownHook(Thread(this::shutdownHook))
        } catch (e: Exception) {
            Logger.error(e) { "Failed to register shutdown hook for JMX MBean at $mbeanName" }
        }
    }

    override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) {
        try {
            val session = Tracer.startTracing(
                // TODO: support configuring file mode (text or binary, stream or dump)
                outputMode = TraceOutputMode.BinaryFileStream(traceDumpFilePath),
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

    override fun startNetworkTracing() {
        try {
            Tracer.startTracing(
                outputMode = TraceOutputMode.BinaryNetworkStream(onDisconnect = ::onStreamingDisconnect),
                startMode = TracingSession.StartMode.Dynamic,
            )
            Logger.info { "WebSocket trace streaming session has been started" }
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot start WebSocket trace streaming" }
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
}