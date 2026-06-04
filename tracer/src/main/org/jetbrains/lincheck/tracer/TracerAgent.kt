/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.tracer

import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.JavaAgentAttachType
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.jvm.agent.TracingEntryPointMethodVisitorProvider
import org.jetbrains.lincheck.jvm.agent.TracingEntryPointTransformer
import org.jetbrains.lincheck.trace.network.TracingServer
import java.lang.instrument.Instrumentation

/**
 * Describes how a tracing agent is configured to begin capturing a trace.
 *
 * - [ApplicationStart] — whole-application tracing starting at JVM startup (static attach, no class/method specified).
 * - [ExternalRequest] — tracing triggered on demand by an external request (server/WebSocket command).
 * - [MethodCall] — tracing starts when a specific method is provided.
 */
sealed class TracingEntryPoint {
    data object ApplicationStart : TracingEntryPoint()
    data object ExternalRequest : TracingEntryPoint()
    data class MethodCall(val className: String, val methodName: String) : TracingEntryPoint()
}

/**
 * Abstract class for managing the lifecycle of a tracing JMV agent.
 *
 * This class provides entry points for both statically and dynamically attached agents
 * and handles initialization procedures.
 */
abstract class TracerAgent {

    var server: TracingServer? = null
        protected set

    // entry point for a statically attached java agent
    fun premain(agentArgs: String?, inst: Instrumentation) {
        setupMode()

        // Attach first then append `bootstrap.jar` to the bootstrap classloader's search path before
        // any downstream code (notably `parseArguments` -> live-debugger breakpoint loading)
        // can reference bootstrap-only classes such as `sun.nio.ch.lincheck.BreakpointStorage`.
        LincheckInstrumentation.attachJavaAgentStatically(inst)
        LincheckInstrumentation.appendBootstrapJarToClassLoaderSearch()

        // parse and validate arguments and system properties
        parseArguments(agentArgs)
        validateArguments(JavaAgentAttachType.STATIC)

        // setup tracing entry point based on agent type and its args
        setupTracingEntryPoint(JavaAgentAttachType.STATIC)

        // install trace entry points transformer and instrumentation if requested
        installTraceEntryPointTransformerIfRequested()

        // install instrumentation
        installInstrumentation()
        postInstallInstrumentationSetup()

        // create tracing server if requested
        startTracingServerIfRequested()

        // start whole-application tracing immediately if the entry point is ApplicationStart
        setupTracingFromApplicationStartIfRequested()
    }

    // entry point for a dynamically attached java agent
    fun agentmain(agentArgs: String?, inst: Instrumentation) {
        setupMode()

        // See `premain` above for the attach-then-append rationale.
        LincheckInstrumentation.attachJavaAgentDynamically(inst)
        LincheckInstrumentation.appendBootstrapJarToClassLoaderSearch()

        // parse and validate arguments and system properties
        parseArguments(agentArgs)
        validateArguments(JavaAgentAttachType.DYNAMIC)

        // setup tracing entry point based on agent type and its args
        setupTracingEntryPoint(JavaAgentAttachType.DYNAMIC)

        // install instrumentation and re-transform already loaded classes
        installInstrumentation()
        postInstallInstrumentationSetup()

        // create tracing server if requested
        startTracingServerIfRequested()
    }

    protected abstract val modeSystemPropertyName: String

    private fun setupMode() {
        System.setProperty(modeSystemPropertyName, "true")
    }

    protected abstract fun parseArguments(agentArgs: String?)
    protected abstract fun validateArguments(attachType: JavaAgentAttachType)

    // configured entry point for this agent
    val tracingEntryPoint: TracingEntryPoint get() = _tracingEntryPoint
    private lateinit var _tracingEntryPoint: TracingEntryPoint

    private fun setupTracingEntryPoint(agentType: JavaAgentAttachType) {
        _tracingEntryPoint = when (agentType) {
            JavaAgentAttachType.DYNAMIC ->
                TracingEntryPoint.ExternalRequest
            JavaAgentAttachType.STATIC if TraceAgentParameters.isApplicationStartTracingRequested() ->
                TracingEntryPoint.ApplicationStart
            JavaAgentAttachType.STATIC ->
                TracingEntryPoint.MethodCall(TraceAgentParameters.classUnderTracing, TraceAgentParameters.methodUnderTracing)
        }
    }

    protected abstract val instrumentationMode: InstrumentationMode

    private fun installInstrumentation() {
        LincheckInstrumentation.install(instrumentationMode)
    }

    protected open fun postInstallInstrumentationSetup() {}

    protected abstract val tracingEntryPointMethodVisitorProvider: TracingEntryPointMethodVisitorProvider?

    private fun installTraceEntryPointTransformerIfRequested() {
        val tracingEntryPoint = (this.tracingEntryPoint as? TracingEntryPoint.MethodCall) ?: return
        val provider = checkNotNull(tracingEntryPointMethodVisitorProvider) {
            "${this::class.simpleName} must provide tracingEntryPointMethodVisitorProvider for MethodCall entry point"
        }
        // This transformer adds tracing turn-on and turn-off at the given method entry/exit.
        LincheckInstrumentation.instrumentation.addTransformer(
            /* transformer = */ TracingEntryPointTransformer(
                LincheckInstrumentation.context,
                provider,
                classUnderTracing = tracingEntryPoint.className,
                methodUnderTracing = tracingEntryPoint.methodName,
            ),
            /* canRetransform = */ true
        )
    }

    /**
     * Starts whole-application tracing immediately and installs a shutdown hook to stop and dump the trace.
     * Called at the end of [premain] when [tracingEntryPoint] is [TracingEntryPoint.ApplicationStart].
     *
     * Override with an empty body in agents that manage their own session lifecycle (e.g. live-debugger).
     */
    protected open fun setupTracingFromApplicationStartIfRequested() {
        if (tracingEntryPoint != TracingEntryPoint.ApplicationStart) return

        val format = TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_FORMAT)
        val formatOption = TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_FOPTION)
        val dumpPath = TraceAgentParameters.traceDumpFilePath
        val pack = (TraceAgentParameters.getArg(TraceAgentParameters.ARGUMENT_PACK) ?: "true").toBoolean()
        val outputMode = TraceOutputMode.parse(
            outputMode     = format,
            outputOption   = formatOption,
            outputFilePath = TraceAgentParameters.traceDumpFilePath,
        )
        Tracer.launchTracingSession(TracingSession.StartMode.ApplicationStart, outputMode, dumpPath, pack)
    }
    
    protected open fun startTracingServerIfRequested() {
        if (TraceAgentParameters.serverEnabled) {
            server = createTracingServer()
            server?.let { Runtime.getRuntime().addShutdownHook(Thread { it.close() }) }
        }
    }

    protected abstract fun createTracingServer(): TracingServer?
}