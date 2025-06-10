/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.traceagent

import org.jetbrains.kotlinx.lincheck.strategy.tracerecorder.TraceRecorder
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.objectweb.asm.commons.Method

internal object TraceRecorderInjections {

    @JvmStatic
    fun prepareTraceRecorder() {
        // Must be first or classes will not be found
        LincheckJavaAgent.install(InstrumentationMode.TRACE_RECORDING)
        // Retransform classes for event tracking
        LincheckJavaAgent.ensureClassHierarchyIsTransformed(TraceAgentParameters.classUnderTraceDebugging)
    }

    @JvmStatic
    fun startTraceRecorder() {
        val (_, testMethod) = TraceAgentParameters.getClassAndMethod()
        val methodDescriptor = Method.getMethod(testMethod).descriptor
        // Init it, but not enable (yet)
        TraceRecorder.installAndStartTrace(TraceAgentParameters.classUnderTraceDebugging, TraceAgentParameters.methodUnderTraceDebugging, methodDescriptor, TraceAgentParameters.traceDumpFilePath)
    }

    @JvmStatic
    fun stopTraceRecorderAndDumpTrace() {
        TraceRecorder.finishTraceAndDumpResults()
    }
}
