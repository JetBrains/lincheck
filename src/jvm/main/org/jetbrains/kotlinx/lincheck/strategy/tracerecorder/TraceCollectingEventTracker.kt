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

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.flattenedTraceGraphToCSV
import org.jetbrains.kotlinx.lincheck.primitiveOrIdentityHashCode
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicFieldUpdaterNames.getAtomicFieldUpdaterDescriptor
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectLabelFactory.adornedStringRepresentation
import org.jetbrains.kotlinx.lincheck.strategy.managed.UnsafeName.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.VarHandleMethodType.*
import org.jetbrains.kotlinx.lincheck.trace.*
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.transformation.MethodIds
import org.jetbrains.kotlinx.lincheck.transformation.toSimpleClassName
import org.jetbrains.kotlinx.lincheck.util.*
import org.objectweb.asm.commons.Method.getMethod
import sun.nio.ch.lincheck.*
import java.io.File
import java.io.PrintStream
import java.lang.invoke.CallSite
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * [TraceCollectingEventTracker] can trace all threads forked by method configured for tracing.
 * This constant turns off (if set to `true`) this feature.
 * It is turned off now, but can be turned on later.
 */
private const val TRACE_ONLY_ONE_THREAD = true

class TraceCollectingEventTracker(
    private val className: String,
    private val methodName: String,
    private val methodDesc: String,
    private val traceDumpPath: String?
) :  EventTracker {
    /**
     * This class contains all data needed to track events for one thread.
     * It is not concurrent-safe, and each instance must be used strictly from one thread only.
     * All this data replicate multiple arrays of [ManagedStrategy] indexed by thread id,
     * but this class is intended to be stored in concurrent hash map keyed by [Thread].
     *
     * TODO: Unify this storage with [ManagedStrategy] and unify all code to track
     *       method calls.
     */
    private class ThreadData(
        /**
         * ID of thread to use in trace, simply an increasing number.
         */
        val id: Int,
        /**
         * Collector to store all [TracePoint]s generated for this thread.
         */
        val collector: TraceCollector = TraceCollector(),
        /**
         * Current "tracked" stacktrace.
         */
        val stackTrace: MutableList<CallStackTraceElement> = arrayListOf(),
        /**
         * Additional stack information to resolve owner information for fields
         * TODO: Unify with stackTrace
         */
        val shadowStack: MutableList<ShadowStackFrame> = arrayListOf(),
        /**
         * Tracks content of constants (i.e., static final fields).
         * Stores a map `object -> fieldName`,
         * mapping an object to a constant name referencing this object.
         */
        val constants: IdentityHashMap<Any, String> = IdentityHashMap<Any, String>(),
        /**
         * Stack of different analysis sections to filter-out unneeded events
         */
        val analysisSectionStack: MutableList<AnalysisSectionType> = arrayListOf(),
        /**
         * Last read constant name (i.e., static final field).
         * We store it as we initialize read value after the trace point is created,
         * so we have to store the trace point somewhere to get it later.
         */
        var lastReadConstantName: String? = null,
        /**
         * Last read trace point, occurred in the current thread.
         * We store it as we initialize read value after the point is created,
         * so we have to store the trace point somewhere to get it later.
         */
        var lastReadTracePoint: ReadTracePoint? = null
    )

    // We don't want to re-create this object each time we need it
    private val analysisProfile: AnalysisProfile = AnalysisProfile(false)

    // We don't use [ThreadDescriptor.eventTrackerData] because we need to list all descriptors in the end
    private val threads = ConcurrentHashMap<Thread, ThreadData>()
    // ID generator for [TracePoint]s
    private val currentTracePointId = atomic(0)
    // ID generator for [StackTraceElement]s
    private val currentCallId = atomic(0)

    override fun beforeThreadFork(thread: Thread, descriptor: ThreadDescriptor) = runInsideIgnoredSection {
        // Don't init new threads forked from initial one if it is not enabled
        if (TRACE_ONLY_ONE_THREAD) {
            return
        }

        val td = threads[Thread.currentThread()] ?: return
        val ftd = ThreadData(threads.size)
        threads[thread] = ftd
        addTracePoint(ThreadStartTracePoint(
            iThread = td.id,
            actorId = 0,
            startedThreadDisplayNumber = ftd.id,
            callStackTrace = ftd.stackTrace
        ))
    }

    override fun beforeThreadStart() = runInsideIgnoredSection {
        val td = threads[Thread.currentThread()] ?: return

        val methodDescriptor = getMethod("void run()").descriptor
        addTracePoint(addBeforeMethodCallTracePoint(
            thread = td,
            owner = null,
            className = "java.lang.Thread",
            methodName = "run",
            codeLocation = UNKNOWN_CODE_LOCATION,
            methodId = MethodIds.getMethodId("java.lang.Thread", "run", methodDescriptor),
            methodParams = emptyArray(),
            atomicMethodDescriptor = null,
            callType = MethodCallTracePoint.CallType.THREAD_RUN
        ))
    }

    override fun afterThreadFinish() = Unit

    override fun threadJoin(thread: Thread?, withTimeout: Boolean) = runInsideIgnoredSection {
        val td = threads[Thread.currentThread()] ?: return
        addTracePoint(ThreadJoinTracePoint(
            iThread = td.id,
            actorId = 0,
            joinedThreadDisplayNumber = threads[thread]?.id ?: -1,
            callStackTrace = td.stackTrace,
        ))
    }

    override fun onThreadRunException(exception: Throwable) = runInsideIgnoredSection {
        throw exception
    }

    override fun beforeLock(codeLocation: Int) = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun lock(monitor: Any) = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun unlock(monitor: Any, codeLocation: Int) = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun beforePark(codeLocation: Int) = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun park(codeLocation: Int) = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun unpark(thread: Thread, codeLocation: Int) = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun beforeWait(codeLocation: Int) = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun wait(monitor: Any, withTimeout: Boolean) = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean) = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun beforeNewObjectCreation(className: String) = runInsideIgnoredSection {
        LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
    }

    override fun afterNewObjectCreation(obj: Any) = Unit

    override fun getNextTraceDebuggerEventTrackerId(tracker: TraceDebuggerTracker): Long = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support Trace Debugger-specific instrumentation")
    }

    override fun advanceCurrentTraceDebuggerEventTrackerId(tracker: TraceDebuggerTracker, oldId: Long) = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support Trace Debugger-specific instrumentation")
    }

    override fun getCachedInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Injections.HandlePojo,
        bootstrapMethodArguments: Array<out Any?>
    ): CallSite? = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support invoke dynamic instrumentation")
    }

    override fun cacheInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Injections.HandlePojo,
        bootstrapMethodArguments: Array<out Any?>,
        callSite: CallSite
    ) = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support invoke dynamic instrumentation")
    }

    override fun updateSnapshotBeforeConstructorCall(objs: Array<out Any?>) = Unit

    override fun beforeReadField(
        obj: Any?,
        className: String,
        fieldName: String,
        codeLocation: Int,
        isStatic: Boolean,
        isFinal: Boolean
    ): Boolean  = runInsideIgnoredSection {
        if (isStatic) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
        }

        val td = threads[Thread.currentThread()] ?: return false
        if (isStatic && isFinal) {
            td.lastReadConstantName = fieldName
        }

        if (isFinal || isStackRecoveryFieldAccess(obj, fieldName)) {
            return false
        }

        val point = ReadTracePoint(
            ownerRepresentation = getFieldOwnerName(obj, className, fieldName, isStatic),
            iThread = td.id,
            actorId = 0,
            callStackTrace = td.stackTrace,
            fieldName = fieldName,
            codeLocation = codeLocation,
            isLocal = false,
        )
        td.lastReadTracePoint = point
        addTracePoint(point)
        return true
    }

    override fun beforeReadArrayElement(
        array: Any,
        index: Int,
        codeLocation: Int
    ): Boolean = runInsideIgnoredSection {
        val td = threads[Thread.currentThread()] ?: return false
        val point = ReadTracePoint(
            ownerRepresentation = null,
            iThread = td.id,
            actorId = 0,
            callStackTrace = td.stackTrace,
            fieldName = "${adornedStringRepresentation(array)}[$index]",
            codeLocation = codeLocation,
            isLocal = false,
        )
        td.lastReadTracePoint = point
        addTracePoint(point)
        return true
    }

    override fun afterRead(value: Any?) = runInsideIgnoredSection {
        val td = threads[Thread.currentThread()] ?: return
        if (td.lastReadConstantName != null && value != null) {
            td.constants[value] = td.lastReadConstantName
        }
        val valueRepresentation = adornedStringRepresentation(value)
        val typeRepresentation = objectFqTypeName(value)
        td.lastReadTracePoint?.initializeReadValue(valueRepresentation, typeRepresentation)
        td.lastReadTracePoint = null
        td.lastReadConstantName = null
    }

    override fun beforeWriteField(
        obj: Any?,
        className: String,
        fieldName: String,
        value: Any?,
        codeLocation: Int,
        isStatic: Boolean,
        isFinal: Boolean
    ): Boolean = runInsideIgnoredSection {
        if (isFinal || isStackRecoveryFieldAccess(obj, fieldName)) {
            return false
        }

        val td = threads[Thread.currentThread()] ?: return false
        val point = WriteTracePoint(
            ownerRepresentation = getFieldOwnerName(obj, className, fieldName, isStatic),
            iThread = td.id,
            actorId = 0,
            callStackTrace = td.stackTrace,
            fieldName = fieldName,
            codeLocation = codeLocation,
            isLocal = false,
        ).also {
            it.initializeWrittenValue(adornedStringRepresentation(value), objectFqTypeName(value))
        }
        addTracePoint(point)
        return true
    }

    override fun beforeWriteArrayElement(
        array: Any,
        index: Int,
        value: Any?,
        codeLocation: Int
    ): Boolean = runInsideIgnoredSection {
        val td = threads[Thread.currentThread()] ?: return false
        val point = WriteTracePoint(
            ownerRepresentation = null,
            iThread = td.id,
            actorId = 0,
            callStackTrace = td.stackTrace,
            fieldName = "${adornedStringRepresentation(array)}[$index]",
            codeLocation = codeLocation,
            isLocal = false,
        ).also {
            it.initializeWrittenValue(adornedStringRepresentation(value), objectFqTypeName(value))
        }
        addTracePoint(point)
        return true
    }

    // TODO: do we need StateRepresentationTracePoint here?
    override fun afterWrite() = Unit

    override fun afterLocalRead(codeLocation: Int, name: String, value: Any?) = runInsideIgnoredSection {
        val td = threads[Thread.currentThread()] ?: return
        td.shadowStack.last().setLocalVariable(name, value)
    }

    override fun afterLocalWrite(codeLocation: Int, name: String, value: Any?) = runInsideIgnoredSection {
        val td = threads[Thread.currentThread()] ?: return
        td.shadowStack.last().setLocalVariable(name, value)
    }

    override fun onMethodCall(
        className: String,
        methodName: String,
        codeLocation: Int,
        methodId: Int,
        methodSignature: MethodSignature?,
        receiver: Any?,
        params: Array<Any?>
    ): Any? = runInsideIgnoredSection {
        val td = threads[Thread.currentThread()] ?: return null

        val methodSection = methodAnalysisSectionType(receiver, className, methodName)

        if (receiver == null && methodSection < AnalysisSectionType.ATOMIC) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
        }

        addBeforeMethodCallTracePoint(
            thread = td,
            owner = receiver,
            codeLocation = codeLocation,
            methodId = methodId,
            className = className,
            methodName = methodName,
            methodParams = params,
            atomicMethodDescriptor = null,
            callType = MethodCallTracePoint.CallType.NORMAL,
        )

        // if the method has certain guarantees, enter the corresponding section
        enterAnalysisSection(td, methodSection)

        return null
    }

    override fun onMethodCallReturn(
        className: String,
        methodName: String,
        descriptorId: Long,
        determenisticDescriptor: Any?,
        methodId: Int,
        receiver: Any?,
        params: Array<out Any?>,
        result: Any?
    ) = runInsideIgnoredSection {
        val td = threads[Thread.currentThread()] ?: return@runInsideIgnoredSection
        if (td.stackTrace.isEmpty()) {
            return
        }
        val tracePoint = td.stackTrace.last().tracePoint
        when (result) {
            Unit -> tracePoint.initializeVoidReturnedValue()
            Injections.VOID_RESULT -> tracePoint.initializeVoidReturnedValue()
            COROUTINE_SUSPENDED -> tracePoint.initializeCoroutineSuspendedResult()
            else -> tracePoint.initializeReturnedValue(adornedStringRepresentation(result), objectFqTypeName(result))
        }
        popShadowStackFrame()
        td.stackTrace.removeLast()
        val methodSection = methodAnalysisSectionType(receiver, className, methodName)
        leaveAnalysisSection(td, methodSection)
    }

    override fun onMethodCallException(
        className: String,
        methodName: String,
        descriptorId: Long,
        destermenisticDescriptor: Any?,
        receiver: Any?,
        params: Array<out Any?>,
        t: Throwable
    ) = runInsideIgnoredSection {
        val td = threads[Thread.currentThread()] ?: return@runInsideIgnoredSection
        if (td.stackTrace.isEmpty()) {
            return
        }
        val tracePoint = td.stackTrace.last().tracePoint
        if (!tracePoint.isActor) {
            tracePoint.initializeThrownException(t)
        }
        popShadowStackFrame()
        td.stackTrace.removeLast()
        val methodSection = methodAnalysisSectionType(receiver, className, methodName)
        leaveAnalysisSection(td, methodSection)
    }

    override fun onInlineMethodCall(
        className: String,
        methodName: String,
        methodId: Int,
        codeLocation: Int,
        owner: Any?
    ) = runInsideIgnoredSection {
        val td = threads[Thread.currentThread()] ?: return@runInsideIgnoredSection
        addBeforeMethodCallTracePoint(
            thread = td,
            owner = owner,
            codeLocation = codeLocation,
            methodId = methodId,
            className = className,
            methodName = methodName,
            methodParams = emptyArray(),
            atomicMethodDescriptor = null,
            callType = MethodCallTracePoint.CallType.NORMAL,
        )
    }

    override fun onInlineMethodCallReturn(className: String, methodId: Int) = runInsideIgnoredSection {
        val td = threads[Thread.currentThread()] ?: return@runInsideIgnoredSection
        if (td.stackTrace.isEmpty()) {
            return
        }
        val tracePoint = td.stackTrace.last().tracePoint
        tracePoint.initializeVoidReturnedValue()
        popShadowStackFrame()
        td.stackTrace.removeLast()
    }

    override fun invokeDeterministicallyOrNull(
        descriptorId: Long,
        descriptor: Any?,
        receiver: Any?,
        params: Array<out Any?>
    ): BootstrapResult<*>? = null

    override fun getThreadLocalRandom(): InjectedRandom  = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support Random calls determinism")
    }

    override fun randomNextInt(): Int = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support Random calls determinism")
    }

    override fun shouldInvokeBeforeEvent(): Boolean = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support IDEA Plugin integration")
    }

    override fun beforeEvent(eventId: Int, type: String) = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support IDEA Plugin integration")
    }
    override fun getEventId(): Int = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support IDEA Plugin integration")
    }

    override fun setLastMethodCallEventId() = runInsideIgnoredSection {
        error("Trace Recorder mode doesn't support IDEA Plugin integration")
    }

    fun enableTrace() {
        // Start tracing in this thread
        val td = ThreadData(threads.size)
        // Shadow stack cannot be empty
        td.shadowStack.add(ShadowStackFrame(Thread.currentThread()))
        threads[Thread.currentThread()] = td

        // Section start not to confuse trace post-processor
        addTracePoint(SectionDelimiterTracePoint(ExecutionPart.PARALLEL))

        // Method in question was called
        addTracePoint(addBeforeMethodCallTracePoint(
            thread = td,
            owner = null,
            className = className,
            methodName = methodName,
            codeLocation = UNKNOWN_CODE_LOCATION,
            methodId = MethodIds.getMethodId(className, methodName, methodDesc),
            methodParams = emptyArray(),
            atomicMethodDescriptor = null,
            callType = MethodCallTracePoint.CallType.ACTOR
        ))
    }

    fun finishAndDumpTrace(humanReadableOutput: Boolean) = runInsideIgnoredSection {
        val tds = ArrayList(threads.values)
        threads.clear()

        val printStream = if (traceDumpPath == null) {
            System.out
        } else  {
            val f = File(traceDumpPath)
            f.parentFile?.mkdirs()
            f.createNewFile()
            PrintStream(f)
        }

        // Merge all traces. Mergesort is possible as optimization
        val totalTraceArray = mutableListOf<TracePoint>()
        tds.forEach { totalTraceArray.addAll(it.collector.trace) }
        totalTraceArray.sortWith { a, b -> a.eventId.compareTo(b.eventId) }

        val totalTrace = Trace(totalTraceArray, listOf("Thread"))

        // Filter & prepare trace to "graph"
        val graph = try {
            traceToCollapsedGraph(totalTrace, analysisProfile, null)
        } catch (t: Throwable) {
            throw t
        }
        val nodeList = graph.flattenNodes(VerboseTraceFlattenPolicy())


        if (humanReadableOutput) {
            val sb = StringBuilder()
            sb.appendTraceTable("Trace started from $methodName", totalTrace, null, nodeList)
            printStream.print(sb.toString())
        } else {
            flattenedTraceGraphToCSV(nodeList).forEach {
                printStream.println(it)
            }
        }

        printStream.close()
    }

    private fun addTracePoint(point: TracePoint) {
        val td = threads[Thread.currentThread()] ?: return
        point.eventId = currentTracePointId.getAndIncrement()
        td.collector.addTracePoint(point)
    }

    /*
     * TODO: deduplicate with [ManagedStrategy] all these methods
     */
    private fun pushShadowStackFrame(owner: Any?) {
        val shadowStack = threads[Thread.currentThread()]?.shadowStack ?: return
        val stackFrame = ShadowStackFrame(owner)
        shadowStack.add(stackFrame)
    }

    private fun popShadowStackFrame() {
        val shadowStack = threads[Thread.currentThread()]?.shadowStack ?: return
        shadowStack.removeLast()
        check(shadowStack.isNotEmpty()) {
            "Shadow stack cannot be empty"
        }
    }

    private fun isStackRecoveryFieldAccess(obj: Any?, fieldName: String?): Boolean =
        obj is Continuation<*> && (fieldName == "label" || fieldName?.startsWith("L$") == true)

    private fun getFieldOwnerName(obj: Any?, className: String, fieldName: String, isStatic: Boolean): String? {
        if (isStatic) {
            val stackTraceElement = threads[Thread.currentThread()]?.shadowStack?.last() ?: return null
            if (stackTraceElement.instance?.javaClass?.name == className) {
                return null
            }
            return className.toSimpleClassName()
        }
        return findOwnerName(obj!!)
    }

    private fun findOwnerName(owner: Any): String? {
        val td = threads[Thread.currentThread()] ?: return null
        val shadowStackFrame = td.shadowStack.last()
        // if the current owner is `this` - no owner needed
        if (owner == shadowStackFrame.instance) return null
        // do not prettify thread names
        if (owner is Thread) {
            return adornedStringRepresentation(owner)
        }
        // lookup for the object in local variables and use the local variable name if found
        shadowStackFrame.getLastAccessVariable(owner)?.let { return it }
        // lookup for a field name in the current stack frame `this`
        shadowStackFrame.instance
            ?.findInstanceFieldReferringTo(owner)
            ?.let { return it.name }
        // lookup for the constant referencing the object
        td.constants[owner]?.let { return it }
        // otherwise return object's string representation
        return adornedStringRepresentation(owner)
    }

    private fun objectFqTypeName(obj: Any?): String {
        val typeName = obj?.javaClass?.name ?: "null"
        // Note: `if` here is important for performance reasons.
        // In common case we want to return just `typeName` without using string templates
        // to avoid redundant string allocation.
        if (obj?.javaClass?.isEnum == true) {
            return "Enum:$typeName"
        }
        return typeName
    }

    private fun addBeforeMethodCallTracePoint(
        thread: ThreadData,
        owner: Any?,
        codeLocation: Int,
        methodId: Int,
        className: String,
        methodName: String,
        methodParams: Array<Any?>,
        atomicMethodDescriptor: AtomicMethodDescriptor?,
        callType: MethodCallTracePoint.CallType,
    ): MethodCallTracePoint {
        val callStackTrace = thread.stackTrace
        val callId = currentCallId.getAndIncrement()
        // The code location of the new method call is currently the last one
        val tracePoint = createBeforeMethodCallTracePoint(
            thread = thread,
            owner = owner,
            className = className,
            methodName = methodName,
            params = methodParams,
            codeLocation = codeLocation,
            atomicMethodDescriptor = atomicMethodDescriptor,
            callType = callType,
        )
        // Method invocation id used to calculate spin cycle start label call depth.
        // Two calls are considered equals if two same methods were called with the same parameters.
        val methodInvocationId = Objects.hash(methodId,
            methodParams.map { primitiveOrIdentityHashCode(it) }.toTypedArray().contentHashCode()
        )
        val stackTraceElement = CallStackTraceElement(
            id = callId,
            tracePoint = tracePoint,
            instance = owner,
            methodInvocationId = methodInvocationId
        )
        callStackTrace.add(stackTraceElement)
        pushShadowStackFrame(owner)
        return tracePoint
    }

    private fun createBeforeMethodCallTracePoint(
        thread: ThreadData,
        owner: Any?,
        className: String,
        methodName: String,
        params: Array<Any?>,
        codeLocation: Int,
        atomicMethodDescriptor: AtomicMethodDescriptor?,
        callType: MethodCallTracePoint.CallType,
    ): MethodCallTracePoint {
        val callStackTrace = thread.stackTrace
        val tracePoint = MethodCallTracePoint(
            iThread = thread.id,
            actorId = 0,
            className = className,
            methodName = methodName,
            callStackTrace = callStackTrace,
            codeLocation = codeLocation,
            isStatic = (owner == null),
            callType = callType,
            isSuspend = isSuspendFunction(className, methodName, params)
        )
        // handle non-atomic methods
        if (atomicMethodDescriptor == null) {
            val ownerName = if (owner != null) findOwnerName(owner) else className.toSimpleClassName()
            if (!ownerName.isNullOrEmpty()) {
                tracePoint.initializeOwnerName(ownerName)
            }
            tracePoint.initializeParameters(params.toList())
            return tracePoint
        }
        // handle atomic methods
        if (isVarHandle(owner)) {
            return initializeVarHandleMethodCallTracePoint(thread, tracePoint, owner, params)
        }
        if (isAtomicFieldUpdater(owner)) {
            return initializeAtomicUpdaterMethodCallTracePoint(tracePoint, owner!!, params)
        }
        if (isAtomic(owner) || isAtomicArray(owner)) {
            return initializeAtomicReferenceMethodCallTracePoint(thread, tracePoint, owner!!, params)
        }
        if (isUnsafe(owner)) {
            return initializeUnsafeMethodCallTracePoint(tracePoint, owner!!, params)
        }
        error("Unknown atomic method $className::$methodName")
    }

    private fun initializeUnsafeMethodCallTracePoint(
        tracePoint: MethodCallTracePoint,
        receiver: Any,
        params: Array<Any?>
    ): MethodCallTracePoint {
        when (val unsafeMethodName = UnsafeNames.getMethodCallType(params)) {
            is UnsafeArrayMethod -> {
                val owner = "${adornedStringRepresentation(unsafeMethodName.array)}[${unsafeMethodName.index}]"
                tracePoint.initializeOwnerName(owner)
                tracePoint.initializeParameters(unsafeMethodName.parametersToPresent)
            }
            is UnsafeName.TreatAsDefaultMethod -> {
                tracePoint.initializeOwnerName(adornedStringRepresentation(receiver))
                tracePoint.initializeParameters(params.toList())
            }
            is UnsafeInstanceMethod -> {
                val ownerName = findOwnerName(unsafeMethodName.owner)
                val owner = ownerName?.let { "$ownerName.${unsafeMethodName.fieldName}" } ?: unsafeMethodName.fieldName
                tracePoint.initializeOwnerName(owner)
                tracePoint.initializeParameters(unsafeMethodName.parametersToPresent)
            }
            is UnsafeStaticMethod -> {
                tracePoint.initializeOwnerName("${unsafeMethodName.clazz.simpleName}.${unsafeMethodName.fieldName}")
                tracePoint.initializeParameters(unsafeMethodName.parametersToPresent)
            }
        }

        return tracePoint
    }

    private fun initializeAtomicReferenceMethodCallTracePoint(
        thread: ThreadData,
        tracePoint: MethodCallTracePoint,
        receiver: Any,
        params: Array<Any?>
    ): MethodCallTracePoint {
        val shadowStackFrame = thread.shadowStack.last()
        val atomicReferenceInfo = AtomicReferenceNames.getMethodCallType(shadowStackFrame, receiver, params)
        when (atomicReferenceInfo) {
            is AtomicReferenceInstanceMethod -> {
                val receiverName = findOwnerName(atomicReferenceInfo.owner)
                tracePoint.initializeOwnerName(receiverName?.let { "$it.${atomicReferenceInfo.fieldName}" } ?: atomicReferenceInfo.fieldName)
                tracePoint.initializeParameters(params.toList())
            }
            is AtomicReferenceStaticMethod -> {
                val clazz = atomicReferenceInfo.ownerClass
                val thisClassName = shadowStackFrame.instance?.javaClass?.name
                val ownerName = if (thisClassName == clazz.name) "" else "${clazz.simpleName}."
                tracePoint.initializeOwnerName("${ownerName}${atomicReferenceInfo.fieldName}")
                tracePoint.initializeParameters(params.toList())
            }
            is AtomicReferenceInLocalVariable -> {
                tracePoint.initializeOwnerName("${atomicReferenceInfo.localVariable}.${atomicReferenceInfo.fieldName}")
                tracePoint.initializeParameters(params.toList())
            }
            is AtomicArrayMethod -> {
                tracePoint.initializeOwnerName("${adornedStringRepresentation(atomicReferenceInfo.atomicArray)}[${atomicReferenceInfo.index}]")
                tracePoint.initializeParameters(params.drop(1))
            }
            is InstanceFieldAtomicArrayMethod -> {
                val receiverName = findOwnerName(atomicReferenceInfo.owner)
                tracePoint.initializeOwnerName((receiverName?.let { "$it." } ?: "") + "${atomicReferenceInfo.fieldName}[${atomicReferenceInfo.index}]")
                tracePoint.initializeParameters(params.drop(1))
            }
            is StaticFieldAtomicArrayMethod -> {
                val clazz = atomicReferenceInfo.ownerClass
                val thisClassName = shadowStackFrame.instance?.javaClass?.name
                val ownerName = if (thisClassName == clazz.name) "" else "${clazz.simpleName}."
                tracePoint.initializeOwnerName("${ownerName}${atomicReferenceInfo.fieldName}[${atomicReferenceInfo.index}]")
                tracePoint.initializeParameters(params.drop(1))
            }
            is AtomicArrayInLocalVariable -> {
                tracePoint.initializeOwnerName("${atomicReferenceInfo.localVariable}.${atomicReferenceInfo.fieldName}[${atomicReferenceInfo.index}]")
                tracePoint.initializeParameters(params.drop(1))
            }
            is AtomicReferenceMethodType.TreatAsDefaultMethod -> {
                tracePoint.initializeOwnerName(adornedStringRepresentation(receiver))
                tracePoint.initializeParameters(params.toList())
            }
        }
        return tracePoint
    }

    private fun initializeVarHandleMethodCallTracePoint(
        thread: ThreadData,
        tracePoint: MethodCallTracePoint,
        varHandle: Any, // for Java 8, the VarHandle class does not exist
        parameters: Array<Any?>,
    ): MethodCallTracePoint {
        val shadowStackFrame = thread.shadowStack.last()
        val varHandleMethodType = VarHandleNames.varHandleMethodType(varHandle, parameters)
        when (varHandleMethodType) {
            is ArrayVarHandleMethod -> {
                tracePoint.initializeOwnerName("${adornedStringRepresentation(varHandleMethodType.array)}[${varHandleMethodType.index}]")
                tracePoint.initializeParameters(varHandleMethodType.parameters)
            }
            is InstanceVarHandleMethod -> {
                val receiverName = findOwnerName(varHandleMethodType.owner)
                tracePoint.initializeOwnerName(receiverName?.let { "$it.${varHandleMethodType.fieldName}" } ?: varHandleMethodType.fieldName)
                tracePoint.initializeParameters(varHandleMethodType.parameters)
            }
            is StaticVarHandleMethod -> {
                val clazz = varHandleMethodType.ownerClass
                val thisClassName = shadowStackFrame.instance?.javaClass?.name
                val ownerName = if (thisClassName == clazz.name) "" else "${clazz.simpleName}."
                tracePoint.initializeOwnerName("${ownerName}${varHandleMethodType.fieldName}")
                tracePoint.initializeParameters(varHandleMethodType.parameters)
            }
            VarHandleMethodType.TreatAsDefaultMethod -> {
                tracePoint.initializeOwnerName(adornedStringRepresentation(varHandle))
                tracePoint.initializeParameters(parameters.toList())
            }
        }

        return tracePoint
    }

    private fun initializeAtomicUpdaterMethodCallTracePoint(
        tracePoint: MethodCallTracePoint,
        atomicUpdater: Any,
        parameters: Array<Any?>,
    ): MethodCallTracePoint {
        getAtomicFieldUpdaterDescriptor(atomicUpdater)?.let { tracePoint.initializeOwnerName(it.fieldName) }
        tracePoint.initializeParameters(parameters.drop(1))
        return tracePoint
    }

    private fun MethodCallTracePoint.initializeParameters(parameters: List<Any?>) =
        initializeParameters(parameters.map { adornedStringRepresentation(it) }, parameters.map { objectFqTypeName(it) })

    private fun methodAnalysisSectionType(
        owner: Any?,
        className: String,
        methodName: String
    ): AnalysisSectionType {
        val ownerName = owner?.javaClass?.canonicalName ?: className
        // Ignore methods called on standard I/O streams
        when (owner) {
            System.`in`, System.out, System.err -> return AnalysisSectionType.IGNORED
        }
        return analysisProfile.getAnalysisSectionFor(ownerName, methodName)
    }

    private fun enterAnalysisSection(td: ThreadData, section: AnalysisSectionType) {
        val analysisSectionStack = td.analysisSectionStack
        val currentSection = analysisSectionStack.lastOrNull()
        if (currentSection != null && currentSection.isCallStackPropagating() && section < currentSection) {
            analysisSectionStack.add(currentSection)
        } else {
            analysisSectionStack.add(section)
        }
        if (section == AnalysisSectionType.IGNORED ||
            // TODO: atomic should have different semantics compared to ignored
            section == AnalysisSectionType.ATOMIC
        ) {
            enterIgnoredSection()
        }
    }

    private fun leaveAnalysisSection(td: ThreadData, section: AnalysisSectionType) {
        if (section == AnalysisSectionType.IGNORED ||
            // TODO: atomic should have different semantics compared to ignored
            section == AnalysisSectionType.ATOMIC
        ) {
            leaveIgnoredSection()
        }
        val analysisSectionStack = td.analysisSectionStack
        analysisSectionStack.removeLast().ensure { currentSection ->
            currentSection == section || (currentSection.isCallStackPropagating() && section < currentSection)
        }
    }
}
