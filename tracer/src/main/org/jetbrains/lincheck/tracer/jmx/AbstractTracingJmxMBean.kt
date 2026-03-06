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

import org.jetbrains.lincheck.trace.controller.*
import org.jetbrains.lincheck.trace.jmx.*
import org.jetbrains.lincheck.tracer.*
import org.jetbrains.lincheck.util.*
import java.util.concurrent.atomic.*
import javax.management.*

/**
 * A base abstract class for implementing tracing JMX MBean interface.
 *
 * Extends [NotificationBroadcasterSupport] so that the registered [StandardEmitterMBean]
 * can emit JMX notifications to remote clients.
 */
abstract class AbstractTracingJmxMBean(val mBeanName: String) : NotificationBroadcasterSupport(), TracingJmxMBean {

    private val notificationSequence = AtomicLong(0)

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

    open fun onStreamingDisconnect() {}

    override fun stopTracing() {
        try {
            Tracer.stopTracing()
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot stop trace recording" }
        }
    }

    fun sendNotification(notification: TracingNotification) {
        try {
            val jmxNotificationData = getJmxNotificationData(notification) ?: return
            val jmxNotification = Notification(
                /* type = */ jmxNotificationData.type,
                /* source = */ ObjectName(mBeanName),
                /* sequenceNumber = */ notificationSequence.incrementAndGet(),
                /* timeStamp = */ jmxNotificationData.timestamp,
                /* message = */ jmxNotificationData.message,
            )
            if (jmxNotificationData.userData != null) {
                jmxNotification.userData = jmxNotificationData.userData
            }
            sendNotification(jmxNotification)
            Logger.info { "Sent notification via JMX: $notification" }
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot send notification via JMX: $notification" }
        }
    }

    protected open fun getJmxNotificationData(notification: TracingNotification): JmxNotificationData? = null
}