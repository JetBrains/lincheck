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
import org.jetbrains.lincheck.trace.TRACE_CONTEXT
import org.jetbrains.lincheck.jvm.agent.LincheckJavaAgent
import org.jetbrains.lincheck.jvm.agent.TraceAgentParameters
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.util.*
import sun.nio.ch.lincheck.*
import java.lang.invoke.CallSite
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private class ThreadData(
    val threadId: Int
) {
    data class StackFrame(
        val call: TRMethodCallTracePoint,
        val shadow: ShadowStackFrame,
        val isInline: Boolean
    )

    private val stack: MutableList<StackFrame> = arrayListOf()
    private val analysisSectionStack: MutableList<AnalysisSectionType> = arrayListOf()

    fun currentMethodCallTracePoint(): TRMethodCallTracePoint = stack.last().call

    fun isCurrentMethodCallInline(): Boolean = stack.last().isInline

    fun firstMethodCallTracePoint(): TRMethodCallTracePoint = stack.first().call

    fun pushStackFrame(tracePoint: TRMethodCallTracePoint, instance: Any?, isInline: Boolean) {
        val stackFrame = ShadowStackFrame(instance)
        stack.add(StackFrame(
            call = tracePoint,
            shadow = stackFrame,
            isInline = isInline
        ))
    }

    fun popStackFrame(): TRMethodCallTracePoint {
        val frame = stack.removeLast()
        return frame.call
    }

    fun getStack(): List<StackFrame> = stack

    fun enterAnalysisSection(section: AnalysisSectionType) {
        val currentSection = analysisSectionStack.lastOrNull()
        if (currentSection != null && currentSection.isCallStackPropagating() && section < currentSection) {
            analysisSectionStack.add(currentSection)
        } else {
            analysisSectionStack.add(section)
        }
        if (section == AnalysisSectionType.IGNORED || section == AnalysisSectionType.ATOMIC) {
            enterIgnoredSection()
        }
    }

    fun leaveAnalysisSection(section: AnalysisSectionType) {
        if (section == AnalysisSectionType.IGNORED ||
            // TODO: atomic should have different semantics compared to ignored
            section == AnalysisSectionType.ATOMIC
        ) {
            leaveIgnoredSection()
        }
        analysisSectionStack.removeLast().ensure { currentSection ->
            currentSection == section || (currentSection.isCallStackPropagating() && section < currentSection)
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

    // For proper completion of threads which are not tracked from the start of the agent,
    // of those threads which are not joined by the Main thread,
    // we need to perform operations in them under locks.
    // Note: the only place where there is a contention on the locks is
    // when Main thread finishes and decides to "finish" all other running threads.
    // By "finish" here we imply that it will dump their recorded data.
    private val locks = ConcurrentHashMap<Thread, ReentrantLock>()

    private val strategy: TraceCollectingStrategy

    init {
        when (mode) {
            TraceCollectorMode.BINARY_STREAM -> {
                check(traceDumpPath != null) { "Stream output type needs non-empty output file name" }
                strategy = FileStreamingTraceCollecting(traceDumpPath, TRACE_CONTEXT)
            }
            TraceCollectorMode.BINARY_DUMP -> {
                check(traceDumpPath != null) { "Binary output type needs non-empty output file name" }
                strategy = MemoryTraceCollecting()
            }
            else -> {
                strategy = MemoryTraceCollecting()
            }
        }
    }

    /**
     * Runs [block] under the `lock` of the `thread`, stored in the thread descriptor
     * with the condition that analysis in that thread is still enabled.
     */
    private inline fun ThreadDescriptor.runUnderLockIfAnalysisEnabled(block: () -> Unit) {
        locks.computeIfAbsent(thread) { ReentrantLock() }.withLock {
            if (isAnalysisEnabled) {
                block()
            }
        }
    }

    /**
     * Runs [block] under the `lock` of the `thread`, stored in the thread descriptor.
     */
    private inline fun ThreadDescriptor.runUnderLock(block: () -> Unit) {
        locks.computeIfAbsent(thread) { ReentrantLock() }.withLock {
            block()
        }
    }

    override fun beforeExistingThreadTracking(thread: Thread, descriptor: ThreadDescriptor) {
        val threadData = threads.computeIfAbsent(thread) {
            val threadData = ThreadData(threads.size)
            ThreadDescriptor.getThreadDescriptor(thread).eventTrackerData = threadData
            threadData
        }

        descriptor.runUnderLock {
            strategy.registerCurrentThread(threadData.threadId)
            // TODO: create a proper virtual trace point for starting the existing thread
            val tracePoint = TRMethodCallTracePoint(
                threadId = threadData.threadId,
                codeLocationId = -1,
                methodId = TRACE_CONTEXT.getOrCreateMethodId("Thread", "run", "()V"),
                obj = TRObject(thread),
                parameters = emptyList()
            )
            strategy.tracePointCreated(null, tracePoint)
            threadData.pushStackFrame(tracePoint, Thread.currentThread(), isInline = false)
            descriptor.enableAnalysis()
        }
    }

    override fun beforeThreadFork(thread: Thread, descriptor: ThreadDescriptor) = runInsideIgnoredSection {
        ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        // Create new thread handle
        val forkedThreadData = ThreadData(threads.size)
        val threadDescriptor = ThreadDescriptor.getThreadDescriptor(thread)
        threadDescriptor.eventTrackerData = forkedThreadData
        threads[thread] = forkedThreadData
        locks[thread] = ReentrantLock()
        // We are ready to use this
    }

    override fun beforeThreadStart() = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val thread = Thread.currentThread()

        threadDescriptor.runUnderLock {
            strategy.registerCurrentThread(threadData.threadId)
            val tracePoint = TRMethodCallTracePoint(
                threadId = threadData.threadId,
                codeLocationId = -1,
                methodId = TRACE_CONTEXT.getOrCreateMethodId("Thread", "run", "()V"),
                obj = TRObject(thread),
                parameters = emptyList()
            )
            strategy.tracePointCreated(null, tracePoint)
            threadData.pushStackFrame(tracePoint, thread, isInline = false)
            threadDescriptor.enableAnalysis()
        }
    }

    override fun afterThreadFinish() = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val thread = Thread.currentThread()

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            // Don't pop, we need it
            val tracePoint = threadData.firstMethodCallTracePoint()
            tracePoint.result = TR_OBJECT_VOID
            strategy.callEnded(thread, tracePoint)
            strategy.completeThread(thread)
            threadDescriptor.disableAnalysis()
        }
    }

    override fun threadJoin(thread: Thread?, withTimeout: Boolean) = Unit

    override fun onThreadRunException(exception: Throwable) = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: throw exception
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: throw exception
        // Don't pop, we need it
        threadDescriptor.runUnderLockIfAnalysisEnabled {
            val tracePoint = threadData.firstMethodCallTracePoint()
            tracePoint.exceptionClassName = exception::class.java.name
            strategy.callEnded(Thread.currentThread(), tracePoint)
            threadDescriptor.disableAnalysis()
            throw exception
        }
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
    ): Unit = runInsideIgnoredSection {
        val fieldDescriptor = TRACE_CONTEXT.getFieldDescriptor(fieldId)
        if (fieldDescriptor.isStatic) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
        }
        return
    }

    override fun beforeReadArrayElement(
        array: Any,
        index: Int,
        codeLocation: Int
    ) {}

    // Needs to run inside ignored section
    // as uninstrumented std lib code can be overshadowed by instrumented project code.
    // Specifically, this function can reach `kotlin.text.StringsKt___StringsKt.take` which calls `length`.
    // In IJ platform there is a custom `length` function at `com.intellij.util.text.ImmutableText.length`
    // which overshadows kt std lib length, AND is instrumented when trace collector is used for IJ repo.
    //
    // This means that technically any function marked as silent or ignored can be overshadowed 
    // and therefore all injected functions should run inside ignored section.
    override fun afterReadField(obj: Any?, codeLocation: Int, fieldId: Int, value: Any?) = runInsideIgnoredSection {
        val fieldDescriptor = TRACE_CONTEXT.getFieldDescriptor(fieldId)
        if (fieldDescriptor.isStatic && value !== null && !value.isImmutable) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(value.javaClass)
        }
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            val tracePoint = TRReadTracePoint(
                threadId = threadData.threadId,
                codeLocationId = codeLocation,
                fieldId = fieldId,
                obj = TRObjectOrNull(obj),
                value = TRObjectOrNull(value)
            )
            strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
        }
    }

    override fun afterReadArrayElement(array: Any, index: Int, codeLocation: Int, value: Any?) = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            val tracePoint = TRReadArrayTracePoint(
                threadId = threadData.threadId,
                codeLocationId = codeLocation,
                array = TRObject(array),
                index = index,
                value = TRObjectOrNull(value)
            )
            strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
        }
    }

    override fun beforeWriteField(
        obj: Any?,
        value: Any?,
        codeLocation: Int,
        fieldId: Int
    ): Unit = runInsideIgnoredSection {
        val fieldDescriptor = TRACE_CONTEXT.getFieldDescriptor(fieldId)
        if (!fieldDescriptor.isStatic && obj == null) {
            // Ignore, NullPointerException will be thrown
            return
        }
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            val tracePoint = TRWriteTracePoint(
                threadId = threadData.threadId,
                codeLocationId = codeLocation,
                fieldId = fieldId,
                obj = TRObjectOrNull(obj),
                value = TRObjectOrNull(value)
            )
            strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
        }
    }

    override fun beforeWriteArrayElement(
        array: Any,
        index: Int,
        value: Any?,
        codeLocation: Int
    ): Unit = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            val tracePoint = TRWriteArrayTracePoint(
                threadId = threadData.threadId,
                codeLocationId = codeLocation,
                array = TRObject(array),
                index = index,
                value = TRObjectOrNull(value)
            )
            strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
        }
    }

    override fun afterWrite() = Unit

    override fun afterLocalRead(codeLocation: Int, variableId: Int, value: Any?) = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        // TODO: make in optional
/*
        val tracePoint = TRReadLocalVariableTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            localVariableId = variableId,
            value = TRObjectOrNull(value)
        )
        strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
*/
    }

    override fun afterLocalWrite(codeLocation: Int, variableId: Int, value: Any?) = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            val tracePoint = TRWriteLocalVariableTracePoint(
                threadId = threadData.threadId,
                codeLocationId = codeLocation,
                localVariableId = variableId,
                value = TRObjectOrNull(value)
            )
            strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
        }
    }

    override fun onMethodCall(
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>
    ): Any? = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return null
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return null
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        if (receiver == null && methodSection < AnalysisSectionType.ATOMIC) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(methodDescriptor.className)
        }

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            val tracePoint = TRMethodCallTracePoint(
                threadId = threadData.threadId,
                codeLocationId = codeLocation,
                methodId = methodId,
                obj = TRObjectOrNull(receiver),
                parameters = params.map { TRObjectOrNull(it) }
            )
            strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
            threadData.pushStackFrame(tracePoint, receiver, isInline = false)
            // if the method has certain guarantees, enter the corresponding section
            threadData.enterAnalysisSection(methodSection)
        }
        return null
    }

    override fun onMethodCallReturn(
        descriptorId: Long,
        deterministicMethodDescriptor: Any?,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        result: Any?
    ): Any? = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return result
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return result
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            while (threadData.isCurrentMethodCallInline()) {
                val inlineTracePoint = threadData.currentMethodCallTracePoint()
                Logger.error { "Forced exit from inline method ${inlineTracePoint.methodId} (${inlineTracePoint.className}.${inlineTracePoint.methodName}) due to return from method $methodId (${methodDescriptor.className}.${methodDescriptor.methodName})" }
                onInlineMethodCallReturn(inlineTracePoint.methodId)
            }

            val tracePoint = threadData.popStackFrame()
            if (tracePoint.methodId != methodId) {
                Logger.error { "Return from method $methodId (${methodDescriptor.className}.${methodDescriptor.methodName}) but on stack ${tracePoint.methodId} (${tracePoint.className}.${tracePoint.methodName})" }
            }

            tracePoint.result = TRObjectOrVoid(result)
            strategy.callEnded(Thread.currentThread(), tracePoint)

            val methodSection = methodAnalysisSectionType(receiver, tracePoint.className, tracePoint.methodName)
            threadData.leaveAnalysisSection(methodSection)
        }

        return result
    }

    override fun onMethodCallException(
        descriptorId: Long,
        deterministicMethodDescriptor: Any?,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        t: Throwable
    ): Throwable = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return t
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return t
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            while (threadData.isCurrentMethodCallInline()) {
                val inlineTracePoint = threadData.currentMethodCallTracePoint()
                Logger.error { "Forced exit from inline method ${inlineTracePoint.methodId} (${inlineTracePoint.className}.${inlineTracePoint.methodName}) due to exception in method $methodId (${methodDescriptor.className}.${methodDescriptor.methodName})" }
                onInlineMethodCallException(inlineTracePoint.methodId, t)
            }

            val tracePoint = threadData.popStackFrame()
            if (tracePoint.methodId != methodId) {
                Logger.error { "Exception in method $methodId (${methodDescriptor.className}.${methodDescriptor.methodName}) but on stack ${tracePoint.methodId} (${tracePoint.className}.${tracePoint.methodName})" }
            }

            tracePoint.exceptionClassName = t.javaClass.name
            strategy.callEnded(Thread.currentThread(), tracePoint)

            val methodSection = methodAnalysisSectionType(receiver, tracePoint.className, tracePoint.methodName)
            threadData.leaveAnalysisSection(methodSection)
        }

        return t
    }

    override fun onInlineMethodCall(
        methodId: Int,
        codeLocation: Int,
        owner: Any?,
    ): Unit = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            val tracePoint = TRMethodCallTracePoint(
                threadId = threadData.threadId,
                codeLocationId = codeLocation,
                methodId = methodId,
                obj = TRObjectOrNull(owner),
                parameters = emptyList()
            )
            strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
            threadData.pushStackFrame(tracePoint, owner, isInline = true)
        }
    }

    override fun onInlineMethodCallReturn(methodId: Int): Unit = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            val tracePoint = threadData.popStackFrame()
            if (tracePoint.methodId != methodId) {
                val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)
                Logger.error { "Return from inline method $methodId (${methodDescriptor.className}.${methodDescriptor.methodName}) but on stack ${tracePoint.methodId} (${tracePoint.className}.${tracePoint.methodName})" }
            }
            tracePoint.result = TR_OBJECT_VOID
            strategy.callEnded(Thread.currentThread(), tracePoint)
        }
    }

    override fun onInlineMethodCallException(methodId: Int, t: Throwable): Unit = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            val tracePoint = threadData.popStackFrame()
            if (tracePoint.methodId != methodId) {
                val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)
                Logger.error { "Exception in inline method $methodId (${methodDescriptor.className}.${methodDescriptor.methodName}) but on stack ${tracePoint.methodId} (${tracePoint.className}.${tracePoint.methodName})" }
            }

            tracePoint.exceptionClassName = t.javaClass.name
            strategy.callEnded(Thread.currentThread(), tracePoint)
        }
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
            methodId = TRACE_CONTEXT.getOrCreateMethodId(className, methodName, "()V"),
            obj = null,
            parameters = emptyList()
        )
        strategy.registerCurrentThread(threadData.threadId)
        strategy.tracePointCreated(null,tracePoint)
        threadData.pushStackFrame(tracePoint, null, isInline = false)

        startTime = System.currentTimeMillis()
    }

    fun finishRunningThread(thread: Thread) {
        val threadDescriptor = ThreadDescriptor.getThreadDescriptor(thread) ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        threadDescriptor.runUnderLockIfAnalysisEnabled {
            val stackFrames = threadData.getStack().asReversed()
            stackFrames.forEach { frame ->
                val tracePoint = frame.call
                tracePoint.result = TR_OBJECT_UNFINISHED_METHOD_RESULT
                strategy.callEnded(thread, tracePoint)
            }
            strategy.completeThread(thread)
            threadDescriptor.disableAnalysis()
        }
    }

    fun finishAndDumpTrace() {
        // TODO:
        //  1. disable analysis in all still-running threads
        //     a. finish threads via CAS, so `afterThreadFinish` and `finishRunningThread` are not called simultaneously
        //  2. we should write the ends of footers of each still-open MethodCallTracePoint's
        //  3. - mark them some-how that their body wasn't finished when main finished
        //  4. collect the prefix of the existing trace and create trace points for that
        //  5. - mark existing threads that their prefixes are missing

        // Finish existing threads, except for Main
        val mainThread = Thread.currentThread()
        threads.forEach { (thread, _) ->
            if (thread != mainThread) {
                finishRunningThread(thread)
            }
        }

        // Close this thread callstack
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return
        val tracePoint = threadData.currentMethodCallTracePoint()
        tracePoint.result = TR_OBJECT_VOID
        strategy.callEnded(Thread.currentThread(), tracePoint)

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
        return analysisProfile.getAnalysisSectionFor(ownerName, methodName)
    }
}
