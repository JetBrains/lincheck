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
import org.jetbrains.lincheck.util.Logger
import java.lang.management.ManagementFactory
import java.net.URI
import javax.management.NotificationBroadcasterSupport
import javax.management.ObjectName
import javax.management.StandardEmitterMBean

class TracingJmxController(
    override val mbeanName: String,
    val mBean: TracingJmxMBean,
    val tracingHost: String = TracingController.DEFAULT_TRACING_HOST,
    val tracingPort: Int = TracingController.DEFAULT_TRACING_PORT,
) : TracingController, TracingJmxRegistrator {

    override val mbeanInterface: Class<out TracingJmxMBean>
        get() = mBean::class.java

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
            val mbean = StandardEmitterMBean(mBean, mbeanInterface as Class<TracingJmxMBean>, mBean)
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

    private fun shutdownHook() {
        stopTracing()
    }
}