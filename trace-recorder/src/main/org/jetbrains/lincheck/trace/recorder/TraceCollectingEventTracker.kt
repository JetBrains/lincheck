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

import org.jetbrains.lincheck.analysis.ShadowStackFrame
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.trace.TRACE_CONTEXT
import org.jetbrains.lincheck.jvm.agent.LincheckJavaAgent
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.trace.TRMethodCallTracePoint.Companion.INCOMPLETE_METHOD_FLAG
import org.jetbrains.lincheck.util.*
import sun.nio.ch.lincheck.*
import java.lang.invoke.CallSite
import java.util.concurrent.ConcurrentHashMap

private class ThreadData(
    val threadId: Int
) {
    data class StackFrame(
        val call: TRMethodCallTracePoint,
        val shadow: ShadowStackFrame,
        val loopStack: MutableList<LoopStackElement>, // stack of currently running loops
        val isInline: Boolean,
    )

    data class LoopStackElement(
        val header: TRLoopTracePoint,
        val iterations: MutableList<TRLoopIterationTracePoint>,
    )

    private val stack: MutableList<StackFrame> = arrayListOf()
    private val analysisSectionStack: MutableList<AnalysisSectionType> = arrayListOf()

    fun currentMethodCallTracePoint(): TRMethodCallTracePoint? =
        stack.lastOrNull()?.call

    fun firstMethodCallTracePoint(): TRMethodCallTracePoint? =
        stack.firstOrNull()?.call

    fun isCurrentMethodCallInline(): Boolean =
        stack.last().isInline

    fun currentLoopTracePoint(): TRLoopTracePoint? =
        stack.lastOrNull()?.loopStack?.lastOrNull()?.header

    fun currentLoopIterationTracePoint(): TRLoopIterationTracePoint? =
        stack.lastOrNull()?.loopStack?.lastOrNull()?.iterations?.lastOrNull()

    fun currentTopTracePoint(): TRContainerTracePoint? {
        val stackElement = stack.lastOrNull() ?: return null
        if (stackElement.loopStack.isEmpty()) {
            return stackElement.call
        }
        val loopElement = stackElement.loopStack.last()
        if (loopElement.iterations.isEmpty()) {
            return loopElement.header
        }
        return loopElement.iterations.last()
    }

    fun pushStackFrame(tracePoint: TRMethodCallTracePoint, instance: Any?, isInline: Boolean) {
        val stackFrame = ShadowStackFrame(instance)
        stack.add(StackFrame(
            call = tracePoint,
            shadow = stackFrame,
            loopStack = mutableListOf(),
            isInline = isInline,
        ))
    }

    fun popStackFrame(): TRMethodCallTracePoint {
        val frame = stack.removeLast()
        return frame.call
    }

    fun getStack(): List<StackFrame> = stack

    fun enterLoop(loopTracePoint: TRLoopTracePoint) {
        val frame = stack.last()
        val loop = LoopStackElement(loopTracePoint, mutableListOf())
        frame.loopStack.add(loop)
    }

    fun addLoopIteration(loopIterationTracePoint: TRLoopIterationTracePoint) {
        val frame = stack.last()
        val loop = frame.loopStack.last()
        loop.iterations.add(loopIterationTracePoint)
        loop.header.incrementIterations()
    }

    fun exitLoop() {
        val frame = stack.last()
        frame.loopStack.removeLast()
    }

    fun enterAnalysisSection(section: AnalysisSectionType) {
        if (section == AnalysisSectionType.IGNORED) {
            enterIgnoredSection()
            return
        }
        val currentSection = analysisSectionStack.lastOrNull()
        if (currentSection != null && currentSection.isCallStackPropagating() && section < currentSection) {
            analysisSectionStack.add(currentSection)
        } else {
            analysisSectionStack.add(section)
        }
        if (section == AnalysisSectionType.ATOMIC) {
            enterIgnoredSection()
        }
    }

    fun leaveAnalysisSection(section: AnalysisSectionType) {
        if (section == AnalysisSectionType.IGNORED) {
            leaveIgnoredSection()
            return
        }
        analysisSectionStack.removeLast().ensure { currentSection ->
            currentSection == section || (currentSection.isCallStackPropagating() && section < currentSection)
        }
        if (section == AnalysisSectionType.ATOMIC) {
            leaveIgnoredSection()
        }
    }
}

/**
 * Style of trace collecting and output
 *
 * Can be passed as a fourth argument to agent, in any case.
 *
 * Default is [BINARY_STREAM]
 */
enum class TraceCollectorMode {
    /**
     * Write a binary format directly to the output file, without
     * collecting it in memory.
     *
     * It is the default mode if a parameter is not passed or cannot be
     * recognized.
     */
    BINARY_STREAM,

    /**
     * Collect full trace in the memory and dump to the output file at the end
     * of the run.
     */
    BINARY_DUMP,

    /**
     * Collect full trace in memory and print it as text to the output file,
     * without code locations.
     */
    TEXT,

    /**
     * Collect full trace in memory and print it as text to the output file,
     * with code locations.
     */
    TEXT_VERBOSE
}

fun parseOutputMode(outputMode: String?, outputOption: String?): TraceCollectorMode {
    if (outputMode == null) return TraceCollectorMode.BINARY_STREAM
    if ("binary".startsWith(outputMode, true)) {
        if (outputOption != null && "dump".startsWith(outputOption, true)) {
            return TraceCollectorMode.BINARY_DUMP
        } else {
            return TraceCollectorMode.BINARY_STREAM
        }
    } else if ("text".startsWith(outputMode, true)) {
        if (outputOption != null && "verbose".startsWith(outputOption, true)) {
            return TraceCollectorMode.TEXT_VERBOSE
        } else {
            return TraceCollectorMode.TEXT
        }
    } else {
        // Default
        return TraceCollectorMode.BINARY_STREAM
    }
}

class TraceCollectingEventTracker(
    private val className: String,
    private val methodName: String,
    private val traceDumpPath: String?,
    private val mode: TraceCollectorMode,
    private val packTrace: Boolean
) : EventTracker {
    // We don't want to re-create this object each time we need it
    private val analysisProfile: AnalysisProfile = AnalysisProfile(false)

    private val metaInfo = TraceMetaInfo.start(TraceAgentParameters.rawArgs, className, methodName)

    // [ThreadDescriptor.eventTrackerData] is weak ref, so store it here too, but use
    // only at the end
    private val threads = ConcurrentHashMap<Thread, ThreadData>()

    private val strategy: TraceCollectingStrategy

    // For proper completion of threads which are not tracked from the start of the agent,
    // of those threads which are not joined by the Main thread,
    // we need to perform operations in them under the flag `inInjectedCode`.
    // Note: the only place where there is a waiting on the spinner is
    // when Main thread finishes and decides to "finish" all other running threads.
    // By "finish" here we imply that it will dump their recorded data.
    private val spinner = Spinner()

    init {
        when (mode) {
            TraceCollectorMode.BINARY_STREAM -> {
                check(traceDumpPath != null) { "Stream output type needs non-empty output file name" }
                strategy = FileStreamingTraceCollecting(traceDumpPath, TRACE_CONTEXT)
            }
            TraceCollectorMode.BINARY_DUMP -> {
                check(traceDumpPath != null) { "Binary output type needs non-empty output file name" }
                strategy = MemoryTraceCollecting(TRACE_CONTEXT)
            }
            else -> {
                strategy = MemoryTraceCollecting(TRACE_CONTEXT)
            }
        }
    }

    override fun registerRunningThread(thread: Thread, descriptor: ThreadDescriptor): Unit = runInsideIgnoredSection {
        val threadData = threads.computeIfAbsent(thread) {
            val threadData = ThreadData(threads.size)
            ThreadDescriptor.getThreadDescriptor(thread).eventTrackerData = threadData
            threadData
        }
        val thread = Thread.currentThread()
        // We need to enable analysis first, before calling `runInsideInjectedCode`, because
        // that method internally checks that the analysis is enabled before calling the provided lambda
        descriptor.enableAnalysis()

        fun appendMethodCall(obj: TRObject?, className: String, methodName: String, methodType: Types.MethodType, codeLocationId: Int, params: List<TRObject> = emptyList()) {
            val parentTracePoint = threadData.firstMethodCallTracePoint()
            val methodCall = TRMethodCallTracePoint(
                threadId = threadData.threadId,
                codeLocationId = codeLocationId,
                methodId = TRACE_CONTEXT.getOrCreateMethodId(className, methodName, methodType),
                obj = obj,
                parameters = params,
                flags = INCOMPLETE_METHOD_FLAG.toShort(),
                parentTracePoint = parentTracePoint
            )
            strategy.tracePointCreated(parentTracePoint, methodCall)
            threadData.pushStackFrame(methodCall, thread, isInline = false)
        }

        // This method does not wrap this whole method, because the analysis in this thread must
        // be enabled first in order for this method to even invoke its lambda
        runInsideInjectedCode {
            strategy.registerCurrentThread(threadData.threadId)
            for (frame in thread.stackTrace.reversed()) {
                if (frame.className == "sun.nio.ch.lincheck.Injections") break
                if (
                    !frame.isLincheckInternals &&
                    !frame.isNativeMethod &&
                    !analysisProfile.shouldBeHidden(frame.className, frame.methodName)
                ) {
                    appendMethodCall(null, frame.className, frame.methodName,  UNKNOWN_METHOD_TYPE, UNKNOWN_CODE_LOCATION_ID)
                }
            }
        }
    }

    override fun beforeThreadFork(thread: Thread, descriptor: ThreadDescriptor) = runInsideInjectedCode {
        ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        // Create new thread handle
        val forkedThreadData = ThreadData(threads.size)
        val threadDescriptor = ThreadDescriptor.getThreadDescriptor(thread)
        threadDescriptor.eventTrackerData = forkedThreadData
        threads[thread] = forkedThreadData
        // We are ready to use this
    }

    override fun beforeThreadStart() = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val thread = Thread.currentThread()
        // just like in `registerRunningThread` we first need to enable analysis
        // so that `runInsideInjectedCode` does not exit on short-path without
        // even invoking its lambda
        threadDescriptor.enableAnalysis()
        runInsideInjectedCode {
            strategy.registerCurrentThread(threadData.threadId)
            val tracePoint = TRMethodCallTracePoint(
                threadId = threadData.threadId,
                codeLocationId = -1,
                methodId = TRACE_CONTEXT.getOrCreateMethodId("Thread", "run", Types.MethodType(Types.VOID_TYPE)),
                obj = TRObject(thread),
                parameters = emptyList()
            )
            strategy.tracePointCreated(null, tracePoint)
            threadData.pushStackFrame(tracePoint, thread, isInline = false)
        }
    }

    override fun afterThreadFinish() = runInsideInjectedCode {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val thread = Thread.currentThread()

        // End all method calls, for which we did not track the method return,
        // except for the very first one (`java.lang.Thread::run` method)
        while (threadData.getStack().size > 1) {
            val tracePoint = threadData.popStackFrame()
            tracePoint.result = TR_OBJECT_UNTRACKED_METHOD_RESULT
            strategy.completeContainerTracePoint(thread, tracePoint)
        }
        // Don't pop, we need it
        val tracePoint = threadData.firstMethodCallTracePoint()!!
        tracePoint.result = TR_OBJECT_VOID
        strategy.completeContainerTracePoint(thread, tracePoint)
        strategy.completeThread(thread)
        threadDescriptor.disableAnalysis()
    }

    override fun threadJoin(thread: Thread?, withTimeout: Boolean) = Unit

    override fun onThreadRunException(exception: Throwable) = runInsideInjectedCode {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: throw exception
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: throw exception
        // Don't pop, we need it
        val tracePoint = threadData.firstMethodCallTracePoint()!!
        tracePoint.setExceptionResult(exception)
        strategy.completeContainerTracePoint(Thread.currentThread(), tracePoint)
        threadDescriptor.disableAnalysis()
        throw exception
    }

    override fun beforeLock(codeLocation: Int) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun lock(monitor: Any) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun unlock(monitor: Any, codeLocation: Int) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun beforePark(codeLocation: Int) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun park(codeLocation: Int) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun unpark(thread: Thread, codeLocation: Int) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun beforeWait(codeLocation: Int) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun wait(monitor: Any, withTimeout: Boolean) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun beforeNewObjectCreation(className: String) = runInsideIgnoredSection {
        LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
    }

    override fun afterNewObjectCreation(obj: Any) = Unit

    override fun getNextTraceDebuggerEventTrackerId(tracker: TraceDebuggerTracker): Long = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support Trace Debugger-specific instrumentation")
        return 0
    }

    override fun advanceCurrentTraceDebuggerEventTrackerId(tracker: TraceDebuggerTracker, oldId: Long) =
        runInsideIgnoredSection {
            System.err.println("Trace Recorder mode doesn't support Trace Debugger-specific instrumentation")
        }

    override fun getCachedInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodData: Injections.HandlePojo,
        bootstrapMethodArguments: Array<out Any?>
    ): CallSite? = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support invoke dynamic instrumentation")
        return null
    }

    override fun cacheInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodData: Injections.HandlePojo,
        bootstrapMethodArguments: Array<out Any?>,
        callSite: CallSite
    ) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support invoke dynamic instrumentation")
    }

    override fun updateSnapshotBeforeConstructorCall(objs: Array<out Any?>) = Unit

    override fun beforeReadField(
        obj: Any?,
        codeLocation: Int,
        fieldId: Int
    ): Unit = runInsideInjectedCode {
        val fieldDescriptor = TRACE_CONTEXT.getFieldDescriptor(fieldId)
        if (fieldDescriptor.isStatic) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(fieldDescriptor.className)
        }
        return
    }

    override fun beforeReadArrayElement(
        array: Any,
        index: Int,
        codeLocation: Int
    ) = Unit

    // Needs to run inside ignored section
    // as uninstrumented std lib code can be overshadowed by instrumented project code.
    // Specifically, this function can reach `kotlin.text.StringsKt___StringsKt.take` which calls `length`.
    // In IJ platform there is a custom `length` function at `com.intellij.util.text.ImmutableText.length`
    // which overshadows kt std lib length, AND is instrumented when trace collector is used for IJ repo.
    //
    // This means that technically any function marked as silent or ignored can be overshadowed 
    // and therefore all injected functions should run inside ignored section.
    override fun afterReadField(obj: Any?, codeLocation: Int, fieldId: Int, value: Any?) = runInsideInjectedCode {
        val fieldDescriptor = TRACE_CONTEXT.getFieldDescriptor(fieldId)
        if (fieldDescriptor.isStatic) {
            if (value !== null && !value.isImmutable) {
                LincheckJavaAgent.ensureClassHierarchyIsTransformed(value.javaClass)
            }
            // NOTE: for static reads we never create trace points
            return
        }
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRReadTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            fieldId = fieldId,
            obj = TRObjectOrNull(obj),
            value = TRObjectOrNull(value)
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    override fun afterReadArrayElement(array: Any, index: Int, codeLocation: Int, value: Any?) = runInsideInjectedCode {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRReadArrayTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            array = TRObject(array),
            index = index,
            value = TRObjectOrNull(value)
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    override fun beforeWriteField(
        obj: Any?,
        value: Any?,
        codeLocation: Int,
        fieldId: Int
    ): Unit = runInsideInjectedCode {
        val fieldDescriptor = TRACE_CONTEXT.getFieldDescriptor(fieldId)
        if (!fieldDescriptor.isStatic && obj == null) {
            // Ignore, NullPointerException will be thrown
            return
        }
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRWriteTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            fieldId = fieldId,
            obj = TRObjectOrNull(obj),
            value = TRObjectOrNull(value)
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    override fun beforeWriteArrayElement(
        array: Any,
        index: Int,
        value: Any?,
        codeLocation: Int
    ): Unit = runInsideInjectedCode {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRWriteArrayTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            array = TRObject(array),
            index = index,
            value = TRObjectOrNull(value)
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    override fun afterWrite() = Unit

    override fun afterLocalRead(codeLocation: Int, variableId: Int, value: Any?) = runInsideInjectedCode {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRReadLocalVariableTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            localVariableId = variableId,
            value = TRObjectOrNull(value)
        )
        strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
    }

    override fun afterLocalWrite(codeLocation: Int, variableId: Int, value: Any?) = runInsideInjectedCode {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRWriteLocalVariableTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            localVariableId = variableId,
            value = TRObjectOrNull(value)
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    override fun onMethodCall(
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>
    ): Any? = runInsideInjectedCode<Any?> {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return null
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return null
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)

        // Should this method call be ignored?
        if (methodSection == AnalysisSectionType.IGNORED) {
            threadData.enterAnalysisSection(methodSection)
            return null
        }

        if (receiver == null && methodSection < AnalysisSectionType.ATOMIC) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(methodDescriptor.className)
        }

        val tracePoint = TRMethodCallTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            methodId = methodId,
            obj = TRObjectOrNull(receiver),
            parameters = params.map { TRObjectOrNull(it) },
            parentTracePoint = threadData.firstMethodCallTracePoint()
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
        threadData.pushStackFrame(tracePoint, receiver, isInline = false)
        // if the method has certain guarantees, enter the corresponding section
        threadData.enterAnalysisSection(methodSection)
        return null
    }

    override fun onMethodCallReturn(
        descriptorId: Long,
        deterministicMethodDescriptor: Any?,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        result: Any?
    ): Any? = runInsideInjectedCode(result) {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return result
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return result
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        if (methodSection == AnalysisSectionType.IGNORED) {
            threadData.leaveAnalysisSection(methodSection)
            return result
        }

        while (threadData.isCurrentMethodCallInline()) {
            val inlineTracePoint = threadData.currentMethodCallTracePoint()!!
            Logger.error {
                "Forced exit from inline method ${inlineTracePoint.methodId} "  +
                "${inlineTracePoint.className}.${inlineTracePoint.methodName}"  +
                " due to return from method $methodId "                         +
                "${methodDescriptor.className}.${methodDescriptor.methodName}"
            }
            onInlineMethodCallReturn(inlineTracePoint.methodId)
        }

        val tracePoint = threadData.popStackFrame()
        if (tracePoint.methodId != methodId) {
            Logger.error { "Return from method $methodId (${methodDescriptor.className}.${methodDescriptor.methodName}) but on stack ${tracePoint.methodId} (${tracePoint.className}.${tracePoint.methodName})" }
        }

        tracePoint.result = TRObjectOrVoid(result)
        strategy.completeContainerTracePoint(Thread.currentThread(), tracePoint)

        threadData.leaveAnalysisSection(methodSection)

        return result
    }

    override fun onMethodCallException(
        descriptorId: Long,
        deterministicMethodDescriptor: Any?,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        t: Throwable
    ): Throwable = runInsideInjectedCode(t) {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return t
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return t
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        if (methodSection == AnalysisSectionType.IGNORED) {
            threadData.leaveAnalysisSection(methodSection)
            return t
        }

        while (threadData.isCurrentMethodCallInline()) {
            val inlineTracePoint = threadData.currentMethodCallTracePoint()!!
            Logger.error {
                "Forced exit from inline method ${inlineTracePoint.methodId} "  +
                "${inlineTracePoint.className}.${inlineTracePoint.methodName}"  +
                " due to exception in method $methodId "                        +
                "${methodDescriptor.className}.${methodDescriptor.methodName}"
            }
            onInlineMethodCallException(inlineTracePoint.methodId, t)
        }

        val tracePoint = threadData.popStackFrame()
        if (tracePoint.methodId != methodId) {
            Logger.error { "Exception in method $methodId (${methodDescriptor.className}.${methodDescriptor.methodName}) but on stack ${tracePoint.methodId} (${tracePoint.className}.${tracePoint.methodName})" }
        }

        tracePoint.setExceptionResult(t)
        strategy.completeContainerTracePoint(Thread.currentThread(), tracePoint)

        threadData.leaveAnalysisSection(methodSection)

        return t
    }

    override fun onInlineMethodCall(
        methodId: Int,
        codeLocation: Int,
        owner: Any?,
    ): Unit = runInsideInjectedCode {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRMethodCallTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            methodId = methodId,
            obj = TRObjectOrNull(owner),
            parameters = emptyList(),
            parentTracePoint = threadData.firstMethodCallTracePoint()
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
        threadData.pushStackFrame(tracePoint, owner, isInline = true)
    }

    override fun onInlineMethodCallReturn(methodId: Int): Unit = runInsideInjectedCode {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = threadData.popStackFrame()
        if (tracePoint.methodId != methodId) {
            val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)
            Logger.error {
                "Return from inline method $methodId ${methodDescriptor.className}.${methodDescriptor.methodName}" +
                "but on stack ${tracePoint.methodId} ${tracePoint.className}.${tracePoint.methodName}"
            }
        }
        tracePoint.result = TR_OBJECT_VOID
        strategy.completeContainerTracePoint(Thread.currentThread(), tracePoint)
    }

    override fun onInlineMethodCallException(methodId: Int, t: Throwable): Unit = runInsideInjectedCode {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = threadData.popStackFrame()
        if (tracePoint.methodId != methodId) {
            val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)
            Logger.error {
                "Exception in inline method $methodId ${methodDescriptor.className}.${methodDescriptor.methodName}" +
                "but on stack ${tracePoint.methodId} ${tracePoint.className}.${tracePoint.methodName}"
            }
        }

        tracePoint.setExceptionResult(t)
        strategy.completeContainerTracePoint(Thread.currentThread(), tracePoint)
    }

    override fun beforeLoopEnter(codeLocation: Int, loopId: Int) {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRLoopTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            loopId = loopId,
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
        threadData.enterLoop(tracePoint)
    }

    override fun onLoopIteration(codeLocation: Int, loopId: Int) {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return


        val currentLoopTracePoint = threadData.currentLoopTracePoint().ensureNotNull {
            "Unexpected loop iteration: no loop trace point found"
        }
        // complete previous iteration, if any
        threadData.currentLoopIterationTracePoint()?.also { previousIteration ->
            strategy.completeContainerTracePoint(Thread.currentThread(), previousIteration)
        }

        val tracePoint = TRLoopIterationTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            loopId = loopId,
            loopIteration = currentLoopTracePoint.iterations,
        )
        strategy.tracePointCreated(currentLoopTracePoint, tracePoint)
        threadData.addLoopIteration(tracePoint)
    }

    override fun afterLoopExit(codeLocation: Int, loopId: Int, canEnterFromOutsideLoop: Boolean) {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        var currentLoopTracePoint = threadData.currentLoopTracePoint()!!
        if (!canEnterFromOutsideLoop) {
            // TODO: perhaps better to use logging instead of throwing exception
            check(currentLoopTracePoint.loopId == loopId) {
                "Unexpected loop exit: expected loopId ${currentLoopTracePoint.loopId}, but was $loopId"
            }
            strategy.completeContainerTracePoint(Thread.currentThread(), currentLoopTracePoint)
            threadData.exitLoop()
        } else {
            do {
                currentLoopTracePoint = threadData.currentLoopTracePoint() ?: break
                strategy.completeContainerTracePoint(Thread.currentThread(), currentLoopTracePoint)
                threadData.exitLoop()
            } while (currentLoopTracePoint.loopId != loopId)
        }
    }

    override fun afterLoopExceptionExit(
        codeLocation: Int,
        loopId: Int,
        exception: Throwable,
        canEnterFromOutsideLoop: Boolean
    ) {
        // TODO: should we do something about exception?
        afterLoopExit(codeLocation, loopId, canEnterFromOutsideLoop)
    }

    override fun invokeDeterministicallyOrNull(
        descriptorId: Long,
        descriptor: Any?,
        receiver: Any?,
        params: Array<out Any?>
    ): BootstrapResult<*>? = null

    override fun getThreadLocalRandom(): InjectedRandom  = runInsideIgnoredSection {
        val msg = "Trace Recorder mode doesn't support Random calls determinism"
        System.err.println(msg)
        error(msg)
    }

    override fun randomNextInt(): Int = runInsideIgnoredSection {
        val msg = "Trace Recorder mode doesn't support Random calls determinism"
        System.err.println(msg)
        error(msg)
    }

    override fun shouldInvokeBeforeEvent(): Boolean = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support IDEA Plugin integration")
        return false
    }

    override fun beforeEvent(eventId: Int, type: String) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support IDEA Plugin integration")
    }

    override fun getCurrentEventId(): Int = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support IDEA Plugin integration")
        return -1
    }

    private var startTime = System.currentTimeMillis()

    fun enableTrace() {
        // Start tracing in this thread
        val thread = Thread.currentThread()
        val threadData = ThreadData(threads.size)
        ThreadDescriptor.getCurrentThreadDescriptor().eventTrackerData = threadData
        threads[thread] = threadData

        val tracePoint = TRMethodCallTracePoint(
            threadId = threadData.threadId,
            codeLocationId = UNKNOWN_CODE_LOCATION_ID,
            methodId = TRACE_CONTEXT.getOrCreateMethodId(className, methodName, Types.MethodType(Types.VOID_TYPE)),
            obj = null,
            parameters = emptyList()
        )
        strategy.registerCurrentThread(threadData.threadId)
        strategy.tracePointCreated(null,tracePoint)
        threadData.pushStackFrame(tracePoint, null, isInline = false)

        startTime = System.currentTimeMillis()
    }

    fun completeRunningThread(thread: Thread, threadDescriptor: ThreadDescriptor) {
        require(!threadDescriptor.isAnalysisEnabled) { "When completing a Thread ${thread.name} (${thread.id}), its analysis is expected to be disabled" }
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        // wait until that thread finishes whatever injected code it is executing right now
        spinner.spinWaitUntil { !threadDescriptor.isInsideInjectedCode }
        // now, we are sure that another thread has finished its injected code
        // and will not attempt to execute anything else, because we disabled analysis in it
        val stackFrames = threadData.getStack().asReversed()
        stackFrames.forEach { frame ->
            val tracePoint = frame.call
            tracePoint.result = TR_OBJECT_UNFINISHED_METHOD_RESULT
            strategy.completeContainerTracePoint(thread, tracePoint)
        }
        strategy.completeThread(thread)
    }

    fun finishAndDumpTrace() {
        // Finish existing threads, except for Main
        val mainThread = Thread.currentThread()
        threads
            .mapNotNull { (thread, _) ->
                if (thread == mainThread) null
                else {
                    val threadDescriptor = ThreadDescriptor.getThreadDescriptor(thread)
                    if (threadDescriptor == null || !threadDescriptor.isAnalysisEnabled) null
                    else {
                        // tell `thread` not to track its tracepoints anymore
                        threadDescriptor.disableAnalysis()
                        thread to threadDescriptor
                    }
                }
            }
            .forEach { (thread, threadDescriptor) ->
                completeRunningThread(thread, threadDescriptor)
            }

        // Close this thread callstack
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return
        val tracePoint = threadData.currentMethodCallTracePoint()!!
        tracePoint.result = TR_OBJECT_VOID
        strategy.completeContainerTracePoint(Thread.currentThread(), tracePoint)

        val allThreads = mutableListOf<ThreadData>()
        allThreads.addAll(threads.values)
        threads.clear()

        strategy.traceEnded()
        metaInfo.traceEnded()

        System.err.println("Trace collected in ${System.currentTimeMillis() - startTime} ms")
        startTime = System.currentTimeMillis()

        if (mode == TraceCollectorMode.BINARY_STREAM) {
            if (packTrace) {
                packRecordedTrace(traceDumpPath!!, metaInfo)
            }
            return
        }

        try {
            val appendable = DefaultTRTextAppendable(System.err)
            val roots = mutableListOf<TRTracePoint>()

            allThreads.sortBy { it.threadId }
            allThreads.forEach { thread ->
                val st = thread.getStack()
                if (st.isEmpty()) {
                    System.err.println("Trace Recorder: Thread ${thread.threadId + 1}: Stack underflow, report bug")
                } else {
                    if (st.size > 1) {
                        System.err.println("Trace Recorder: Thread ${thread.threadId + 1}: Stack is not empty, contains ${st.size} elements, report bug")
                        System.err.println("Stack leftover:")
                        st.reversed().forEach {
                            appendable.append("  ")
                            it.call.toText(appendable)
                            appendable.append("\n")
                        }
                    }
                    roots.add(st.first().call)
                }
            }
            when (mode) {
                TraceCollectorMode.BINARY_DUMP -> {
                    saveRecorderTrace(traceDumpPath!!, TRACE_CONTEXT, roots)
                    if (packTrace) {
                        packRecordedTrace(traceDumpPath, metaInfo)
                    }
                }
                TraceCollectorMode.TEXT -> printPostProcessedTrace(traceDumpPath, TRACE_CONTEXT, roots, false)
                TraceCollectorMode.TEXT_VERBOSE -> printPostProcessedTrace(traceDumpPath, TRACE_CONTEXT, roots, true)
                TraceCollectorMode.BINARY_STREAM -> {}
            }
        } catch (t: Throwable) {
            System.err.println("TraceRecorder: Cannot write output file $traceDumpPath: ${t.message} at ${t.stackTraceToString()}")
            return
        } finally {
            System.err.println("Trace dumped in ${System.currentTimeMillis() - startTime} ms")
        }
    }

    private fun methodAnalysisSectionType(
        owner: Any?,
        className: String,
        methodName: String
    ): AnalysisSectionType {
        val ownerName = owner?.javaClass?.canonicalName ?: className
        // Ignore methods called on standard I/O streams
        if (owner === System.`in` || owner === System.out || owner === System.err) {
            return AnalysisSectionType.IGNORED
        }
        // Do not track Collection.size() calls
        if (methodName == "size" && owner is Collection<*>) {
            return AnalysisSectionType.IGNORED
        }
        return analysisProfile.getAnalysisSectionFor(ownerName, methodName)
    }
}
