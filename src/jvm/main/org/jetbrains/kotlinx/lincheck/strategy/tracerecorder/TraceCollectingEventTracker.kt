/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.tracerecorder

import org.jetbrains.kotlinx.lincheck.strategy.managed.ShadowStackFrame
import org.jetbrains.kotlinx.lincheck.tracedata.*
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.util.*
import sun.nio.ch.lincheck.*
import java.io.File
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
    BINARY, TEXT, VERBOSE
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

    // We don't use [ThreadDescriptor.eventTrackerData] because we need to list all descriptors in the end
    private val threads = ConcurrentHashMap<Thread, ThreadData>()

    override fun beforeThreadFork(thread: Thread, descriptor: ThreadDescriptor) = runInsideIgnoredSection {
        threads[Thread.currentThread()] ?: return
        // Create new thread handle
        val forkedThreadHandle = ThreadData(threads.size)
        threads[thread] = forkedThreadHandle
        // We are ready to use this
    }

    override fun beforeThreadStart() = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return
        val tracePoint = TRMethodCallTracePoint(
            threadId = threadHandle.threadId,
            codeLocationId = -1,
            methodId = methodCache.getOrCreateId(MethodDescriptor("Thread", "run", "()V")),
            obj = TRObject(Thread.currentThread()),
            parameters = emptyList()
        )
        tracePoint.result = TR_OBJECT_VOID
        threadHandle.pushStackFrame(tracePoint, Thread.currentThread())
        enableAnalysis()
    }

    override fun afterThreadFinish() {
        disableAnalysis()
    }

    override fun threadJoin(thread: Thread?, withTimeout: Boolean) = Unit

    override fun onThreadRunException(exception: Throwable) = runInsideIgnoredSection {
        disableAnalysis()
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
        bootstrapMethodHandle: Injections.HandlePojo,
        bootstrapMethodArguments: Array<out Any?>
    ): CallSite? = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support invoke dynamic instrumentation")
        return null
    }

    override fun cacheInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Injections.HandlePojo,
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
        val fieldDescriptor = fieldCache[fieldId]
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
        val threadHandle = threads[Thread.currentThread()] ?: return
        val tracePoint = TRReadTracePoint(
            threadId = threadHandle.threadId,
            codeLocationId = codeLocation,
            fieldId = fieldId,
            obj = TRObjectOrNull(obj),
            value = TRObjectOrNull(value)
        )
        threadHandle.currentMethodCallTracePoint().events.add(tracePoint)
    }

    override fun afterReadArrayElement(array: Any, index: Int, codeLocation: Int, value: Any?) {
        val threadHandle = threads[Thread.currentThread()] ?: return

        val tracePoint = TRReadArrayTracePoint(
            threadId = threadHandle.threadId,
            codeLocationId = codeLocation,
            array = TRObject(array),
            index = index,
            value = null // todo
        )
        threadHandle.currentMethodCallTracePoint().events.add(tracePoint)
    }

    override fun beforeWriteField(
        obj: Any?,
        value: Any?,
        codeLocation: Int,
        fieldId: Int
    ): Boolean = runInsideIgnoredSection {
        val fieldDescriptor = fieldCache[fieldId]
        if (!fieldDescriptor.isStatic && obj == null) {
            // Ignore, NullPointerException will be thrown
            return false
        }

        val threadHandle = threads[Thread.currentThread()] ?: return false
        val tracePoint = TRWriteTracePoint(
            threadId = threadHandle.threadId,
            codeLocationId = codeLocation,
            fieldId = fieldId,
            obj = TRObjectOrNull(obj),
            value = TRObjectOrNull(value)
        )
        threadHandle.currentMethodCallTracePoint().events.add(tracePoint)
        return false
    }

    override fun beforeWriteArrayElement(
        array: Any,
        index: Int,
        value: Any?,
        codeLocation: Int
    ): Boolean = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return false
        val tracePoint = TRWriteArrayTracePoint(
            threadId = threadHandle.threadId,
            codeLocationId = codeLocation,
            array = TRObject(array),
            index = index,
            value = TRObjectOrNull(value)
        )
        threadHandle.currentMethodCallTracePoint().events.add(tracePoint)
        return false
    }

    override fun afterWrite() = Unit

    override fun afterLocalRead(codeLocation: Int, variableId: Int, value: Any?) = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return

        val tracePoint = TRReadLocalVariableTracePoint(
            threadId = threadHandle.threadId,
            codeLocationId = codeLocation,
            localVariableId = variableId,
            value = TRObjectOrNull(value)
        )
        threadHandle.currentMethodCallTracePoint().events.add(tracePoint)

        val variableDescriptor = variableCache[variableId]
        threadHandle.afterLocalRead(variableDescriptor.name, value)
    }

    override fun afterLocalWrite(codeLocation: Int, variableId: Int, value: Any?) = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return

        val tracePoint = TRWriteLocalVariableTracePoint(
            threadId = threadHandle.threadId,
            codeLocationId = codeLocation,
            localVariableId = variableId,
            value = TRObjectOrNull(value)
        )
        threadHandle.currentMethodCallTracePoint().events.add(tracePoint)

        val variableDescriptor = variableCache[variableId]
        threadHandle.afterLocalWrite(variableDescriptor.name, value)
    }

    override fun onMethodCall(
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>
    ): Any? = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return null
        val methodDescriptor = methodCache[methodId]

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        if (receiver == null && methodSection < AnalysisSectionType.ATOMIC) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(methodDescriptor.className)
        }

        val tracePoint = TRMethodCallTracePoint(
            threadId = threadHandle.threadId,
            codeLocationId = codeLocation,
            methodId = methodId,
            obj = TRObjectOrNull(receiver),
            parameters = params.map { TRObjectOrNull(it) }
        )
        threadHandle.currentMethodCallTracePoint().events.add(tracePoint)
        threadHandle.pushStackFrame(tracePoint, receiver)

        // if the method has certain guarantees, enter the corresponding section
        threadHandle.enterAnalysisSection(methodSection)
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
        val threadHandle = threads[Thread.currentThread()] ?: return result
        val methodDescriptor = methodCache[methodId]

        val tracePoint = threadHandle.popStackFrame()
        tracePoint.result = TRObjectOrVoid(result)

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        threadHandle.leaveAnalysisSection(methodSection)
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
        val threadHandle = threads[Thread.currentThread()] ?: return t
        val methodDescriptor = methodCache[methodId]

        val tracePoint = threadHandle.popStackFrame()
        tracePoint.exceptionClassName = t.javaClass.name

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        threadHandle.leaveAnalysisSection(methodSection)
        return t
    }

    override fun onInlineMethodCall(
        methodId: Int,
        codeLocation: Int,
        owner: Any?,
    ): Unit = Unit
// runInsideIgnoredSection {
//        val threadHandle = threads[Thread.currentThread()] ?: return
//        val methodDescriptor = methodCache[methodId]
//        val tracePoint = MethodCallTracePoint(
//            iThread = threadHandle.threadId,
//            actorId = 0,
//            className = methodDescriptor.className,
//            methodName = methodDescriptor.methodName,
//            callStackTrace = EMPTY_CALL_STACK_TRACE,
//            codeLocation = codeLocation,
//            isStatic = false,
//            callType = MethodCallTracePoint.CallType.NORMAL,
//            isSuspend = false
//        )
//        threadHandle.addTracepointToCurrentCall(tracePoint)
//        threadHandle.pushTracepointStackFrame(tracePoint, owner)
//    }

    override fun onInlineMethodCallReturn(methodId: Int): Unit = Unit
// runInsideIgnoredSection {
//        val threadHandle = threads[Thread.currentThread()] ?: return
//        val tracePoint = threadHandle.popTracepointStackFrame()
//        // TODO: add returned value
//        // tracePoint.returnedValue
//    }

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
        val threadHandle = ThreadData(threads.size)
        // Shadow stack cannot be empty
        threadHandle.shadowStack.add(ShadowStackFrame(Thread.currentThread()))
        threads[Thread.currentThread()] = threadHandle

        val tracePoint = TRMethodCallTracePoint(
            threadId = threadHandle.threadId,
            codeLocationId = -1,
            methodId = methodCache.getOrCreateId(MethodDescriptor(className, methodName, "()V")),
            obj = null,
            parameters = emptyList()
        )
        tracePoint.result = TR_OBJECT_VOID
        threadHandle.pushStackFrame(tracePoint, null)

        startTime = System.currentTimeMillis()
    }

    fun finishAndDumpTrace() {
        val allThreads = mutableListOf<ThreadData>()
        allThreads.addAll(threads.values)
        threads.clear()

        if (traceDumpPath == null) {
            return
        }

        // System.err.println("Trace collected in ${System.currentTimeMillis() - startTime} ms")
        startTime = System.currentTimeMillis()

        val output = try {
            val f = File(traceDumpPath)
            f.parentFile?.mkdirs()
            f.createNewFile()
            f.outputStream()
        } catch (t: Throwable) {
            System.err.println("TraceRecorder: Cannot create output file $traceDumpPath: ${t.message}")
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
                    }
                    roots.add(st.first())
                }
            }
            when (outputType) {
                TraceCollectorOutputType.BINARY -> saveRecorderTrace(output, roots)
                TraceCollectorOutputType.TEXT -> printRecorderTrace(output, roots, false)
                TraceCollectorOutputType.VERBOSE -> printRecorderTrace(output, roots, true)
            }
        } catch (t: Throwable) {
            System.err.println("TraceRecorder: Cannot write output file $traceDumpPath: ${t.message}")
            return
        } finally {
            output.close()
            // System.err.println("Trace dumped in ${System.currentTimeMillis() - startTime} ms")
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
