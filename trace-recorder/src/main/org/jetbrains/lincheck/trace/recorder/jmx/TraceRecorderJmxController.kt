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

import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.trace.jmx.TracingJmxController
import org.jetbrains.lincheck.trace.recorder.TraceRecorder
import org.jetbrains.lincheck.util.Logger

/**
 * Provides a JMX controller interface implementation for the [TraceRecorder].
 */
object TraceRecorderJmxController : TracingJmxController {
    override fun install(format: String?, formatOption: String?, traceDumpFilePath: String?) {
        TraceRecorder.install(format, formatOption, traceDumpFilePath, LincheckInstrumentation.context)
    }

    override fun startTracing() {
        try {
            TraceRecorder.startRecording()
        } catch (t: Throwable) {
            Logger.error { "Cannot start trace recording" }
            Logger.error(t)
        }
    }

    override fun stopTracing(traceDumpFilePath: String, packTrace: Boolean) {
        try {
            TraceRecorder.stopRecording()
        } catch (t: Throwable) {
            Logger.error { "Cannot stop trace recording" }
            Logger.error(t)
        }
    }

    override fun dumpTrace(traceDumpFilePath: String, packTrace: Boolean) {
        try {
            TraceRecorder.dumpTrace(traceDumpFilePath, packTrace)
        } catch (t: Throwable) {
            Logger.error { "Cannot dump trace"}
            Logger.error(t)
        }
    }

    override fun uninstall() {
        TraceRecorder.uninstall()
    }
}