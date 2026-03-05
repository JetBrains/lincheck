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

import org.jetbrains.lincheck.trace.controller.LiveDebuggerController
import org.jetbrains.lincheck.trace.controller.TracingController
import javax.management.remote.JMXConnector

class LiveDebuggerJmxController(
    jmxConnector: JMXConnector,
    override val mBean: LiveDebuggerJmxMBean,
    tracingHost: String = TracingController.DEFAULT_TRACING_HOST,
    tracingPort: Int = TracingController.DEFAULT_TRACING_PORT,
) : AbstractTracingJmxController(jmxConnector, mBean, tracingHost, tracingPort), LiveDebuggerController {

    override fun addBreakpoints(breakpoints: List<String>) {
        mBean.addBreakpoints(breakpoints)
    }

    override fun removeBreakpoints(breakpoints: List<String>) {
        mBean.removeBreakpoints(breakpoints)
    }
}