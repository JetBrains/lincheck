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
import org.jetbrains.lincheck.util.ideaPluginEnabled
import org.jetbrains.lincheck.util.isThreadContainerThreadStartMethod

interface TransformationProfile {
    fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration
}

class TransformationConfiguration(
    var trackObjectCreations: Boolean = false,

    var trackLocalVariableAccesses: Boolean = false,
    var trackSharedMemoryAccesses: Boolean = false,

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
}

internal fun TransformationConfiguration.shouldApplyVisitor(visitorClass: Class<*>): Boolean {
    return when (visitorClass) {
        ObjectCreationTransformerBase::class.java -> trackObjectCreations

        LocalVariablesAccessTransformer::class.java -> trackLocalVariableAccesses
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

object StressTransformationProfile : TransformationProfile {
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

object TraceRecorderTransformationProfile : TransformationProfile {
    override fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration {
        val config = TransformationConfiguration()

        // TODO: handle `shouldWrapInIgnoredSection`
        // TODO: handle `shouldNotInstrument`

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
            trackLocalVariableAccesses = true
            trackSharedMemoryAccesses = true

            trackMethodCalls = true
            trackInlineMethodCalls = true

            trackThreadsOperations = true
        }
    }
}

object TraceDebuggerTransformationProfile : TransformationProfile {
    override fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration {
        val config = TransformationConfiguration()

        // TODO: handle `shouldWrapInIgnoredSection`
        // TODO: handle `shouldNotInstrument`

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
            trackLocalVariableAccesses = true
            trackSharedMemoryAccesses = true

            trackMethodCalls = true
            trackInlineMethodCalls = true

            trackThreadsOperations = true
            trackAllSynchronizationOperations = true
        }
    }
}

object ModelCheckingTransformationProfile : TransformationProfile {
    override fun getMethodConfiguration(className: String, methodName: String, descriptor: String): TransformationConfiguration {
        val config = TransformationConfiguration()

        // TODO: handle `shouldWrapInIgnoredSection`
        // TODO: handle `shouldNotInstrument`

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
                // config.interceptInvokeDynamic = true
            }
        }

        // Currently, constructors are treated in a special way to avoid problems
        // with `VerificationError` due to leaking this problem,
        // see: https://github.com/JetBrains/lincheck/issues/424
        if ((methodName == "<init>")) {
            return config.apply {
                trackObjectCreations = true
                trackSharedMemoryAccesses = true
            }
        }

        return config.apply {
            trackObjectCreations = true
            trackLocalVariableAccesses = true
            trackSharedMemoryAccesses = true

            trackMethodCalls = true
            trackInlineMethodCalls = true

            trackThreadsOperations = true
            trackAllSynchronizationOperations = true

            // In model checking mode we track all hash code calls in the instrumented code
            // and substitute them with a constant value.
            interceptIdentityHashCodes = true
        }
    }
}