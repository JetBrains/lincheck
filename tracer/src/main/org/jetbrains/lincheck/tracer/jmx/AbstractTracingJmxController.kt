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

import org.jetbrains.lincheck.trace.jmx.*
import org.jetbrains.lincheck.tracer.*
import org.jetbrains.lincheck.util.*
import java.lang.management.*
import java.util.concurrent.atomic.*
import javax.management.*

/**
 * A base abstract class for implementing tracing JMX controller interface for the [Tracer].
 *
 * Extends [NotificationBroadcasterSupport] so that the registered [StandardEmitterMBean] can emit
 * JMX notifications to remote clients (e.g., to the IDE plugin when a breakpoint reaches its
 * hit limit).
 */
abstract class AbstractTracingJmxController :
    NotificationBroadcasterSupport(), TracingJmxRegistrator, TracingJmxMBean {

    override val mbeanInterface: Class<out TracingJmxMBean>
        get() = TracingJmxMBean::class.java

    private val notificationSequence = AtomicLong(0)
    
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
            val mbean = StandardEmitterMBean(this, mbeanInterface as Class<TracingJmxMBean>, this)
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

    /**
     * Sends a JMX notification to inform that the given breakpoint has reached its hit limit.
     *
     * @param breakpointId a `"className:fileName:lineNumber"` string identifying the breakpoint.
     */
    fun notifyBreakpointHitLimitReached(breakpointId: String) {
        val notification = Notification(
            LiveDebuggerJmxMBean.BREAKPOINT_HIT_LIMIT_NOTIFICATION_TYPE,
            ObjectName(mbeanName),
            notificationSequence.incrementAndGet(),
            "Breakpoint '$breakpointId' has reached its hit limit",
        )
        notification.userData = breakpointId
        sendNotification(notification)
        Logger.info { "Sent hit-limit notification for breakpoint: $breakpointId" }
    }
}