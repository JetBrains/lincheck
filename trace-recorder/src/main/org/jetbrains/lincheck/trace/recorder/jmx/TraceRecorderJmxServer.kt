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
import java.lang.management.ManagementFactory
import java.rmi.registry.LocateRegistry
import javax.management.remote.JMXServiceURL
import javax.management.remote.JMXConnectorServerFactory
import org.jetbrains.lincheck.util.Logger

/**
 * Programmatically sets up and starts a JMX server for the trace recorder agent.
 * This allows remote monitoring and management of the trace recording process.
 */
object TraceRecorderJmxServer {
    /**
     * Starts the JMX server on the specified host and port.
     *
     * @param jmxHost The hostname or IP address for JMX connections (default: localhost)
     * @param jmxPort The port for JMX connections (default: 9999)
     * @param rmiPort The port for RMI registry (default: 9998)
     */
    @JvmStatic
    fun start(
        jmxHost: String? = null,
        jmxPort: Int? = null,
        rmiPort: Int? = null,
    ) {
        val host = jmxHost ?: TraceAgentParameters.DEFAULT_JMX_HOST
        val port = jmxPort ?: TraceAgentParameters.DEFAULT_JMX_PORT
        val rmi = rmiPort ?: TraceAgentParameters.DEFAULT_RMI_PORT
        try {
            // Create RMI registry on the specified port
            LocateRegistry.createRegistry(rmi)

            // Get the platform MBean server
            val mbs = ManagementFactory.getPlatformMBeanServer()

            // Create JMX service URL
            val serviceUrl = JMXServiceURL("service:jmx:rmi://$host:$port/jndi/rmi://$host:$rmi/tracing")

            // Create and start the JMX connector server
            val connectorServer = JMXConnectorServerFactory.newJMXConnectorServer(serviceUrl, null, mbs)
            connectorServer.start()

            println("JMX server started successfully on $host:$port (RMI port: $rmi)")
        } catch (t: Throwable) {
            Logger.error { "Failed to start JMX server" }
            Logger.error(t)
        }
    }
}
