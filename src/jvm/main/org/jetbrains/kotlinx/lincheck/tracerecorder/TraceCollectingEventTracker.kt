/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.tracerecorder

import org.jetbrains.kotlinx.lincheck.strategy.managed.ShadowStackFrame
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.trace.*
import sun.nio.ch.lincheck.*
import java.lang.invoke.CallSite
import java.util.concurrent.ConcurrentHashMap

private class ThreadData(
    val threadId: Int
) {
    val callStack: MutableList<TRMethodCallTracePoint> = arrayListOf()
    val shadowStack: MutableList<ShadowStackFrame> = arrayListOf()
    private val analysisSectionStack: MutableList<AnalysisSectionType> = arrayListOf()

    fun currentMethodCallTracePoint(): TRMethodCallTracePoint = callStack.last()

    fun pushStackFrame(tracePoint: TRMethodCallTracePoint, instance: Any?) {
        val stackFrame = ShadowStackFrame(instance)
        callStack.add(tracePoint)
        shadowStack.add(stackFrame)
    }

    fun popStackFrame(): TRMethodCallTracePoint {
        shadowStack.removeLast()
        return callStack.removeLast()
    }

    fun afterLocalRead(variableName: String, value: Any?) {
        val shadowStackFrame = shadowStack.last()
        shadowStackFrame.setLocalVariable(variableName, value)
    }

    fun afterLocalWrite(variableName: String, value: Any?) {
        val shadowStackFrame = shadowStack.last()
        shadowStackFrame.setLocalVariable(variableName, value)
    }

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

enum class TraceCollectorOutputType {
    BINARY, `BINARY-MEM`, TEXT, VERBOSE
}

fun String?.toTraceCollectorOutputType(): TraceCollectorOutputType {
    if (this == null) return TraceCollectorOutputType.BINARY
    for (v in TraceCollectorOutputType.entries) {
        if (this.equals(v.name, ignoreCase = true)) return v
    }
    return TraceCollectorOutputType.BINARY
}

class TraceCollectingEventTracker(
    private val className: String,
    private val methodName: String,
    private val traceDumpPath: String?,
    private val outputType: TraceCollectorOutputType
) :  EventTracker {
    // We don't want to re-create this object each time we need it
    private val analysisProfile: AnalysisProfile = AnalysisProfile(false)

    // [ThreadDescriptor.eventTrackerData] is weak ref, so store it here too, but use
    // only at the end
    private val threads = ConcurrentHashMap<Thread, ThreadData>()

    private val strategy: TraceCollectingStrategy

    init {
        when (outputType) {
            TraceCollectorOutputType.BINARY -> {
                check(traceDumpPath != null) { "Stream output type needs non-empty output file name" }
                strategy = FileStreamingTraceCollecting(traceDumpPath, TRACE_CONTEXT)
            }
            TraceCollectorOutputType.`BINARY-MEM` -> {
                check(traceDumpPath != null) { "Binary output type needs non-empty output file name" }
                strategy = MemoryTraceCollecting()
            }
            else -> {
                strategy = MemoryTraceCollecting()
            }
        }
    }

    override fun beforeThreadFork(thread: Thread, descriptor: ThreadDescriptor) = runInsideIgnoredSection {
        ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        // Create new thread handle
        val forkedThreadData = ThreadData(threads.size)
        ThreadDescriptor.getThreadDescriptor(thread).eventTrackerData = forkedThreadData
        threads[thread] = forkedThreadData
        // We are ready to use this
    }

    override fun beforeThreadStart() = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return

        strategy.registerCurrentThread(threadData.threadId)

        val tracePoint = TRMethodCallTracePoint(
            threadId = threadData.threadId,
            codeLocationId = -1,
            methodId = TRACE_CONTEXT.getOrCreateMethodId("Thread", "run", "()V"),
            obj = TRObject(Thread.currentThread()),
            parameters = emptyList()
        )
        strategy.tracePointCreated(null, tracePoint)
        threadData.pushStackFrame(tracePoint, Thread.currentThread())
        threadDescriptor.enableAnalysis()
    }

    override fun afterThreadFinish() {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: return
        // Don't pop, we need it
        val tracePoint = threadData.callStack.first()
        tracePoint.result = TR_OBJECT_VOID
        strategy.callEnded(tracePoint)
        strategy.finishCurrentThread()
        threadDescriptor.disableAnalysis()
    }

    override fun threadJoin(thread: Thread?, withTimeout: Boolean) = Unit

    override fun onThreadRunException(exception: Throwable) = runInsideIgnoredSection {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: throw exception
        val threadData = threadDescriptor.eventTrackerData as? ThreadData? ?: throw exception
        // Don't pop, we need it
        val tracePoint = threadData.callStack.first()
        tracePoint.exceptionClassName = exception::class.java.name
        strategy.callEnded(tracePoint)
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

    override fun advanceCurrentTraceDebuggerEventTrackerId(tracker: TraceDebuggerTracker, oldId: Long) = runInsideIgnoredSection {
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
    ): Boolean  = runInsideIgnoredSection {
        val fieldDescriptor = TRACE_CONTEXT.getFieldDescriptor(fieldId)
        if (fieldDescriptor.isStatic) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
        }
        return false
    }

    override fun beforeReadArrayElement(
        array: Any,
        index: Int,
        codeLocation: Int
    ): Boolean = false

    override fun afterReadField(obj: Any?, codeLocation: Int, fieldId: Int, value: Any?) {
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return
        val tracePoint = TRReadTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            fieldId = fieldId,
            obj = TRObjectOrNull(obj),
            value = TRObjectOrNull(value)
        )
        strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
    }

    override fun afterReadArrayElement(array: Any, index: Int, codeLocation: Int, value: Any?) {
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRReadArrayTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            array = TRObject(array),
            index = index,
            value = null // todo
        )
        strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
    }

    override fun beforeWriteField(
        obj: Any?,
        value: Any?,
        codeLocation: Int,
        fieldId: Int
    ): Boolean = runInsideIgnoredSection {
        val fieldDescriptor = TRACE_CONTEXT.getFieldDescriptor(fieldId)
        if (!fieldDescriptor.isStatic && obj == null) {
            // Ignore, NullPointerException will be thrown
            return false
        }

        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return false
        val tracePoint = TRWriteTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            fieldId = fieldId,
            obj = TRObjectOrNull(obj),
            value = TRObjectOrNull(value)
        )
        strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
        return false
    }

    override fun beforeWriteArrayElement(
        array: Any,
        index: Int,
        value: Any?,
        codeLocation: Int
    ): Boolean = runInsideIgnoredSection {
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return false
        val tracePoint = TRWriteArrayTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            array = TRObject(array),
            index = index,
            value = TRObjectOrNull(value)
        )
        strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
        return false
    }

    override fun afterWrite() = Unit

    override fun afterLocalRead(codeLocation: Int, variableId: Int, value: Any?) = runInsideIgnoredSection {
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRReadLocalVariableTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            localVariableId = variableId,
            value = TRObjectOrNull(value)
        )
        strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)

        val variableDescriptor = TRACE_CONTEXT.getVariableDescriptor(variableId)
        threadData.afterLocalRead(variableDescriptor.name, value)
    }

    override fun afterLocalWrite(codeLocation: Int, variableId: Int, value: Any?) = runInsideIgnoredSection {
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return

        val tracePoint = TRWriteLocalVariableTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            localVariableId = variableId,
            value = TRObjectOrNull(value)
        )
        strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)

        val variableDescriptor = TRACE_CONTEXT.getVariableDescriptor(variableId)
        threadData.afterLocalWrite(variableDescriptor.name, value)
    }

    override fun onMethodCall(
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>
    ): Any? = runInsideIgnoredSection {
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return null
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        if (receiver == null && methodSection < AnalysisSectionType.ATOMIC) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(methodDescriptor.className)
        }

        val tracePoint = TRMethodCallTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            methodId = methodId,
            obj = TRObjectOrNull(receiver),
            parameters = params.map { TRObjectOrNull(it) }
        )
        strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
        threadData.pushStackFrame(tracePoint, receiver)

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
    ): Any? = runInsideIgnoredSection {
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return result
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)

        val tracePoint = threadData.popStackFrame()
        tracePoint.result = TRObjectOrVoid(result)
        strategy.callEnded(tracePoint)

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
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
    ): Throwable = runInsideIgnoredSection {
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return t
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)

        val tracePoint = threadData.popStackFrame()
        tracePoint.exceptionClassName = t.javaClass.name
        strategy.callEnded(tracePoint)

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        threadData.leaveAnalysisSection(methodSection)
        return t
    }

    override fun onInlineMethodCall(
        methodId: Int,
        codeLocation: Int,
        owner: Any?,
    ): Unit = runInsideIgnoredSection {
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return
        val tracePoint = TRMethodCallTracePoint(
            threadId = threadData.threadId,
            codeLocationId = codeLocation,
            methodId = methodId,
            obj = TRObjectOrNull(owner),
            parameters = emptyList()
        )
        strategy.tracePointCreated(threadData.currentMethodCallTracePoint(), tracePoint)
        threadData.pushStackFrame(tracePoint, owner)
    }

    override fun onInlineMethodCallReturn(methodId: Int): Unit = runInsideIgnoredSection {
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return
        val tracePoint = threadData.popStackFrame()
        tracePoint.result = TR_OBJECT_VOID
        strategy.callEnded(tracePoint)
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

    override fun getEventId(): Int = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support IDEA Plugin integration")
        return -1
    }

    override fun setLastMethodCallEventId() = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support IDEA Plugin integration")
    }

    private var startTime = System.currentTimeMillis()

    fun enableTrace() {
        // Start tracing in this thread
        val threadData = ThreadData(threads.size)
        // Shadow stack cannot be empty
        threadData.shadowStack.add(ShadowStackFrame(Thread.currentThread()))
        ThreadDescriptor.getCurrentThreadDescriptor().eventTrackerData = threadData
        threads[Thread.currentThread()] = threadData

        val tracePoint = TRMethodCallTracePoint(
            threadId = threadData.threadId,
            codeLocationId = -1,
            methodId = TRACE_CONTEXT.getOrCreateMethodId(className, methodName, "()V"),
            obj = null,
            parameters = emptyList()
        )
        strategy.registerCurrentThread(threadData.threadId)
        strategy.tracePointCreated(null,tracePoint)
        threadData.pushStackFrame(tracePoint, null)

        startTime = System.currentTimeMillis()
    }

    fun finishAndDumpTrace() {
        // Close this thread callstack
        val threadData = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTrackerData as? ThreadData? ?: return
        val tracePoint = threadData.currentMethodCallTracePoint()
        tracePoint.result = TR_OBJECT_VOID
        strategy.callEnded(tracePoint)


        val allThreads = mutableListOf<ThreadData>()
        allThreads.addAll(threads.values)
        threads.clear()

        strategy.traceEnded()

        System.err.println("Trace collected in ${System.currentTimeMillis() - startTime} ms")
        startTime = System.currentTimeMillis()

        if (outputType == TraceCollectorOutputType.BINARY) {
            return
        }

        try {
            allThreads.sortBy { it.threadId }
            val roots = mutableListOf<TRTracePoint>()
            allThreads.forEach { thread ->
                val st = thread.callStack
                if (st.isEmpty()) {
                    System.err.println("Trace Recorder: Thread ${thread.threadId + 1}: Stack underflow, report bug")
                } else {
                    if (st.size > 1) {
                        System.err.println("Trace Recorder: Thread ${thread.threadId + 1}: Stack is not empty, contains ${st.size} elements, report bug")
                        System.err.println("Stack leftover:")
                        st.reversed().forEach {
                            System.err.println("  ${it.toText(true)}")
                        }
                    }
                    roots.add(st.first())
                }
            }
            when (outputType) {
                TraceCollectorOutputType.`BINARY-MEM` -> saveRecorderTrace(traceDumpPath!!, TRACE_CONTEXT, roots)
                TraceCollectorOutputType.TEXT -> printRecorderTrace(traceDumpPath, TRACE_CONTEXT, roots, false)
                TraceCollectorOutputType.VERBOSE -> printRecorderTrace(traceDumpPath, TRACE_CONTEXT, roots, true)
                TraceCollectorOutputType.BINARY -> Unit // Do nothing, everything is written
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
