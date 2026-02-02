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
import org.jetbrains.lincheck.descriptors.CodeLocations
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.trace.TRMethodCallTracePoint.Companion.INCOMPLETE_METHOD_FLAG
import org.jetbrains.lincheck.util.*
import sun.nio.ch.lincheck.*
import java.lang.invoke.CallSite
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    var rootCall: TRMethodCallTracePoint? = null
        private set

    private val stack: MutableList<StackFrame> = arrayListOf()
    private val analysisSectionStack: MutableList<AnalysisSectionType> = arrayListOf()

    fun setRootCall(rootCall: TRMethodCallTracePoint) {
        check(this.rootCall == null) { "Root call tracepoint can be set only once" }
        this.rootCall = rootCall
    }

    fun currentMethodCallTracePoint(): TRMethodCallTracePoint? =
        stack.lastOrNull()?.call

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

class TraceCollectingEventTracker(
    internal val mode: TraceRecordingMode,
    internal val context: TraceContext,
    internal val traceStreamingFilePath: String? = null, // should be non-null for BINARY_STREAM mode
) : EventTracker {
    // Analysis profile for tracing --- tells what methods should be analyzed or ignored
    private val analysisProfile: AnalysisProfile = AnalysisProfile(analyzeStdLib = false)

    // [ThreadDescriptor.eventTrackerData] is a weak reference,
    // so store it here too, but use only at the end
    private val threads = ConcurrentHashMap<Thread, ThreadData>()

    // Assign unique, monotonically increasing ids to threads. Using threads.size for
    // id assignment is racy: two threads starting concurrently can observe the same size
    // and get identical ids, which corrupts the trace/index.
    // Atomic counter guarantees uniqueness across threads.
    private val nextThreadId = AtomicInteger(0)

    // Strategy for collecting trace points
    private val strategy: TraceCollectingStrategy

    init {
        when (mode) {
            is TraceRecordingMode.BinaryFileDump -> {
                strategy = MemoryTraceCollecting(context)
            }
            is TraceRecordingMode.BinaryFileStream -> {
                check(traceStreamingFilePath != null) { "Stream output type needs non-empty output file name" }
                strategy = FileStreamingTraceCollecting(traceStreamingFilePath, context)
            }
            is TraceRecordingMode.BinaryTcpStream -> {
                strategy = TcpStreamingTraceCollecting(mode.host, mode.port, context)
            }
            is TraceRecordingMode.Null -> {
                strategy = NullTraceCollecting(context)
            }
            else -> {
                strategy = MemoryTraceCollecting(context)
            }
        }
    }

    // For proper completion of threads which are not tracked from the start of the agent,
    // of those threads which are not joined by the Main thread,
    // we need to perform operations in them under the flag `inInjectedCode`.
    // Note: the only place where there is a waiting on the spinner is
    // when Main thread finishes and decides to "finish" all other running threads.
    // By "finish" here we imply that it will dump their recorded data.
    private val spinner = Spinner()

    override fun registerRunningThread(descriptor: ThreadDescriptor, thread: Thread): Unit = runInsideIgnoredSection {
        // must be outside of the `computeIfAbsent` call, because its body
        // might be invoked multiple times due to concurrent invocations
        // https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentMap.html#computeIfAbsent-K-java.util.function.Function-
        val assignedThreadId = nextThreadId.getAndIncrement()
        val threadData = threads.computeIfAbsent(thread) {
            ThreadData(assignedThreadId).also {
                ThreadDescriptor.getThreadDescriptor(thread).eventTrackerData = it
            }
        }

        // We need to enable analysis first, before calling `runInsideInjectedCode`, because
        // that method internally checks that the analysis is enabled before calling the provided lambda
        descriptor.enableAnalysis()

        // This method does not wrap this whole method, because the analysis in this thread must
        // be enabled first in order for this method to even invoke its lambda
        descriptor.runInsideInjectedCode {
            strategy.registerCurrentThread(threadData.threadId)

            if (isInLiveDebuggerMode) return // TODO: refactor this check!

            pushInvokedMethodCalls(thread, threadData)
        }
    }

    override fun beforeThreadStart(
        threadDescriptor: ThreadDescriptor,
        startingThread: Thread,
        startingThreadDescriptor: ThreadDescriptor
    ) {}

    override fun onThreadJoin(threadDescriptor: ThreadDescriptor, thread: Thread?, withTimeout: Boolean) {}

    override fun beforeThreadRun(threadDescriptor: ThreadDescriptor) = threadDescriptor.runInsideIgnoredSection {
        // Create new thread data
        val threadData = ThreadData(nextThreadId.getAndIncrement())
        val thread = Thread.currentThread()
        // Register thread data
        threadDescriptor.eventTrackerData = threadData
        threads[thread] = threadData

        // just like in `registerRunningThread` we first need to enable analysis
        // so that `runInsideInjectedCode` does not exit on short-path without
        // even invoking its lambda
        threadDescriptor.enableAnalysis()

        threadDescriptor.runInsideInjectedCode {
            strategy.registerCurrentThread(threadData.threadId)

            if (isInLiveDebuggerMode) return // TODO: refactor this check!

            val tracePoint = TRMethodCallTracePoint(
                context = context,
                threadId = threadData.threadId,
                codeLocationId = -1,
                methodId = context.getOrCreateMethodId("Thread", "run", Types.MethodType(Types.VOID_TYPE)),
                obj = TRObject(context, thread),
                parameters = emptyList()
            )
            strategy.tracePointCreated(null, tracePoint)
            threadData.setRootCall(tracePoint)
            threadData.pushStackFrame(tracePoint, thread, isInline = false)
        }
    }

    override fun afterThreadRunReturn(threadDescriptor: ThreadDescriptor) = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        completeInvokedMethodCalls(Thread.currentThread(), threadData) { _, tp -> tp.result = TR_OBJECT_UNTRACKED_METHOD_RESULT }
        threadDescriptor.disableAnalysis()
    }

    override fun afterThreadRunException(
        threadDescriptor: ThreadDescriptor,
        exception: Throwable
    ) = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: throw exception
        completeInvokedMethodCalls(Thread.currentThread(), threadData) { _, tp -> tp.setExceptionResult(exception) }
        threadDescriptor.disableAnalysis()
        throw exception
    }

    override fun beforeLock(threadDescriptor: ThreadDescriptor, codeLocation: Int) = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support lock and monitor instrumentation" }
    }

    override fun lock(threadDescriptor: ThreadDescriptor, monitor: Any) = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support lock and monitor instrumentation" }
    }

    override fun unlock(threadDescriptor: ThreadDescriptor, codeLocation: Int, monitor: Any) = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support lock and monitor instrumentation" }
    }

    override fun beforePark(threadDescriptor: ThreadDescriptor, codeLocation: Int) = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support lock and monitor instrumentation" }
    }

    override fun park(threadDescriptor: ThreadDescriptor, codeLocation: Int) = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support lock and monitor instrumentation" }
    }

    override fun unpark(threadDescriptor: ThreadDescriptor, codeLocation: Int, thread: Thread) = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support lock and monitor instrumentation" }
    }

    override fun beforeWait(threadDescriptor: ThreadDescriptor, codeLocation: Int) = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support lock and monitor instrumentation" }
    }

    override fun wait(threadDescriptor: ThreadDescriptor, monitor: Any, withTimeout: Boolean) = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support lock and monitor instrumentation" }
    }

    override fun notify(threadDescriptor: ThreadDescriptor, codeLocation: Int, monitor: Any, notifyAll: Boolean) = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support lock and monitor instrumentation" }
    }

    override fun beforeNewObjectCreation(threadDescriptor: ThreadDescriptor, className: String) {}
    override fun afterNewObjectCreation(threadDescriptor: ThreadDescriptor, obj: Any) {}

    override fun getNextTraceDebuggerEventTrackerId(tracker: TraceDebuggerTracker): Long = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support Trace Debugger-specific instrumentation" }
        return 0L
    }

    override fun advanceCurrentTraceDebuggerEventTrackerId(tracker: TraceDebuggerTracker, oldId: Long) =
        runInsideIgnoredSection {
            Logger. error { "Trace Recorder mode doesn't support Trace Debugger-specific instrumentation" }
        }

    override fun getCachedInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodData: Injections.HandlePojo,
        bootstrapMethodArguments: Array<out Any?>
    ): CallSite? = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support invoke dynamic instrumentation" }
        return null
    }

    override fun cacheInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodData: Injections.HandlePojo,
        bootstrapMethodArguments: Array<out Any?>,
        callSite: CallSite,
    ) = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support invoke dynamic instrumentation" }
    }

    override fun updateSnapshotBeforeConstructorCall(objs: Array<out Any?>) {}

    override fun beforeReadField(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        obj: Any?,
        fieldId: Int,
        resultInterceptor: ResultInterceptor?
    ) {}

    override fun beforeReadArrayElement(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        array: Any,
        index: Int,
        resultInterceptor: ResultInterceptor?
    ) {}


    // Needs to run inside ignored section
    // as uninstrumented std lib code can be overshadowed by instrumented project code.
    // Specifically, this function can reach `kotlin.text.StringsKt___StringsKt.take` which calls `length`.
    // In IJ platform there is a custom `length` function at `com.intellij.util.text.ImmutableText.length`
    // which overshadows kt std lib length, AND is instrumented when trace collector is used for IJ repo.
    //
    // This means that technically any function marked as silent or ignored can be overshadowed 
    // and therefore all injected functions should run inside ignored section.
    override fun afterReadField(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        obj: Any?,
        fieldId: Int,
        value: Any?
    ) = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val tracePoint = TRReadTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            fieldId = fieldId,
            obj = TRObjectOrNull(context, obj),
            value = TRObjectOrNull(context, value)
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    override fun afterReadArrayElement(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        array: Any,
        index: Int,
        value: Any?
    ) = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRReadArrayTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            array = TRObject(context, array),
            index = index,
            value = TRObjectOrNull(context, value)
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    override fun beforeWriteField(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        obj: Any?,
        value: Any?,
        fieldId: Int,
    ): Unit = threadDescriptor.runInsideInjectedCode {
        val fieldDescriptor = context.getFieldDescriptor(fieldId)
        if (!fieldDescriptor.isStatic && obj == null) {
            // Ignore, NullPointerException will be thrown
            return
        }
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRWriteTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            fieldId = fieldId,
            obj = TRObjectOrNull(context, obj),
            value = TRObjectOrNull(context, value)
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    override fun beforeWriteArrayElement(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        array: Any,
        index: Int,
        value: Any?,
    ): Unit = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRWriteArrayTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            array = TRObject(context, array),
            index = index,
            value = TRObjectOrNull(context, value)
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    override fun afterWrite(threadDescriptor: ThreadDescriptor) {}

    override fun afterLocalRead(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        variableId: Int,
        value: Any?
    ) = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val tracePoint = TRReadLocalVariableTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            localVariableId = variableId,
            value = TRObjectOrNull(context, value)
        )
        strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
    }

    override fun afterLocalWrite(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        variableId: Int,
        value: Any?
    ) = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val tracePoint = TRWriteLocalVariableTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            localVariableId = variableId,
            value = TRObjectOrNull(context, value)
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    override fun onMethodCall(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        interceptor: ResultInterceptor?,
    ): Unit = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val methodDescriptor = context.getMethodDescriptor(methodId)

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)

        // Should this method call be ignored?
        if (methodSection == AnalysisSectionType.IGNORED) {
            threadData.enterAnalysisSection(methodSection)
            return
        }

        val parentTracepoint = threadData.currentTopTracePoint()
        val tracePoint = TRMethodCallTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            methodId = methodId,
            obj = TRObjectOrNull(context, receiver),
            parameters = params.map { TRObjectOrNull(context, it) },
            parentTracePoint = parentTracepoint,
        )
        strategy.tracePointCreated(parentTracepoint, tracePoint)
        threadData.pushStackFrame(tracePoint, receiver, isInline = false)
        // if the method has certain guarantees, enter the corresponding section
        threadData.enterAnalysisSection(methodSection)
    }

    override fun onMethodCallReturn(
        threadDescriptor: ThreadDescriptor,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        result: Any?,
        interceptor: ResultInterceptor?,
    ): Unit = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val thread = Thread.currentThread()
        val methodDescriptor = context.getMethodDescriptor(methodId)

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        if (methodSection == AnalysisSectionType.IGNORED) {
            threadData.leaveAnalysisSection(methodSection)
            return
        }

        // TODO: what about inline method calls? Inside them also could be loops.
        //  The inline methods should be closed after closing the loops inside them.
        // close all existing loops
        while (threadData.currentLoopTracePoint() != null) {
            exitCurrentLoop(thread, threadData)
        }

        while (threadData.isCurrentMethodCallInline()) {
            val inlineTracePoint = threadData.currentMethodCallTracePoint()!!
            Logger.error {
                "Forced exit from inline method ${inlineTracePoint.methodId} "  +
                "${inlineTracePoint.className}.${inlineTracePoint.methodName}"  +
                " due to return from method $methodId "                         +
                "${methodDescriptor.className}.${methodDescriptor.methodName}"
            }
            onInlineMethodCallReturn(threadDescriptor, inlineTracePoint.methodId)
        }

        val tracePoint = threadData.popStackFrame()
        if (tracePoint.methodId != methodId) {
            Logger.error {
                "Return from method $methodId ${methodDescriptor.className}.${methodDescriptor.methodName} " +
                "but on stack ${tracePoint.methodId} ${tracePoint.className}.${tracePoint.methodName}"
            }
        }

        tracePoint.result = TRObjectOrVoid(context, result)
        strategy.completeContainerTracePoint(thread, tracePoint)

        threadData.leaveAnalysisSection(methodSection)
    }

    override fun onMethodCallException(
        threadDescriptor: ThreadDescriptor,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        t: Throwable,
        interceptor: ResultInterceptor?,
    ): Unit = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val thread = Thread.currentThread()
        val methodDescriptor = context.getMethodDescriptor(methodId)

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        if (methodSection == AnalysisSectionType.IGNORED) {
            threadData.leaveAnalysisSection(methodSection)
            return
        }

        // TODO: what about inline method calls? Inside them also could be loops.
        //  The inline methods should be closed after closing the loops inside them.
        // close all existing loops
        while (threadData.currentLoopTracePoint() != null) {
            exitCurrentLoop(thread, threadData)
        }

        while (threadData.isCurrentMethodCallInline()) {
            val inlineTracePoint = threadData.currentMethodCallTracePoint()!!
            Logger.error {
                "Forced exit from inline method ${inlineTracePoint.methodId} "  +
                "${inlineTracePoint.className}.${inlineTracePoint.methodName}"  +
                " due to exception in method $methodId "                        +
                "${methodDescriptor.className}.${methodDescriptor.methodName}"
            }
            onInlineMethodCallException(threadDescriptor, inlineTracePoint.methodId, t)
        }

        val tracePoint = threadData.popStackFrame()
        if (tracePoint.methodId != methodId) {
            Logger.error {
                "Exception in method $methodId ${methodDescriptor.className}.${methodDescriptor.methodName} " +
                "but on stack ${tracePoint.methodId} ${tracePoint.className}.${tracePoint.methodName}"
            }
        }

        tracePoint.setExceptionResult(t)
        strategy.completeContainerTracePoint(thread, tracePoint)

        threadData.leaveAnalysisSection(methodSection)
    }

    override fun onInlineMethodCall(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        methodId: Int,
        owner: Any?,
    ): Unit = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRMethodCallTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            methodId = methodId,
            obj = TRObjectOrNull(context, owner),
            parameters = emptyList(),
            parentTracePoint = threadData.currentTopTracePoint()
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
        threadData.pushStackFrame(tracePoint, owner, isInline = true)
    }

    override fun onInlineMethodCallReturn(
        threadDescriptor: ThreadDescriptor,
        methodId: Int
    ): Unit = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = threadData.popStackFrame()
        if (tracePoint.methodId != methodId) {
            val methodDescriptor = context.getMethodDescriptor(methodId)
            Logger.error {
                "Return from inline method $methodId ${methodDescriptor.className}.${methodDescriptor.methodName}" +
                "but on stack ${tracePoint.methodId} ${tracePoint.className}.${tracePoint.methodName}"
            }
        }
        tracePoint.result = TR_OBJECT_VOID
        strategy.completeContainerTracePoint(Thread.currentThread(), tracePoint)
    }

    override fun onInlineMethodCallException(
        threadDescriptor: ThreadDescriptor,
        methodId: Int,
        t: Throwable
    ): Unit = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        val tracePoint = threadData.popStackFrame()
        if (tracePoint.methodId != methodId) {
            val methodDescriptor = context.getMethodDescriptor(methodId)
            Logger.error {
                "Exception in inline method $methodId ${methodDescriptor.className}.${methodDescriptor.methodName}" +
                "but on stack ${tracePoint.methodId} ${tracePoint.className}.${tracePoint.methodName}"
            }
        }

        tracePoint.setExceptionResult(t)
        strategy.completeContainerTracePoint(Thread.currentThread(), tracePoint)
    }

    override fun onSnapshotLineBreakpoint(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        locals: Array<Any?>,
        traceId: String?,
    ) = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        
        // We do not use threadData.getStack() as we might not track (all) method calls in live debug mode
        val stackTrace = Thread.currentThread().stackTrace
            // Removes lincheck related calls
            .filter { !isInLincheckPackage(it.className) }
            // Removes the getStackTrace method
            .drop(1)
        
        val stackTraceCodeLocationIds = stackTrace.map { stackTraceElement ->
            // TODO JBRes-7631 prevent duplicate code locations
            CodeLocations.newCodeLocation(context, stackTraceElement)
        }
        
        val timeStamp = System.currentTimeMillis()
        
        val tracePoint = TRSnapshotLineBreakpointTracePoint(
            context = context,
            codeLocationId = codeLocation,
            threadId = threadData.threadId,
            stackTraceCodeLocationIds = stackTraceCodeLocationIds,
            currentTimeMillis = timeStamp,
            locals = locals.map { TRObjectOrNull(context, it) },
            traceId = traceId,
        )
        // TODO maybe these tracepoints should be collected separately
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    override fun onLoopIteration(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        loopId: Int
    ) = threadDescriptor.runInsideInjectedCode {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        // create a new loop if required
        if (loopId != threadData.currentLoopTracePoint()?.loopId) {
            val tracePoint = TRLoopTracePoint(
                context = context,
                threadId = threadData.threadId,
                codeLocationId = codeLocation,
                loopId = loopId,
            )
            strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
            threadData.enterLoop(tracePoint)
        }

        val currentLoopTracePoint = threadData.currentLoopTracePoint()!!
        // complete previous iteration, if any
        threadData.currentLoopIterationTracePoint()?.also { previousIteration ->
            strategy.completeContainerTracePoint(Thread.currentThread(), previousIteration)
        }

        val tracePoint = TRLoopIterationTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            loopId = loopId,
            loopIteration = currentLoopTracePoint.iterations,
        )
        strategy.tracePointCreated(currentLoopTracePoint, tracePoint)
        threadData.addLoopIteration(tracePoint)
    }

    override fun afterLoopExit(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        loopId: Int,
        exception: Throwable?,
        isReachableFromOutsideLoop: Boolean
    ) = threadDescriptor.runInsideInjectedCode {
        // TODO: should we do something about exception?
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val thread = Thread.currentThread()
        val currentLoopTracePoint = threadData.currentLoopTracePoint()

        if (!isReachableFromOutsideLoop) {
            if (currentLoopTracePoint == null) {
                Logger.warn { "Exit from loop $loopId outside of it" }
            } else if (currentLoopTracePoint.loopId != loopId) {
                Logger.warn { "Unexpected loop exit: expected loopId ${currentLoopTracePoint.loopId}, but was $loopId" }
            }
        }
        if (currentLoopTracePoint?.loopId == loopId) {
            exitCurrentLoop(thread, threadData)
        }
    }

    override fun onThrow(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        exception: Throwable
    ) {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val tracePoint = TRThrowTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            exception = TRObject(context, exception)
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    override fun onCatch(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        exception: Throwable
    ) {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        val tracePoint = TRCatchTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            exception = TRObject(context, exception)
        )
        strategy.tracePointCreated(threadData.currentTopTracePoint(), tracePoint)
    }

    /**
     * Method takes the currently open loop trace point in [thread] and finishes
     * its last open iteration (if it exists) and then the loop itself.
     *
     * Note: assumes there is an open loop trace point in the provided thread.
     */
    private fun exitCurrentLoop(thread: Thread, threadData: ThreadData) {
        val currentLoopTracePoint = threadData.currentLoopTracePoint() ?: error("No loop trace point exists")
        val currentLoopIterationTracePoint = threadData.currentLoopIterationTracePoint()

        // complete the last loop iteration if it exists and then loop itself
        if (currentLoopIterationTracePoint != null) {
            strategy.completeContainerTracePoint(thread, currentLoopIterationTracePoint)
        }
        strategy.completeContainerTracePoint(thread, currentLoopTracePoint)
        threadData.exitLoop()
    }

    override fun getThreadLocalRandom(): InjectedRandom  = runInsideIgnoredSection {
        val msg = "Trace Recorder mode doesn't support Random calls determinism"
        Logger.error { msg }
        error(msg)
    }

    override fun randomNextInt(): Int = runInsideIgnoredSection {
        val msg = "Trace Recorder mode doesn't support Random calls determinism"
        Logger.error { msg }
        error(msg)
    }

    override fun shouldInvokeBeforeEvent(): Boolean = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support IDEA Plugin integration" }
        return false
    }

    override fun beforeEvent(eventId: Int, type: String) = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support IDEA Plugin integration" }
    }

    override fun getCurrentEventId(): Int = runInsideIgnoredSection {
        Logger. error { "Trace Recorder mode doesn't support IDEA Plugin integration" }
        return -1
    }

    internal fun registerCurrentThread(className: String, methodName: String, codeLocationId: Int) {
        val thread = Thread.currentThread()
        val threadData = ThreadData(nextThreadId.getAndIncrement())
        ThreadDescriptor.getCurrentThreadDescriptor().eventTrackerData = threadData
        threads[thread] = threadData
        strategy.registerCurrentThread(threadData.threadId)

        if (isInLiveDebuggerMode) return // TODO: refactor this check!

        val tracePoint = TRMethodCallTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocationId,
            methodId = context.getOrCreateMethodId(className, methodName, Types.MethodType(Types.VOID_TYPE)),
            obj = null,
            parameters = emptyList()
        )
        strategy.tracePointCreated(null, tracePoint)

        threadData.setRootCall(tracePoint)
        threadData.pushStackFrame(tracePoint, null, isInline = false)
    }

    private fun pushMethodCall(
        thread: Thread,
        threadData: ThreadData,
        obj: TRObject?,
        className: String,
        methodName: String,
        methodType: Types.MethodType,
        codeLocationId: Int,
        params: List<TRObject> = emptyList()
    ) {
        val parentTracePoint = threadData.currentMethodCallTracePoint()
        val methodCall = TRMethodCallTracePoint(
            context = context,
            threadId = threadData.threadId,
            codeLocationId = codeLocationId,
            methodId = context.getOrCreateMethodId(className, methodName, methodType),
            obj = obj,
            parameters = params,
            flags = INCOMPLETE_METHOD_FLAG.toShort(),
            parentTracePoint = parentTracePoint
        )
        strategy.tracePointCreated(parentTracePoint, methodCall)
        if (threadData.getStack().isEmpty()) {
            threadData.setRootCall(methodCall)
        }
        threadData.pushStackFrame(methodCall, thread, isInline = false)
    }

    private fun pushInvokedMethodCalls(
        thread: Thread,
        threadData: ThreadData,
    ) {
        for (frame in thread.stackTrace.reversed()) {
            if (frame.className == "sun.nio.ch.lincheck.Injections") break
            if (frame.isLincheckInternals ||
                frame.isNativeMethod ||
                analysisProfile.shouldBeHidden(frame.className, frame.methodName)
            ) continue

            pushMethodCall(thread, threadData,
                obj = null,
                className = frame.className,
                methodName = frame.methodName,
                methodType = UNKNOWN_METHOD_TYPE,
                codeLocationId = UNKNOWN_CODE_LOCATION_ID,
            )
        }
    }

    /**
     * Completes all method calls of the current thread via [onMethodCallCompletion] and empties the stack.
     *
     * @param loopsCompletionExpected when set to `false` will report an error if there were any loop tracepoints completed along the process.
     */
    private fun completeInvokedMethodCalls(
        thread: Thread,
        threadData: ThreadData,
        loopsCompletionExpected: Boolean = true,
        onMethodCallCompletion: (stackLevel: Int, tracePoint: TRMethodCallTracePoint) -> Unit
    ) {
        // End all method calls, for which we did not track the method return
        while (threadData.getStack().isNotEmpty()) {
            val hasLoops = threadData.currentLoopTracePoint() != null
            while (threadData.currentLoopTracePoint() != null) {
                exitCurrentLoop(thread, threadData)
            }

            val tracePoint = threadData.popStackFrame()
            if (hasLoops && !loopsCompletionExpected) {
                Logger.error { "Forced exit from method ${tracePoint.className}.${tracePoint.methodName} breaks loops." }
            }

            onMethodCallCompletion(threadData.getStack().size, tracePoint)
            strategy.completeContainerTracePoint(thread, tracePoint)
        }
        strategy.completeThread(thread)
    }

    private fun completeRunningThread(thread: Thread, threadDescriptor: ThreadDescriptor) {
        require(!threadDescriptor.isAnalysisEnabled) { "When completing a Thread ${thread.name} (${thread.id}), its analysis is expected to be disabled" }
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        // wait until that thread finishes whatever injected code it is executing right now
        spinner.spinWaitUntil { !threadDescriptor.isInsideInjectedCode }
        // now, we are sure that another thread has finished its injected code
        // and will not attempt to execute anything else, because we disabled analysis in it

        // Skip this thread completely if it is finished "naturally" (i.e. by concurrent afterThreadRun[Return|Exit]).
        // Early exit because we must skip `strategy.completeThread(thread)` for this thread too.
        if (threadData.getStack().isEmpty()) return

        completeInvokedMethodCalls(thread, threadData) { _, tp -> tp.result = TR_OBJECT_UNFINISHED_METHOD_RESULT }
    }

    private fun completeCurrentThread(thread: Thread, threadDescriptor: ThreadDescriptor) {
        val threadData = threadDescriptor.eventTrackerData as? ThreadData?
        if (threadData == null) {
            Logger.error { "Main tracing thread ${thread.name} doesn't have event tracker data" }
            return
        }

        if (threadData.getStack().isEmpty()) {
            Logger.error { "Main tracing thread \"${thread.name}\" stack underflow" }
            strategy.completeThread(thread)
            return
        }

        val overflowStack = mutableListOf<String>()
        completeInvokedMethodCalls(thread, threadData, loopsCompletionExpected = false) { stackLevel, tp ->
            if (stackLevel != 0) {
                overflowStack.add("${tp.className}.${tp.methodName}")
            }
            tp.result = if (stackLevel == 0) TR_OBJECT_VOID else TR_OBJECT_UNFINISHED_METHOD_RESULT
        }

        // Report error if stack was too deep
        if (overflowStack.isNotEmpty()) {
            Logger.error { "Main tracing thread \"${thread.name}\" stack overflow:\n" + overflowStack.joinToString("\n") }
        }
    }

    fun finishTracing() {
        // Finish existing threads, except for Main
        val currentThread = Thread.currentThread()

        threads
            .mapNotNull { (thread, _) ->
                if (thread == currentThread) null
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

        val currentThreadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor()
        if (currentThreadDescriptor != null) {
            // Close this thread call stack (it must be 1 element, complain about problems otherwise)
            completeCurrentThread(currentThread, currentThreadDescriptor)
        }

        strategy.traceEnded()
    }

    /**
     * Returns a list of all thread root trace points.
     */
    fun getThreadRoots(): List<TRTracePoint> {
        val roots = mutableListOf<TRTracePoint>()
        threads.values.sortedBy { it.threadId }.forEach { threadData ->
            val rootCall = threadData.rootCall
            if (rootCall == null) {
                val threadName = context.getThreadName(threadData.threadId)
                Logger.error { "Trace Recorder: Thread #${threadData.threadId + 1} ($threadName): No root call found" }
            } else {
                roots.add(rootCall)
            }
        }
        return roots
    }

    private fun methodAnalysisSectionType(
        owner: Any?,
        className: String,
        methodName: String
    ): AnalysisSectionType {
        val ownerName = owner?.javaClass?.canonicalName ?: className
        return analysisProfile.getAnalysisSectionFor(ownerName, methodName)
    }
}
