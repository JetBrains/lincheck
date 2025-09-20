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
import org.jetbrains.lincheck.util.*

interface TransformationProfile {
    fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration
}

class TransformationConfiguration(
    var trackObjectCreations: Boolean = false,

    var trackLocalVariableWrites: Boolean = false,

    var trackRegularFieldReads: Boolean = false,
    var trackRegularFieldWrites: Boolean = false,
    var trackStaticFieldReads: Boolean = false,
    var trackStaticFieldWrites: Boolean = false,
    var trackArrayElementReads: Boolean = false,
    var trackArrayElementWrites: Boolean = false,

    var trackMethodCalls: Boolean = false,
    var trackInlineMethodCalls: Boolean = false,

    var trackThreadsOperations: Boolean = false,

    var trackMonitorsOperations: Boolean = false,
    var trackWaitNotifyOperations: Boolean = false,
    var trackSynchronizedBlocks: Boolean = false,
    var trackParkingOperations: Boolean = false,

    var interceptIdentityHashCodes: Boolean = false,

    var interceptInvokeDynamic: Boolean = false,

    var trackCoroutineSuspensions: Boolean = false,
    var interceptCoroutineDelays: Boolean = false,

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

    companion object {
        val UNTRACKED = TransformationConfiguration()
    }
}

internal fun TransformationConfiguration.shouldApplyVisitor(visitorClass: Class<*>): Boolean {
    return when (visitorClass) {
        ObjectCreationTransformerBase::class.java -> trackObjectCreations

        LocalVariablesAccessTransformer::class.java -> trackLocalVariableWrites
        SharedMemoryAccessTransformer::class.java -> trackSharedMemoryAccesses

        MethodCallTransformerBase::class.java -> trackMethodCalls
        InlineMethodCallTransformer::class.java -> trackInlineMethodCalls

        ThreadTransformer::class.java -> trackThreadsOperations
        MonitorTransformer::class.java -> trackMonitorsOperations
        WaitNotifyTransformer::class.java -> trackWaitNotifyOperations
        SynchronizedMethodTransformer::class.java -> trackSynchronizedBlocks
        ParkingTransformer::class.java -> trackParkingOperations

        ConstantHashCodeTransformer::class.java -> interceptIdentityHashCodes

        DeterministicInvokeDynamicTransformer::class.java -> interceptInvokeDynamic

        CoroutineCancellabilitySupportTransformer::class.java -> trackCoroutineSuspensions
        CoroutineDelaySupportTransformer::class.java -> interceptCoroutineDelays

        IgnoredSectionWrapperTransformer::class.java -> wrapInIgnoredSection

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
        TRACE_DEBUGGING -> TraceDebuggerDefaultTransformationProfile
        MODEL_CHECKING -> ModelCheckingDefaultTransformationProfile
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

    override fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration {
        // exclude has a higher priority
        if (excludeRegexes.any { it.matches(className) }) {
            return TransformationConfiguration.UNTRACKED
        }

        // if the include list is specified, instrument only included classes
        if (includeRegexes.isNotEmpty() && !includeRegexes.any { it.matches(className) }) {
            return TransformationConfiguration.UNTRACKED
        }

        // otherwise, delegate decision to the base profile
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
                trackThreadsOperations = true
            }
        }

        // Currently, constructors are treated in a special way to avoid problems
        // with `VerificationError` due to leaking this problem,
        // see: https://github.com/JetBrains/lincheck/issues/424
        if ((methodName == "<init>")) {
            return config.apply {
                trackObjectCreations = true
            }
        }

        return config.apply {
            trackObjectCreations = true
            trackLocalVariableWrites = true

            trackStaticFieldReads = true
            trackAllFieldsWrites = true
            trackArrayElementWrites = true

            trackMethodCalls = true
            trackInlineMethodCalls = false

            trackThreadsOperations = true
        }
    }
}

object TraceDebuggerDefaultTransformationProfile : TransformationProfile {
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
                trackThreadsOperations = true
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

            trackThreadsOperations = true
            trackAllSynchronizationOperations = true

            trackCoroutineSuspensions = true
            interceptCoroutineDelays = true
        }
    }
}

object ModelCheckingDefaultTransformationProfile : TransformationProfile {
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
                trackThreadsOperations = true
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
        if ((methodName == "<init>")) {
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

            trackThreadsOperations = true
            trackAllSynchronizationOperations = true

            // In model checking mode we track all hash code calls in the instrumented code
            // and substitute them with a constant value.
            interceptIdentityHashCodes = true

            trackCoroutineSuspensions = true
            interceptCoroutineDelays = true
        }
    }
}

private fun shouldWrapInIgnoredSection(className: String, methodName: String, descriptor: String): Boolean {
    // Wrap static initialization blocks into ignored sections.
    if (methodName == "<clinit>")
        return true
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
    // Instrumentation of `java.util.Arrays` class causes some subtle flaky bugs.
    // See details in https://github.com/JetBrains/lincheck/issues/717.
    if (isJavaUtilArraysClass(className))
        return true
    // Do not instrument coroutines' internal machinery.
    if (isCoroutineInternalClass(className))
        return true

    return false
}