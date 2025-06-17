/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.traceagent

import java.util.concurrent.atomic.AtomicInteger

private val EVENT_ID_GENERATOR = AtomicInteger(0)

sealed class TRTracePoint(
    val threadId: Int,
    val codeLocationId: Int,
) {
    val eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
}

class TRMethodCallTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val methodId: Int,
    val obj: TRObject?,
    val parameters: List<TRObject?>,
) : TRTracePoint(threadId, codeLocationId) {
    var result: TRObject? = null
    var exceptionClassName: String? = null
    val events: MutableList<TRTracePoint> = mutableListOf()
}

class TRReadTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val fieldId: Int,
    val obj: TRObject?,
    val value: TRObject?
) : TRTracePoint(threadId, codeLocationId)

class TRWriteTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val fieldId: Int,
    val obj: TRObject?,
    val value: TRObject?
) : TRTracePoint(threadId, codeLocationId)

class TRReadLocalVariableTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val localVariableId: Int,
    val value: TRObject?
) : TRTracePoint(threadId, codeLocationId)

class TRWriteLocalVariableTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val localVariableId: Int,
    val value: TRObject?
) : TRTracePoint(threadId, codeLocationId)

class TRReadArrayTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val array: TRObject?,
    val index: Int,
    val value: TRObject?
) : TRTracePoint(threadId, codeLocationId)

class TRWriteArrayTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val array: TRObject?,
    val index: Int,
    val value: TRObject?
) : TRTracePoint(threadId, codeLocationId)

class TRObject(
    val className: String,
    val hashCodeId: Int,
)

fun TRObject(obj: Any?): TRObject? =
    obj?.let { TRObject(it::class.java.name, System.identityHashCode(obj)) }

class Trace(
    val rootTraceNode: TRMethodCallTracePoint,
    val codeLocations: ArrayList<StackTraceElement>, // codeLocationId -> StackTraceElement
)