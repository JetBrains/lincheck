/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.jmx

import org.jetbrains.lincheck.trace.NetworkTraceReader
import org.jetbrains.lincheck.trace.controller.TracingController
import org.jetbrains.lincheck.trace.controller.TracingNotification
import org.jetbrains.lincheck.trace.controller.TracingNotificationListener
import javax.management.Notification
import javax.management.NotificationListener
import javax.management.ObjectName
import javax.management.remote.JMXConnector
import java.net.URI

abstract class AbstractTracingJmxController(
    val jmxConnector: JMXConnector,
    open val mBean: TracingJmxMBean,
    val tracingHost: String = TracingController.DEFAULT_TRACING_HOST,
    val tracingPort: Int = TracingController.DEFAULT_TRACING_PORT,
) : TracingController {

    private val notificationListeners = mutableListOf<TracingNotificationListener>()

    init {
        subscribeToNotifications()
    }

    override fun startFileTracing(traceDumpFilePath: String, packTrace: Boolean) {
        mBean.startFileTracing(traceDumpFilePath, packTrace)
    }

    override fun startNetworkTracing(): NetworkTraceReader {
        mBean.startNetworkTracing()
        val uri = URI("ws://$tracingHost:$tracingPort")
        return NetworkTraceReader(uri)
    }

    override fun stopTracing() {
        mBean.stopTracing()
    }

    private fun subscribeToNotifications() {
        val mbeanConnection = jmxConnector.mBeanServerConnection
        val objectName = ObjectName(mBean.name)
        if (!mbeanConnection.isRegistered(objectName)) {
            throw IllegalStateException("MBean ${mBean.name} is not registered")
        }
        val listener = NotificationListener { notification, _ ->
            val notification = mBean.parseNotification(notification) ?: return@NotificationListener
            notificationListeners.forEach { listener ->
                listener(notification)
            }
        }
        mbeanConnection.addNotificationListener(objectName, listener, null, null)
    }

    override fun addNotificationListener(listener: TracingNotificationListener) {
        notificationListeners.add(listener)
    }

    protected open fun Notification.parse(): TracingNotification? = null
}

class TracingJmxController(
    jmxConnector: JMXConnector,
    mBean: TracingJmxMBean,
    tracingHost: String = TracingController.DEFAULT_TRACING_HOST,
    tracingPort: Int = TracingController.DEFAULT_TRACING_PORT,
) : AbstractTracingJmxController(jmxConnector, mBean, tracingHost, tracingPort)