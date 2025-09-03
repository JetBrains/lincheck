/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.kotlinx.lincheck.transformation.transformers.*
import org.jetbrains.kotlinx.lincheck.trace.recorder.transformers.*

interface TransformationProfile {
    fun getMethodConfiguration(className: String, methodName: String, methodDescriptor: String): TransformationConfiguration?
}

class TransformationConfiguration(
    val trackObjectCreations: Boolean,

    val trackLocalVariableAccesses: Boolean,
    val trackSharedMemoryAccesses: Boolean,

    val trackMethodCalls: Boolean,
    val trackInlineMethodCalls: Boolean,

    val trackThreadsOperations: Boolean,
    val trackMonitorsOperations: Boolean,
    val trackWaitNotifyOperations: Boolean,
    val trackSynchronizedBlocks: Boolean,
    val trackParkingOperations: Boolean,

    val interceptInvokeDynamic: Boolean,

    // TODO: in the future, we may want to provide finer-grained control
    //   over which operations are tracked, for example:
    //   - track only shared reads `trackSharedReadAccesses` or writes `trackSharedWriteAccesses` (same for local vars),
    //   - control where tracking hooks are injected, for instance,
    //     before `injectBeforeSharedReadAccess` and/or `injectAfterSharedReadAccess`.
    //   - and other ...
)

internal fun TransformationConfiguration.shouldApplyTransformer(transformer: LincheckTransformer): Boolean {

    return when (transformer) {
        is ObjectCreationTransformer -> trackObjectCreations
        is ObjectCreationMinimalTransformer -> trackObjectCreations

        is LocalVariablesAccessTransformer -> trackLocalVariableAccesses
        is SharedMemoryAccessTransformer -> trackSharedMemoryAccesses

        is MethodCallTransformer -> trackMethodCalls
        is MethodCallMinimalTransformer -> trackMethodCalls
        is InlineMethodCallTransformer -> trackInlineMethodCalls

        is ThreadTransformer -> trackThreadsOperations
        is MonitorTransformer -> trackMonitorsOperations
        is WaitNotifyTransformer -> trackWaitNotifyOperations
        is SynchronizedMethodTransformer -> trackSynchronizedBlocks
        is ParkingTransformer -> trackParkingOperations

        is DeterministicInvokeDynamicTransformer -> interceptInvokeDynamic

        else -> TODO()
    }

}