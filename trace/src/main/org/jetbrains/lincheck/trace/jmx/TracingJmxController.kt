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
import java.net.URI

class TracingJmxController(
    val mBean: TracingJmxMBean,
    val tracingHost: String = TracingController.DEFAULT_TRACING_HOST,
    val tracingPort: Int = TracingController.DEFAULT_TRACING_PORT,
) : TracingController {

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
}