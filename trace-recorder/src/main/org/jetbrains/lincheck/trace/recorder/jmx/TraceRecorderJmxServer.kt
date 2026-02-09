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
import org.jetbrains.lincheck.util.Logger
import java.lang.management.ManagementFactory
import javax.management.remote.JMXConnectorServer
import javax.management.remote.JMXConnectorServerFactory
import java.rmi.registry.LocateRegistry
import java.util.concurrent.atomic.AtomicReference

/**
 * Programmatically sets up and starts a JMX server for the trace recorder agent.
 * This allows remote monitoring and management of the trace recording process.
 */
object TraceRecorderJmxServer {

    @Volatile
    @JvmStatic
    private var connectorServer = AtomicReference<JMXConnectorServer>()

    /**
     * Starts the JMX server on the specified host and port.
     *
     * @param jmxHost The hostname or IP address for JMX connections.
     * @param jmxPort The port for JMX connections.
     * @param rmiPort The port for RMI registry.
     */
    @JvmStatic
    @Synchronized
    fun start(jmxHost: String, jmxPort: Int, rmiPort: Int) {
        if (connectorServer.get() != null) {
            Logger.warn { "JMX server is already running" }
            return
        }

        try {
            // Create an RMI registry on the specified port
            LocateRegistry.createRegistry(rmiPort)

            // Get the platform MBean server
            val mbs = ManagementFactory.getPlatformMBeanServer()

            // Create JMX service URL
            val serviceUrl = TraceAgentParameters.getJmxServerUrl(jmxHost, jmxPort, rmiPort)

            // Create and start the JMX connector server
            val connectorServer = JMXConnectorServerFactory
                .newJMXConnectorServer(serviceUrl, null, mbs)
                .apply { start() }

            this.connectorServer.set(connectorServer)
            Logger.info { "JMX server started successfully on $jmxHost:$jmxPort (RMI port: $rmiPort)" }
        } catch (t: Throwable) {
            Logger.error(t) { "Failed to start JMX server on $jmxHost:$jmxPort (RMI port: $rmiPort)" }
        }

        installShutdownHook()
    }

    /**
     * Stops the JMX server if it is running.
     */
    @JvmStatic
    fun stop() {
        try {
            val connectorServer = this.connectorServer.getAndSet(null) ?: return
            connectorServer.stop()
            Logger.info { "JMX server stopped successfully" }
        } catch (t: Throwable) {
            Logger.error(t) { "Failed to stop JMX server" }
        }
    }

    @JvmStatic
    private fun installShutdownHook() {
        try {
            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
            Logger.info { "Shutdown hook successfully installed for JMX server" }
        } catch (e: Throwable) {
            Logger.error(e) { "Failed to register shutdown hook for JMX server" }
        }
    }
}