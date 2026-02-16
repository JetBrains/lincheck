/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.recorder

import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_BREAKPOINTS_FILE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_EXCLUDE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_INCLUDE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_JMX_MBEAN
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.ARGUMENT_MODE
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters.traceDumpFilePath
import org.jetbrains.lincheck.trace.recorder.jmx.*
import org.jetbrains.lincheck.util.*
import org.jetbrains.lincheck.util.isInTraceRecorderMode
import java.lang.instrument.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent that is set as `premain` entry class for fat trace debugger jar archive.
 * This archive when attached to the jvm process expects also an option
 * `-Dlincheck.traceDebuggerMode=true`, `-Dlincheck.traceRecorderMode=true`, or `-Dlincheck.liveDebuggerMode=true`
 * in order to enable trace debugging plugin, trace recorder functionality, or live debugger functionality accordingly.
 */
internal object TraceRecorderAgent {
    const val ARGUMENT_FORMAT = "format"
    const val ARGUMENT_FOPTION = "formatOption"
    const val ARGUMENT_PACK = "pack"

    // Allowed additional arguments
    private val ADDITIONAL_ARGS = listOf(
        ARGUMENT_MODE,
        ARGUMENT_FORMAT,
        ARGUMENT_FOPTION,
        ARGUMENT_INCLUDE,
        ARGUMENT_EXCLUDE,
        ARGUMENT_PACK,
        ARGUMENT_JMX_MBEAN,
        ARGUMENT_BREAKPOINTS_FILE,
    )
    private val liveDebuggerShutdownHookInstalled = AtomicBoolean(false)

    // entry point for a statically attached java agent
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        // parse and validate arguments and system properties
        parseArguments(agentArgs)

        TraceAgentParameters.validateMode()
        if (isInLiveDebuggerMode) {
            TraceAgentParameters.validateClassAndMethodArgumentsAreNotProvidedInLiveDebuggerMode()
        } else {
            TraceAgentParameters.validateClassAndMethodArgumentsAreProvided()
        }

        // attach java agent
        LincheckInstrumentation.attachJavaAgentStatically(inst)

        // load breakpoints from file if specified (for live debugger mode)
        loadBreakpointsFromFileIfSpecified()

        // register JMX MBean if the specified argument was passed
        registerJmxMBeanIfRequested()

        // install trace entry points transformer and instrumentation
        if (!isInLiveDebuggerMode) {
            installTraceEntryPointTransformer()
        }
        installInstrumentation()
        if (isInLiveDebuggerMode && traceDumpFilePath != null) {
            startLiveDebuggerTracing()
        }
    }

    // entry point for a dynamically attached java agent
    @JvmStatic
    fun agentmain(agentArgs: String?, inst: Instrumentation) {
        // parse and validate arguments and system properties
        parseArguments(agentArgs)

        if (TraceAgentParameters.getArg(ARGUMENT_MODE) == null) {
            // set trace recorder mode system property by default
            System.setProperty(TRACE_RECORDER_MODE_PROPERTY, "true")
        }
        TraceAgentParameters.validateMode()
        if (isInLiveDebuggerMode) {
            TraceAgentParameters.validateClassAndMethodArgumentsAreNotProvidedInLiveDebuggerMode()
        }

        // attach java agent
        LincheckInstrumentation.attachJavaAgentDynamically(inst)

        // load breakpoints from file if specified (for live debugger mode)
        loadBreakpointsFromFileIfSpecified()

        // register JMX MBean if the specified argument was passed
        registerJmxMBeanIfRequested()

        // install instrumentation and re-transform already loaded classes
        installInstrumentation()
        if (isInLiveDebuggerMode && traceDumpFilePath != null) {
            startLiveDebuggerTracing()
        }
        // TODO: Re-transform already loaded classes if needed
    }

    @JvmStatic
    private fun parseArguments(agentArgs: String?) {
        TraceAgentParameters.parseArgs(agentArgs, ADDITIONAL_ARGS)
    }

    @JvmStatic
    private fun loadBreakpointsFromFileIfSpecified() {
        val breakpointsFilePath = TraceAgentParameters.breakpointsFilePath ?: return
        if (!isInLiveDebuggerMode) {
            Logger.warn { "Ignoring breakpointsFile outside live debugger mode: $breakpointsFilePath" }
            return
        }
        try {
            BreakpointsFileParser.loadAndRegisterBreakpoints(
                breakpointsFilePath,
                LincheckClassFileTransformer.liveDebuggerSettings
            )
        } catch (e: Exception) {
            Logger.error(e) { "Failed to load breakpoints from file: $breakpointsFilePath" }
            throw e
        }
    }

    @JvmStatic
    private fun registerJmxMBeanIfRequested() {
        if (TraceAgentParameters.jmxMBeanEnabled) {
            TraceRecorderJmxController.register()
        }
    }

    @JvmStatic
    private fun installTraceEntryPointTransformer() {
        // This transformer adds tracing turn-on and turn-off at the given method entry/exit.
        LincheckInstrumentation.instrumentation.addTransformer(
            /* transformer = */ TraceAgentTransformer(
                LincheckInstrumentation.context,
                ::TraceRecorderMethodTransformer,
                classUnderTracing = TraceAgentParameters.classUnderTraceDebugging,
                methodUnderTracing = TraceAgentParameters.methodUnderTraceDebugging,
            ),
            /* canRetransform = */ true
        )
    }

    @JvmStatic
    private fun installInstrumentation() {
        val mode = when {
            isInTraceRecorderMode -> InstrumentationMode.TRACE_RECORDING
            isInLiveDebuggerMode -> InstrumentationMode.LIVE_DEBUGGING
            else -> error("Unexpected instrumentation mode")
        }
        LincheckInstrumentation.install(mode)
    }

    private fun startLiveDebuggerTracing() {
        val traceDumpFilePath = TraceAgentParameters.traceDumpFilePath
        val packTrace = (TraceAgentParameters.getArg(ARGUMENT_PACK) ?: "true").toBoolean()
        val recordingMode = TraceRecordingMode.parse(
            outputMode = TraceAgentParameters.getArg(ARGUMENT_FORMAT),
            outputOption = TraceAgentParameters.getArg(ARGUMENT_FOPTION),
            outputFilePath = traceDumpFilePath,
        )
        try {
            val session = TraceRecorder.startRecording(
                recordingMode = recordingMode,
                startMode = TraceRecorderSession.StartMode.Dynamic,
            )
            if (session == null) {
                Logger.warn { "Trace recording was not started (recording already in progress)" }
                return
            }
            when (recordingMode) {
                TraceRecordingMode.Null -> Unit
                else -> {
                    if (traceDumpFilePath == null) {
                        Logger.error { "Trace dump path is not set for live debugger mode" }
                    } else {
                        session.installOnFinishHook {
                            dumpTrace(traceDumpFilePath, packTrace)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot start trace recording in live debugger mode" }
            return
        }
        registerLiveDebuggerShutdownHook()
    }

    private fun registerLiveDebuggerShutdownHook() {
        if (!liveDebuggerShutdownHookInstalled.compareAndSet(false, true)) return
        try {
            Runtime.getRuntime().addShutdownHook(Thread(::stopLiveDebuggerTracing))
        } catch (e: Exception) {
            Logger.error(e) { "Failed to register shutdown hook for live debugger tracing" }
        }
    }

    private fun stopLiveDebuggerTracing() {
        if (!TraceRecorder.isRecording()) return
        try {
            TraceRecorder.stopRecording()
        } catch (t: Throwable) {
            Logger.error(t) { "Cannot stop trace recording in live debugger mode" }
        }
    }
}
