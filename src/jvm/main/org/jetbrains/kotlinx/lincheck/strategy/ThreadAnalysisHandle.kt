/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy

import org.jetbrains.kotlinx.lincheck.primitiveOrIdentityHashCode
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicFieldUpdaterNames.getAtomicFieldUpdaterDescriptor
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.AtomicArrayInLocalVariable
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.AtomicArrayMethod
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.AtomicReferenceInLocalVariable
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.AtomicReferenceInstanceMethod
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.AtomicReferenceStaticMethod
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.InstanceFieldAtomicArrayMethod
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.StaticFieldAtomicArrayMethod
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceNames
import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectLabelFactory.adornedStringRepresentation
import org.jetbrains.kotlinx.lincheck.strategy.managed.ShadowStackFrame
import org.jetbrains.kotlinx.lincheck.strategy.managed.UnsafeName
import org.jetbrains.kotlinx.lincheck.strategy.managed.UnsafeName.UnsafeArrayMethod
import org.jetbrains.kotlinx.lincheck.strategy.managed.UnsafeName.UnsafeInstanceMethod
import org.jetbrains.kotlinx.lincheck.strategy.managed.UnsafeName.UnsafeStaticMethod
import org.jetbrains.kotlinx.lincheck.strategy.managed.UnsafeNames
import org.jetbrains.kotlinx.lincheck.strategy.managed.VarHandleMethodType
import org.jetbrains.kotlinx.lincheck.strategy.managed.VarHandleMethodType.ArrayVarHandleMethod
import org.jetbrains.kotlinx.lincheck.strategy.managed.VarHandleMethodType.InstanceVarHandleMethod
import org.jetbrains.kotlinx.lincheck.strategy.managed.VarHandleMethodType.StaticVarHandleMethod
import org.jetbrains.kotlinx.lincheck.strategy.managed.VarHandleNames
import org.jetbrains.kotlinx.lincheck.strategy.native_calls.DeterministicMethodDescriptor
import org.jetbrains.kotlinx.lincheck.trace.CallStackTraceElement
import org.jetbrains.kotlinx.lincheck.trace.MethodCallTracePoint
import org.jetbrains.kotlinx.lincheck.trace.ReadTracePoint
import org.jetbrains.kotlinx.lincheck.trace.TraceCollector
import org.jetbrains.kotlinx.lincheck.trace.TracePoint
import org.jetbrains.kotlinx.lincheck.trace.WriteTracePoint
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.transformation.toSimpleClassName
import org.jetbrains.kotlinx.lincheck.util.AnalysisSectionType
import org.jetbrains.kotlinx.lincheck.util.AtomicMethodDescriptor
import org.jetbrains.kotlinx.lincheck.util.ThreadId
import org.jetbrains.kotlinx.lincheck.util.ensure
import org.jetbrains.kotlinx.lincheck.util.enterIgnoredSection
import org.jetbrains.kotlinx.lincheck.util.findInstanceFieldReferringTo
import org.jetbrains.kotlinx.lincheck.util.isAtomic
import org.jetbrains.kotlinx.lincheck.util.isAtomicArray
import org.jetbrains.kotlinx.lincheck.util.isAtomicFieldUpdater
import org.jetbrains.kotlinx.lincheck.util.isSuspendFunction
import org.jetbrains.kotlinx.lincheck.util.isUnsafe
import org.jetbrains.kotlinx.lincheck.util.isVarHandle
import sun.nio.ch.lincheck.MethodSignature
import org.jetbrains.kotlinx.lincheck.util.isCallStackPropagating
import org.jetbrains.kotlinx.lincheck.util.leaveIgnoredSection
import java.util.IdentityHashMap
import java.util.Objects

@Suppress("UNUSED_PARAMETER")

/**
 * This class contains all data needed to track events for one thread.
 * It is not concurrent-safe, and each instance must be used strictly from one thread only.
 *
 * @property threadId Id of thread to use in trace, simply an increasing number.
 * @property traceCollector Collector to store all [TracePoint]-s generated for this thread.
 */
// TODO: unify with ThreadScheduler.ThreadData
// TODO: better name?
internal class ThreadAnalysisHandle(val threadId: Int, val traceCollector: TraceCollector?) {
    /**
     * Current tracked stacktrace.
     */
    // TODO: Unify with shadowStack
    // TODO: do not expose mutable list
    val stackTrace: MutableList<CallStackTraceElement> = arrayListOf()

    /**
     * Shadow stack reflecting the program's actual stack.
     */
    // TODO: do not expose mutable list
    val shadowStack: MutableList<ShadowStackFrame> = arrayListOf()

    /**
     * Stack of entered analysis section types.
     */
    // TODO: unify with `shadowStack`
    // TODO: handle coroutine resumptions (i.e., unify with `suspendedFunctionsStack`)
    // TODO: do not expose mutable list
    val analysisSectionStack: MutableList<AnalysisSectionType> = arrayListOf()

    /**
     * Tracks content of constants (i.e., static final fields).
     * Stores a map `object -> fieldName`,
     * mapping an object to a constant name referencing this object.
     */
    // TODO: do not expose mutable map
    val constants: IdentityHashMap<Any, String> = IdentityHashMap<Any, String>()

    /**
     * Last read constant name (i.e., static final field).
     * We store it as we initialize read value after the trace point is created,
     * so we have to store the trace point somewhere to get it later.
     */
    var lastReadConstantName: String? = null
        private set

    /**
     * Last read trace point, occurred in the current thread.
     * We store it as we initialize read value after the point is created,
     * so we have to store the trace point somewhere to get it later.
     */
    var lastReadTracePoint: ReadTracePoint? = null
        private set

    private val collectTrace = (traceCollector != null)

    fun beforeReadField(
        obj: Any?,
        className: String,
        fieldName: String,
        codeLocation: Int,
        isStatic: Boolean,
        isFinal: Boolean
    ) {
        if (collectTrace && isStatic && isFinal) {
            lastReadConstantName = fieldName
        }
    }

    fun afterRead(value: Any?) {
        if (!collectTrace) return
        if (lastReadConstantName != null && value != null) {
            constants[value] = lastReadConstantName
        }
        val valueRepresentation = adornedStringRepresentation(value)
        val typeRepresentation = objectFqTypeName(value)
        lastReadTracePoint?.initializeReadValue(valueRepresentation, typeRepresentation)
        lastReadTracePoint = null
        lastReadConstantName = null
    }

    fun afterLocalRead(variableName: String, value: Any?) {
        if (!collectTrace) return
        val shadowStackFrame = shadowStack.last()
        shadowStackFrame.setLocalVariable(variableName, value)
        // TODO: enable local vars tracking in the trace after further polishing
        // TODO: add a flag to enable local vars tracking in the trace conditionally
        // val tracePoint = if (collectTrace) {
        //     ReadTracePoint(
        //         ownerRepresentation = null,
        //         iThread = iThread,
        //         actorId = currentActorId[iThread]!!,
        //         callStackTrace = callStackTrace[iThread]!!,
        //         fieldName = variableName,
        //         codeLocation = codeLocation,
        //         isLocal = true,
        //     ).also { it.initializeReadValue(adornedStringRepresentation(value), objectFqTypeName(value)) }
        // } else {
        //     null
        // }
        // traceCollector!!.passCodeLocation(tracePoint)
    }

    fun afterLocalWrite(variableName: String, value: Any?) {
        if (!collectTrace) return
        val shadowStackFrame = shadowStack.last()
        shadowStackFrame.setLocalVariable(variableName, value)
        // TODO: enable local vars tracking in the trace after further polishing
        // TODO: add a flag to enable local vars tracking in the trace conditionally
        // val tracePoint = if (collectTrace) {
        //     WriteTracePoint(
        //         ownerRepresentation = null,
        //         iThread = iThread,
        //         actorId = currentActorId[iThread]!!,
        //         callStackTrace = callStackTrace[iThread]!!,
        //         fieldName = variableName,
        //         codeLocation = codeLocation,
        //         isLocal = true,
        //     ).also { it.initializeWrittenValue(adornedStringRepresentation(value), objectFqTypeName(value)) }
        // } else {
        //     null
        // }
        // traceCollector!!.passCodeLocation(tracePoint)
    }

    fun createReadFieldTracePoint(
        obj: Any?,
        className: String,
        fieldName: String,
        codeLocation: Int,
        isStatic: Boolean,
        isFinal: Boolean,
        actorId: Int = 0,
    ) : ReadTracePoint? = if (!collectTrace) null else ReadTracePoint(
        ownerRepresentation = getFieldOwnerName(obj, className, fieldName, isStatic),
        iThread = threadId,
        actorId = actorId,
        callStackTrace = stackTrace,
        fieldName = fieldName,
        codeLocation = codeLocation,
        isLocal = false,
    ).apply {
        lastReadTracePoint = this
    }

    fun createReadArrayElementTracePoint(
        array: Any,
        index: Int,
        codeLocation: Int,
        actorId: Int = 0,
    ) : ReadTracePoint? = if (!collectTrace) null else ReadTracePoint(
        ownerRepresentation = null,
        iThread = threadId,
        actorId = actorId,
        callStackTrace = stackTrace,
        fieldName = "${adornedStringRepresentation(array)}[$index]",
        codeLocation = codeLocation,
        isLocal = false,
    ).apply {
        lastReadTracePoint = this
    }

    fun createWriteArrayElementTracePoint(
        array: Any,
        index: Int,
        value: Any?,
        codeLocation: Int,
        actorId: Int = 0,
    ) : WriteTracePoint? = if (!collectTrace) null else WriteTracePoint(
        ownerRepresentation = null,
        iThread = threadId,
        actorId = actorId,
        callStackTrace = stackTrace,
        fieldName = "${adornedStringRepresentation(array)}[$index]",
        codeLocation = codeLocation,
        isLocal = false,
    ).apply {
        initializeWrittenValue(adornedStringRepresentation(value), objectFqTypeName(value))
    }

    fun createWriteFieldTracepoint(
        obj: Any?,
        className: String,
        fieldName: String,
        value: Any?,
        codeLocation: Int,
        isStatic: Boolean,
        actorId: Int = 0,
    ): WriteTracePoint? = if (!collectTrace) null else WriteTracePoint(
        ownerRepresentation = getFieldOwnerName(obj, className, fieldName, isStatic),
        iThread = threadId,
        actorId = actorId,
        callStackTrace = stackTrace,
        fieldName = fieldName,
        codeLocation = codeLocation,
        isLocal = false
    ).apply {
        initializeWrittenValue(adornedStringRepresentation(value), objectFqTypeName(value))
    }

    fun createMethodCallTracePoint(
        obj: Any?,
        className: String,
        methodName: String,
        params: Array<Any?>,
        codeLocation: Int,
        atomicMethodDescriptor: AtomicMethodDescriptor?,
        callType: MethodCallTracePoint.CallType,
        actorId: Int = 0,
    ): MethodCallTracePoint {
        val tracePoint = MethodCallTracePoint(
            iThread = threadId,
            actorId = actorId,
            className = className,
            methodName = methodName,
            callStackTrace = stackTrace,
            codeLocation = codeLocation,
            isStatic = (obj == null),
            callType = callType,
            isSuspend = isSuspendFunction(className, methodName, params)
        )
        // handle non-atomic methods
        if (atomicMethodDescriptor == null) {
            val ownerName = if (obj != null) findOwnerName(obj) else className.toSimpleClassName()
            if (!ownerName.isNullOrEmpty()) {
                tracePoint.initializeOwnerName(ownerName)
            }
            tracePoint.initializeParameters(params.toList())
            return tracePoint
        }
        // handle atomic methods
        if (isVarHandle(obj)) {
            return initializeVarHandleMethodCallTracePoint(tracePoint, obj, params)
        }
        if (isAtomicFieldUpdater(obj)) {
            return initializeAtomicUpdaterMethodCallTracePoint(tracePoint, obj!!, params)
        }
        if (isAtomic(obj) || isAtomicArray(obj)) {
            return initializeAtomicReferenceMethodCallTracePoint(tracePoint, obj!!, params)
        }
        if (isUnsafe(obj)) {
            return initializeUnsafeMethodCallTracePoint(tracePoint, obj!!, params)
        }
        error("Unknown atomic method $className::$methodName")
    }

    fun pushMethodCallTracePointOnStack(
        obj: Any?,
        methodId: Int,
        methodParams: Array<Any?>,
        tracePoint: MethodCallTracePoint,
        callStackTraceElementId: Int,
    ) {
        val methodInvocationId = Objects.hash(methodId,
            methodParams.map { primitiveOrIdentityHashCode(it) }.toTypedArray().contentHashCode()
        )
        val stackTraceElement = CallStackTraceElement(
            id = callStackTraceElementId,
            instance = obj,
            tracePoint = tracePoint,
            methodInvocationId = methodInvocationId
        )
        stackTrace.add(stackTraceElement)
    }

    fun pushStackTraceElement(stackTraceElement: CallStackTraceElement) {
        stackTrace.add(stackTraceElement)
    }

    fun popStackTraceElement() {
        stackTrace.removeLast()
    }

    /* Methods to control the shadow stack. */

    fun pushShadowStackFrame(owner: Any?) {
        val stackFrame = ShadowStackFrame(owner)
        shadowStack.add(stackFrame)
    }

    fun popShadowStackFrame() {
        shadowStack.removeLast()
        check(shadowStack.isNotEmpty()) {
            "Shadow stack cannot be empty"
        }
    }

    private fun methodAnalysisSectionType(
        owner: Any?,
        className: String,
        methodName: String,
        atomicMethodDescriptor: AtomicMethodDescriptor?,
        deterministicMethodDescriptor: DeterministicMethodDescriptor<*, *>?,
    ): AnalysisSectionType {
        val ownerName = owner?.javaClass?.canonicalName ?: className
        if (atomicMethodDescriptor != null) {
            return AnalysisSectionType.ATOMIC
        }
        // TODO: decide if we need to introduce special `DETERMINISTIC` guarantee?
        if (deterministicMethodDescriptor != null) {
            return AnalysisSectionType.IGNORED
        }
        // Ignore methods called on standard I/O streams
        when (owner) {
            System.`in`, System.out, System.err -> return AnalysisSectionType.IGNORED
        }
        val section = analysisProfile.getAnalysisSectionFor(ownerName, methodName)
        userDefinedGuarantees?.forEach { guarantee ->
            if (guarantee.classPredicate(ownerName) && guarantee.methodPredicate(methodName)) {
                return guarantee.type
            }
        }
        return section
    }

    fun enterAnalysisSection(section: AnalysisSectionType) {
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

    /**
     * Returns string representation of the field owner based on the provided parameters.
     */
    private fun getFieldOwnerName(obj: Any?, className: String, fieldName: String, isStatic: Boolean): String? {
        if (isStatic) {
            val stackTraceElement = shadowStack.last()
            if (stackTraceElement.instance?.javaClass?.name == className) {
                return null
            }
            return className.toSimpleClassName()
        }
        return findOwnerName(obj!!)
    }

    /**
     * Returns beautiful string representation of the [owner].
     * If the [owner] is `this` of the current method, then returns `null`.
     */
    private fun findOwnerName(owner: Any): String? {
        // if the current owner is `this` - no owner needed
        if (isCurrentStackFrameReceiver(owner)) return null
        // do not prettify thread names
        if (owner is Thread) {
            return adornedStringRepresentation(owner)
        }
        // lookup for the object in local variables and use the local variable name if found
        val shadowStackFrame = shadowStack.last()
        shadowStackFrame.getLastAccessVariable(owner)?.let { return it }
        // lookup for a field name in the current stack frame `this`
        shadowStackFrame.instance
            ?.findInstanceFieldReferringTo(owner)
            ?.let { return it.name }
        // lookup for the constant referencing the object
        constants[owner]?.let { return it }
        // otherwise return object's string representation
        return adornedStringRepresentation(owner)
    }

    /**
     * Checks if [owner] is the `this` object (i.e., receiver) of the currently executed method call.
     */
    private fun isCurrentStackFrameReceiver(owner: Any): Boolean {
        val stackTraceElement = shadowStack.last()
        return (owner === stackTraceElement.instance)
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
        tracePoint: MethodCallTracePoint,
        receiver: Any,
        params: Array<Any?>
    ): MethodCallTracePoint {
        val shadowStackFrame = shadowStack.last()
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
        tracePoint: MethodCallTracePoint,
        varHandle: Any, // for Java 8, the VarHandle class does not exist
        parameters: Array<Any?>,
    ): MethodCallTracePoint {
        val shadowStackFrame = shadowStack.last()
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

}