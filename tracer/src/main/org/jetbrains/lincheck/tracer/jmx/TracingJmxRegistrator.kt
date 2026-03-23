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

import org.jetbrains.lincheck.trace.jmx.TracingJmxMBean
import org.jetbrains.lincheck.util.Logger
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import javax.management.StandardEmitterMBean

/**
 * Registrator of tracing JMX MBeans.
 */
object TracingJmxRegistrator {

    /**
     * Registers a JMX MBean with the platform MBean server and ensures a shutdown hook is added for cleanup.
     * Before registering the MBean, checks if an MBean with the given name is already registered,
     * and if so, exits without registering.
     *
     * @param mBean the MBean instance implementing the [TracingJmxMBean] interface to be registered.
     * @param mBeanInterface the class object of the [TracingJmxMBean] interface implemented by [mBean].
     */
    fun register(mBean: TracingJmxMBean, mBeanName: String, mBeanInterface: Class<out TracingJmxMBean>) {
        // register JMX MBean
        try {
            val mbs = ManagementFactory.getPlatformMBeanServer()
            val objectName = ObjectName(mBeanName)

            if (mbs.isRegistered(objectName)) {
                Logger.error { "JMX MBean already registered at $mBeanName" }
                return
            }

            @Suppress("UNCHECKED_CAST")
            val mbean = StandardEmitterMBean(mBean, mBeanInterface as Class<TracingJmxMBean>, mBean)
            mbs.registerMBean(mbean, objectName)

            Logger.info { "JMX MBean registered successfully at $mBeanName" }
        } catch (e: Exception) {
            Logger.error(e) { "Failed to register JMX MBean at $mBeanName" }
        }

        // register shutdown hook
        try {
            Runtime.getRuntime().addShutdownHook(Thread { mBean.stopTracing() })
        } catch (e: Exception) {
            Logger.error(e) { "Failed to register shutdown hook for JMX MBean at $mBeanName" }
        }
    }
}