/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.jetbrains.lincheck.jvm.agent.transformers.*
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode.*
import org.jetbrains.lincheck.jvm.agent.LincheckClassFileTransformer.liveDebuggerSettings
import org.jetbrains.lincheck.util.*

interface TransformationProfile {
    fun shouldTransform(className: String): Boolean

    fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration
}

class TransformationConfiguration(
    var trackObjectCreations: Boolean = false,

    var trackLocalVariableReads: Boolean = false,
    var trackLocalVariableWrites: Boolean = false,
    var trackRegularFieldReads: Boolean = false,
    var trackRegularFieldWrites: Boolean = false,
    var trackStaticFieldReads: Boolean = false,
    var trackStaticFieldWrites: Boolean = false,
    var trackArrayElementReads: Boolean = false,
    var trackArrayElementWrites: Boolean = false,

    var trackMethodCalls: Boolean = false,
    var interceptMethodCallResults: Boolean = false,
    var trackConstructorCalls: Boolean = false,
    var trackInlineMethodCalls: Boolean = false,

    var trackThreadRun: Boolean = false,
    var trackThreadStart: Boolean = false,
    var trackThreadJoin: Boolean = false,

    var trackMonitorsOperations: Boolean = false,
    var trackWaitNotifyOperations: Boolean = false,
    var trackSynchronizedBlocks: Boolean = false,
    var trackParkingOperations: Boolean = false,

    var trackLoops: Boolean = false,

    var interceptIdentityHashCodes: Boolean = false,

    var interceptInvokeDynamic: Boolean = false,

    // TODO: Figure out where to set this to true
    var interceptReadResults: Boolean = false,

    var trackCoroutineSuspensions: Boolean = false,
    var interceptCoroutineDelays: Boolean = false,

    var trackSnapshotLineBreakpoints: Boolean = false,

    /**
     * Trace ID's provided by tracing frameworks like OpenTelemetry
     */
    var trackTraceIds: Boolean = false,

    var trackThrows: Boolean = false,
    var trackCatchBlocks: Boolean = false,

    var wrapInIgnoredSection: Boolean = false,

    // TODO: in the future, we may want to provide finer-grained control
    //   over which operations are tracked, for example:
    //   - track only shared reads `trackSharedReadAccesses` or writes `trackSharedWriteAccesses` (same for local vars),
    //   - control where tracking hooks are injected, for instance,
    //     before `injectBeforeSharedReadAccess` and/or `injectAfterSharedReadAccess`.
    //   - and other ...
) {
    var trackAllSynchronizationOperations: Boolean
        get() =
            trackMonitorsOperations &&
            trackWaitNotifyOperations &&
            trackSynchronizedBlocks &&
            trackParkingOperations
        set(value) {
            trackMonitorsOperations = value
            trackWaitNotifyOperations = value
            trackSynchronizedBlocks = value
            trackParkingOperations = value
        }

    val trackLocalVariableAccesses: Boolean
        get() = trackLocalVariableReads || trackLocalVariableWrites

    var trackAllLocalVariableAccesses: Boolean
        get() =
            trackLocalVariableReads &&
            trackLocalVariableWrites
        set(value) {
            trackLocalVariableReads = value
            trackLocalVariableWrites = value
        }

    var trackAllFieldsReads: Boolean
        get() =
            trackRegularFieldReads &&
            trackStaticFieldReads
        set(value) {
            trackRegularFieldReads = value
            trackStaticFieldReads = value
        }

    var trackAllFieldsWrites: Boolean
        get() =
            trackRegularFieldWrites &&
            trackStaticFieldWrites
        set(value) {
            trackRegularFieldWrites = value
            trackStaticFieldWrites = value
        }

    val trackSharedMemoryAccesses: Boolean
        get() = trackRegularFieldReads  || trackStaticFieldReads  || trackArrayElementReads ||
                trackRegularFieldWrites || trackStaticFieldWrites || trackArrayElementWrites

    var trackAllSharedMemoryAccesses: Boolean
        get() =
            trackAllFieldsReads && trackArrayElementReads &&
            trackAllFieldsWrites && trackArrayElementWrites
        set(value) {
            trackAllFieldsReads = value
            trackArrayElementReads = value
            trackAllFieldsWrites = value
            trackArrayElementWrites = value
        }

    val trackThreadsOperations: Boolean
        get() = trackThreadRun || trackThreadStart || trackThreadJoin

    var trackAllThreadsOperations: Boolean
        get() =
            trackThreadRun &&
            trackThreadStart &&
            trackThreadJoin
        set(value) {
            trackThreadRun = value
            trackThreadStart = value
            trackThreadJoin = value
        }

    companion object {
        val UNTRACKED = TransformationConfiguration()
    }
}

internal fun TransformationConfiguration.shouldApplyVisitor(visitorClass: Class<*>): Boolean {
    return when (visitorClass) {
        ObjectCreationTransformerBase::class.java -> trackObjectCreations

        LocalVariablesAccessTransformer::class.java -> trackLocalVariableAccesses
        SharedMemoryAccessTransformer::class.java -> trackSharedMemoryAccesses

        MethodCallTransformer::class.java -> trackMethodCalls
        InlineMethodCallTransformer::class.java -> trackInlineMethodCalls

        ThreadRunTransformer::class.java -> trackThreadRun
        ThreadStartJoinTransformer::class.java -> trackThreadStart || trackThreadJoin

        MonitorTransformer::class.java -> trackMonitorsOperations
        WaitNotifyTransformer::class.java -> trackWaitNotifyOperations
        SynchronizedMethodTransformer::class.java -> trackSynchronizedBlocks
        ParkingTransformer::class.java -> trackParkingOperations

        LoopTransformer::class.java -> trackLoops

        ConstantHashCodeTransformer::class.java -> interceptIdentityHashCodes

        DeterministicInvokeDynamicTransformer::class.java -> interceptInvokeDynamic

        CoroutineCancellabilitySupportTransformer::class.java -> trackCoroutineSuspensions
        CoroutineDelaySupportTransformer::class.java -> interceptCoroutineDelays

        SnapshotBreakpointTransformer::class.java -> trackSnapshotLineBreakpoints

        IgnoredSectionWrapperTransformer::class.java -> wrapInIgnoredSection

        ThrowTransformer::class.java -> trackThrows
        CatchBlockStartTransformer::class.java -> trackCatchBlocks

        // the configuration does not govern other types of transformers,
        // so they should be applied by default
        else -> true
    }
}

fun createTransformationProfile(
    mode: InstrumentationMode,
    includeClasses: List<String> = emptyList(),
    excludeClasses: List<String> = emptyList(),
): TransformationProfile {
    val defaultProfile = when (mode) {
        STRESS -> StressDefaultTransformationProfile
        TRACE_RECORDING -> TraceRecorderDefaultTransformationProfile
        LIVE_DEBUGGING -> LiveDebuggerTransformationProfile(liveDebuggerSettings)
        TRACE_DEBUGGING -> TraceDebuggerDefaultTransformationProfile
        MODEL_CHECKING -> ModelCheckingDefaultTransformationProfile
        EXPERIMENTAL_MODEL_CHECKING -> ExperimentalModelCheckingTransformationProfile
    }
    if (includeClasses.isNotEmpty() || excludeClasses.isNotEmpty()) {
        return FilteredTransformationProfile(includeClasses, excludeClasses, defaultProfile)
    }
    return defaultProfile
}

class FilteredTransformationProfile(
    val includeClasses: List<String>,
    val excludeClasses: List<String>,
    val baseProfile: TransformationProfile,
) : TransformationProfile {
    private val includeRegexes: List<Regex> = includeClasses.map { it.toGlobRegex() }
    private val excludeRegexes: List<Regex> = excludeClasses.map { it.toGlobRegex() }

    override fun shouldTransform(className: String): Boolean {
        // exclude has a higher priority
        if (excludeRegexes.any { it.matches(className) }) {
            return false
        }

        // if the include list is specified instrument only included classes
        if (includeRegexes.isNotEmpty() && !includeRegexes.any { it.matches(className) }) {
            return false
        }

        // otherwise, delegate decision to the base profile
        return baseProfile.shouldTransform(className)
    }

    override fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration {
        return baseProfile.getMethodConfiguration(className, methodName, descriptor)
    }

    private fun String.toGlobRegex(): Regex {
        // Convert a simple glob with '*' to a proper anchored regex.
        // Escape all regex meta-characters in literal segments and replace '*' with '.*'.
        if (this == "*") return ".*".toRegex()
        val parts = this.split("*")
        val pattern = parts.joinToString(".*") { Regex.escape(it) }
        return ("^$pattern$").toRegex()
    }
}

object StressDefaultTransformationProfile : TransformationProfile {

    override fun shouldTransform(className: String): Boolean {
        // In the stress testing mode, we can simply skip the standard
        // Java and Kotlin classes -- they do not have coroutine suspension points.
        if (isRecognizedUninstrumentedStandardLibraryClass(className)) return false

        if (isRecognizedUninstrumentedClass(className)) return false
        return true
    }

    override fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration {
        val config = TransformationConfiguration()

        if (methodName == "<clinit>" || methodName == "<init>") {
            return config
        }

        // in stress mode we track only coroutine suspension points
        return config.apply {
            trackCoroutineSuspensions = true
        }
    }
}

object TraceRecorderDefaultTransformationProfile : TransformationProfile {

    override fun shouldTransform(className: String): Boolean {
        // Instrument thread-related classes to intercept `Thread.run` beginning/end.
        if (className == "java.lang.Thread") return true
        if (className.startsWith("kotlin.concurrent.ThreadsKt")) return true

        // In the trace recording mode, we do not instrument Java/Kotlin stdlib classes.
        if (isRecognizedUninstrumentedStandardLibraryClass(className)) return false

        // there is a bug with instrumentation of android tools classes,
        // see https://youtrack.jetbrains.com/issue/JBRes-7051
        if (className.startsWith("com.android.tools.")) return false

        if (isRecognizedUninstrumentedClass(className)) return false
        return true
    }

    override fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration {
        val config = TransformationConfiguration()

        if (shouldWrapInIgnoredSection(className, methodName, descriptor)) {
            return config.apply {
                wrapInIgnoredSection = true
            }
        }

        // For `java.lang.Thread` class (and `ThreadContainer.start()` method),
        // we only apply `ThreadTransformer` and skip all other transformations
        if (isThreadClass(className) || isThreadContainerThreadStartMethod(className, methodName)) {
            return config.apply {
                trackThreadRun = true
            }
        }

        // Currently, constructors are treated in a special way to avoid problems
        // with `VerificationError` due to leaking this problem,
        // see: https://github.com/JetBrains/lincheck/issues/424
        if (methodName == "<init>") {
            return config
        }

        return config.apply {
            trackLocalVariableWrites = true
            trackAllFieldsWrites = true
            trackArrayElementWrites = true

            trackMethodCalls = true
            trackConstructorCalls = true
            trackInlineMethodCalls = false

            trackLoops = true

            trackThreadRun = true

            trackThrows = true
            trackCatchBlocks = true
        }
    }
}

object TraceDebuggerDefaultTransformationProfile : TransformationProfile {

    override fun shouldTransform(className: String): Boolean {
        // We do not need to instrument most standard Java classes.
        // It is fine to inject the Lincheck analysis only into the
        // `java.util.*` ones, ignored the known atomic constructs.
        if (className.startsWith("java.")) {
            // Instrument `Thread` to intercept thread events.
            if (className == "java.lang.Thread") return true
            // Instrument `Throwable` as it has `synchronized` methods,
            // and corresponding monitor events should be intercepted.
            if (className == "java.lang.Throwable") return true
            // Instrument `java.util.concurrent` classes, except atomics.
            if (className.startsWith("java.util.concurrent.") && className.contains("Atomic")) return false
            // Instrument `java.util` classes.
            if (className.startsWith("java.util.")) return true

            // Transform IO classes in trace debugger mode to intercept non-deterministic APIs.
            if (className.startsWith("java.io.")) return true
            if (className.startsWith("java.nio.")) return true
            if (className.startsWith("java.time.")) return true

            return false
        }
        if (className.startsWith("javax.")) return false
        if (className.startsWith("jdk.")) {
            // Transform `ThreadContainer.start` to detect thread forking.
            if (isThreadContainerClass(className)) return true
            return false
        }

        if (className.startsWith("com.sun.")) return false
        if (className.startsWith("sun.")) {
            // We should never instrument the Lincheck classes.
            if (className.startsWith("sun.nio.ch.lincheck.")) return false
            // Transform IO classes in trace debugger mode to intercept non-deterministic APIs.
            if (className.startsWith("sun.nio.")) return true
            return false
        }

        // Old legacy Java std library for CORBA,
        // for instance, `org/omg/stub/javax/management`;
        // can appear on Java 8 when JMX is used.
        if (className.startsWith("org.omg.")) return false

        // We do not need to instrument most standard Kotlin classes.
        // However, we need to inject the Lincheck analysis into the classes
        // related to collections, iterators, random and coroutines.
        if (className.startsWith("kotlin.")) {
            if (className.startsWith("kotlin.concurrent.ThreadsKt")) return true
            if (className.startsWith("kotlin.collections.")) return true
            if (className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) return true
            if (className.startsWith("kotlin.ranges.")) return true
            if (className.startsWith("kotlin.random.")) return true
            if (className.startsWith("kotlin.coroutines.jvm.internal.")) return false
            if (className.startsWith("kotlin.coroutines.")) return true

            // Transform IO classes in trace debugger mode to intercept non-deterministic APIs.
            if (className.startsWith("kotlin.io.")) return true
            return false
        }

        if (isRecognizedUninstrumentedClass(className)) return false
        return true
    }

    override fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration {
        val config = TransformationConfiguration()

        // NOTE: `shouldWrapInIgnoredSection` should be before `shouldNotInstrument`,
        //       otherwise we may incorrectly forget to add some ignored sections
        //       and start tracking events in unexpected places
        if (shouldWrapInIgnoredSection(className, methodName, descriptor)) {
            return config.apply {
                wrapInIgnoredSection = true
            }
        }
        if (shouldNotInstrument(className, methodName, descriptor)) {
            return config
        }

        // For `java.lang.Thread` class (and `ThreadContainer.start()` method),
        // we only apply `ThreadTransformer` and skip all other transformations
        if (isThreadClass(className) || isThreadContainerThreadStartMethod(className, methodName)) {
            return config.apply {
                trackAllThreadsOperations = true
            }
        }

        // In trace debugger mode we record hash codes of tracked objects and substitute them on re-run.
        // To intercept all hash codes, we need to use `DeterministicInvokeDynamicTransformer` transformer,
        //  to properly track events inside various primitives that use `invokedynamic`,
        // such as string templates or lambda expressions.
        config.interceptInvokeDynamic = true

        // Debugger implicitly evaluates `toString()` for variables rendering.
        // We need to ensure there are no `beforeEvents` calls inside `toString()`
        // to ensure the event numeration will remain the same.
        if (ideaPluginEnabled && isToStringMethod(methodName, descriptor)) {
            return config.apply {
                trackObjectCreations = true
            }
        }

        return config.apply {
            trackObjectCreations = true

            trackLocalVariableWrites = true
            trackAllSharedMemoryAccesses = true

            trackMethodCalls = true
            trackInlineMethodCalls = true
            interceptMethodCallResults = true

            trackAllThreadsOperations = true
            trackAllSynchronizationOperations = true

            trackCoroutineSuspensions = true
            interceptCoroutineDelays = true
        }
    }
}

object ModelCheckingDefaultTransformationProfile : TransformationProfile {

    override fun shouldTransform(className: String): Boolean {
        // We do not need to instrument most standard Java classes.
        // It is fine to inject the Lincheck analysis only into the
        // `java.util.*` ones, ignored the known atomic constructs.
        if (className.startsWith("java.")) {
            // Instrument `Thread` to intercept thread events.
            if (className == "java.lang.Thread") return true
            // Instrument `Throwable` as it has `synchronized` methods,
            // and corresponding monitor events should be intercepted.
            if (className == "java.lang.Throwable") return true
            // Instrument `java.util.concurrent` classes, except atomics.
            if (className.startsWith("java.util.concurrent.") && className.contains("Atomic")) return false
            // Instrument `java.util` classes.
            if (className.startsWith("java.util.")) return true
            return false
        }
        if (className.startsWith("javax.")) return false
        if (className.startsWith("jdk.")) {
            // Transform `ThreadContainer.start` to detect thread forking.
            if (isThreadContainerClass(className)) return true
            return false
        }

        if (className.startsWith("com.sun.")) return false
        if (className.startsWith("sun.")) {
            // We should never instrument the Lincheck classes.
            if (className.startsWith("sun.nio.ch.lincheck.")) return false
            return false
        }

        // Old legacy Java std library for CORBA,
        // for instance, `org/omg/stub/javax/management`;
        // can appear on Java 8 when JMX is used.
        if (className.startsWith("org.omg.")) return false

        // We do not need to instrument most standard Kotlin classes.
        // However, we need to inject the Lincheck analysis into the classes
        // related to collections, iterators, random and coroutines.
        if (className.startsWith("kotlin.")) {
            if (className.startsWith("kotlin.concurrent.ThreadsKt")) return true
            if (className.startsWith("kotlin.collections.")) return true
            if (className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) return true
            if (className.startsWith("kotlin.ranges.")) return true
            if (className.startsWith("kotlin.random.")) return true
            if (className.startsWith("kotlin.coroutines.jvm.internal.")) return false
            if (className.startsWith("kotlin.coroutines.")) return true
            return false
        }

        if (isRecognizedUninstrumentedClass(className)) return false
        return true
    }

    override fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration {
        val config = TransformationConfiguration()

        // NOTE: `shouldWrapInIgnoredSection` should be before `shouldNotInstrument`,
        //       otherwise we may incorrectly forget to add some ignored sections
        //       and start tracking events in unexpected places
        if (shouldWrapInIgnoredSection(className, methodName, descriptor)) {
            return config.apply {
                wrapInIgnoredSection = true
            }
        }
        if (shouldNotInstrument(className, methodName, descriptor)) {
            return config
        }

        // For `java.lang.Thread` class (and `ThreadContainer.start()` method),
        // we only apply `ThreadTransformer` and skip all other transformations
        if (isThreadClass(className) || isThreadContainerThreadStartMethod(className, methodName)) {
            return config.apply {
                trackAllThreadsOperations = true
            }
        }

        // Debugger implicitly evaluates `toString()` for variables rendering.
        // We need to ensure there are no `beforeEvents` calls inside `toString()`
        // to ensure the event numeration will remain the same.
        if (ideaPluginEnabled && isToStringMethod(methodName, descriptor)) {
            return config.apply {
                trackObjectCreations = true
            }
        }

        // Currently, constructors are treated in a special way to avoid problems
        // with `VerificationError` due to leaking this problem,
        // see: https://github.com/JetBrains/lincheck/issues/424
        if (methodName == "<init>") {
            return config.apply {
                trackObjectCreations = true
                trackAllSharedMemoryAccesses = true
            }
        }

        return config.apply {
            trackObjectCreations = true

            trackAllSharedMemoryAccesses = true

            trackMethodCalls = true
            trackInlineMethodCalls = true
            interceptMethodCallResults = true

            trackAllThreadsOperations = true
            trackAllSynchronizationOperations = true

            // In model checking mode we track all hash code calls in the instrumented code
            // and substitute them with a constant value.
            interceptIdentityHashCodes = true

            trackCoroutineSuspensions = true
            interceptCoroutineDelays = true
        }
    }
}

//TODO: There is some pretty epic code duplication
object ExperimentalModelCheckingTransformationProfile : TransformationProfile {

    override fun shouldTransform(className: String): Boolean {
        // We do not need to instrument most standard Java classes.
        // It is fine to inject the Lincheck analysis only into the
        // `java.util.*` ones, ignored the known atomic constructs.
        if (className.startsWith("java.")) {
            // Instrument `Thread` to intercept thread events.
            if (className == "java.lang.Thread") return true
            // Instrument `Throwable` as it has `synchronized` methods,
            // and corresponding monitor events should be intercepted.
            if (className == "java.lang.Throwable") return true
            // Instrument `java.util.concurrent` classes, except atomics.
            if (className.startsWith("java.util.concurrent.") && className.contains("Atomic")) return false
            // Instrument `java.util` classes.
            if (className.startsWith("java.util.")) return true
            return false
        }
        if (className.startsWith("javax.")) return false
        if (className.startsWith("jdk.")) {
            // Transform `ThreadContainer.start` to detect thread forking.
            if (isThreadContainerClass(className)) return true
            return false
        }

        if (className.startsWith("com.sun.")) return false
        if (className.startsWith("sun.")) {
            // We should never instrument the Lincheck classes.
            if (className.startsWith("sun.nio.ch.lincheck.")) return false
            return false
        }

        // Old legacy Java std library for CORBA,
        // for instance, `org/omg/stub/javax/management`;
        // can appear on Java 8 when JMX is used.
        if (className.startsWith("org.omg.")) return false

        // We do not need to instrument most standard Kotlin classes.
        // However, we need to inject the Lincheck analysis into the classes
        // related to collections, iterators, random and coroutines.
        if (className.startsWith("kotlin.")) {
            if (className.startsWith("kotlin.concurrent.ThreadsKt")) return true
            if (className.startsWith("kotlin.collections.")) return true
            if (className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) return true
            if (className.startsWith("kotlin.ranges.")) return true
            if (className.startsWith("kotlin.random.")) return true
            if (className.startsWith("kotlin.coroutines.jvm.internal.")) return false
            if (className.startsWith("kotlin.coroutines.")) return true
            return false
        }

        if (isRecognizedUninstrumentedClass(className)) return false
        return true
    }

    override fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration {
        val config = TransformationConfiguration()

        // NOTE: `shouldWrapInIgnoredSection` should be before `shouldNotInstrument`,
        //       otherwise we may incorrectly forget to add some ignored sections
        //       and start tracking events in unexpected places
        if (shouldWrapInIgnoredSection(className, methodName, descriptor)) {
            return config.apply {
                wrapInIgnoredSection = true
            }
        }
        if (shouldNotInstrument(className, methodName, descriptor)) {
            return config
        }

        // For `java.lang.Thread` class (and `ThreadContainer.start()` method),
        // we only apply `ThreadTransformer` and skip all other transformations
        if (isThreadClass(className) || isThreadContainerThreadStartMethod(className, methodName)) {
            return config.apply {
                trackAllThreadsOperations = true
            }
        }

        // Debugger implicitly evaluates `toString()` for variables rendering.
        // We need to ensure there are no `beforeEvents` calls inside `toString()`
        // to ensure the event numeration will remain the same.
        if (ideaPluginEnabled && isToStringMethod(methodName, descriptor)) {
            return config.apply {
                trackObjectCreations = true
            }
        }

        // Currently, constructors are treated in a special way to avoid problems
        // with `VerificationError` due to leaking this problem,
        // see: https://github.com/JetBrains/lincheck/issues/424
        if (methodName == "<init>") {
            return config.apply {
                trackObjectCreations = true
                trackAllSharedMemoryAccesses = true
            }
        }

        return config.apply {
            trackObjectCreations = true

            trackAllSharedMemoryAccesses = true

            trackMethodCalls = true
            trackInlineMethodCalls = true
            interceptMethodCallResults = true

            trackAllThreadsOperations = true
            trackAllSynchronizationOperations = true

            // In model checking mode we track all hash code calls in the instrumented code
            // and substitute them with a constant value.
            interceptIdentityHashCodes = true

            trackCoroutineSuspensions = true
            interceptCoroutineDelays = true

            trackRegularFieldReads = true
            trackRegularFieldWrites = true
            trackStaticFieldReads = true
            trackStaticFieldWrites = true

            trackLocalVariableReads = true
            trackLocalVariableWrites = true
            trackArrayElementReads = true
            trackArrayElementWrites = true

            interceptReadResults = true
        }
    }
}

class LiveDebuggerTransformationProfile(
    val settings: LiveDebuggerSettings,
) : TransformationProfile {

    override fun shouldTransform(className: String): Boolean {
        return isLiveDebuggerBreakpointClass(className)
    }

    private fun isLiveDebuggerBreakpointClass(className: String): Boolean =
        settings.lineBreakPoints.any { it.className == className }

    override fun getMethodConfiguration(
        className: String,
        methodName: String,
        descriptor: String
    ): TransformationConfiguration {
        // In live debugger mode, only track snapshot line breakpoints
        return TransformationConfiguration().apply {
            trackSnapshotLineBreakpoints = true
            trackTraceIds = true
        }
    }
}

// Common rules for all transformation profiles regarding uninstrumented classes;
// basically handles a shared set of third-party libraries and tools
// (everything except Java and Kotlin stdlib).
private fun isRecognizedUninstrumentedClass(className: String): Boolean {
    // Do not instrument AtomicFU's atomics.
    if (isKotlinxAtomicFUClass(className) && className.contains("Atomic")) return true

    // We need to skip the classes related to the debugger support in Kotlin coroutines.
    if (isKotlinxCoroutinesDebugClass(className)) return true

    // We should never transform IntelliJ runtime classes (debugger and coverage agents).
    if (isIntellijRuntimeAgentClass(className)) return true
    // We should never instrument the JetBrains coverage package classes (for instance, relocated ASM library).
    if (isJetBrainsCoverageClass(className)) return true

    // Do not instrument bytecode-manipulation libraries
    // (special care required to circumvent package shadowing, see `TraceAgentTasks.kt`).
    if (isAsmClass(className) || isByteBuddyClass(className)) return true

    // We can also safely do not instrument some libraries for performance reasons.
    if (isGradleClass(className)) return true
    if (isRecognizedTestingLibraryClass(className)) return true
    if (isRecognizedLoggingLibraryClass(className)) return true
    if (isRecognizedApacheLibraryClass(className)) return true

    if (isRecognizedUninstrumentedThirdPartyLibraryClass(className)) return true

    // All the classes that were not filtered out are eligible for transformation.
    return false
}

private fun shouldWrapInIgnoredSection(className: String, methodName: String, descriptor: String): Boolean {
    // Wrap static initialization blocks into ignored sections.
    if (methodName == "<clinit>")
        return true
    // Wrapping constructors in ignored sections is not yet supported.
    if (methodName == "<init>")
        return false
    // Wrap `ClassLoader::loadClass(className)` calls into ignored sections
    // to ensure their code is not analyzed by the Lincheck.
    if (isClassLoaderClassName(className) && isLoadClassMethod(methodName, descriptor))
        return true
    // Wrap `MethodHandles.Lookup.findX` and related methods into ignored sections
    // to ensure their code is not analyzed by the Lincheck.
    if (isIgnoredMethodHandleMethod(className, methodName))
        return true
    // Wrap all methods of the `StackTraceElement` class into ignored sections.
    // Although `StackTraceElement` own bytecode should not be instrumented,
    // it may call functions from `java.util` classes (e.g., `HashMap`),
    // which can be instrumented and analyzed.
    // At the same time, `StackTraceElement` methods can be called almost at any point
    // (e.g., when an exception is thrown and its stack trace is being collected),
    // and we should ensure that these calls are not analyzed by Lincheck.
    //
    // See the following issues:
    //   - https://github.com/JetBrains/lincheck/issues/376
    //   - https://github.com/JetBrains/lincheck/issues/419
    if (isStackTraceElementClass(className))
        return true
    // Ignore methods of JDK 20+ `ThreadContainer` classes, except the `start` method.
    if (isThreadContainerClass(className) &&
        !isThreadContainerThreadStartMethod(className, methodName))
        return true
    // Wrap IntelliJ IDEA runtime agent's methods into an ignored section.
    if (isIntellijRuntimeAgentClass(className))
        return true

    return false
}

private fun shouldNotInstrument(className: String, methodName: String, descriptor: String): Boolean {
    // Do not instrument `ClassLoader` methods.
    if (isClassLoaderClassName(className))
        return true
    // Do not instrument `MethodHandles` constructors.
    if (isMethodHandleRelatedClass(className) && methodName == "<init>")
        return true
    // Instrumentation of `java.util.Arrays` class causes some subtle flaky bugs.
    // See details in https://github.com/JetBrains/lincheck/issues/717.
    if (isJavaUtilArraysClass(className))
        return true
    // Do not instrument coroutines' internal machinery.
    if (isCoroutineInternalClass(className))
        return true

    return false
}