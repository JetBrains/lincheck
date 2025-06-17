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

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger

private val EVENT_ID_GENERATOR = AtomicInteger(0)

@Serializable
sealed class TRTracePoint {
    abstract val codeLocationId: Int
    abstract val threadId: Int
    val eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
}

@Serializable
class TRMethodCallTracePoint(
    override val threadId: Int,
    override val codeLocationId: Int,
    val methodId: Int,
    val obj: TRObject?,
    val parameters: List<TRObject?>,
) : TRTracePoint() {
    var result: TRObject? = null
    var exceptionClassName: String? = null
    val events: MutableList<TRTracePoint> = mutableListOf()
}

@Serializable
class TRReadTracePoint(
    override val threadId: Int,
    override val codeLocationId: Int,
    val fieldId: Int,
    val obj: TRObject?,
    val value: TRObject?
) : TRTracePoint()

@Serializable
class TRWriteTracePoint(
    override val threadId: Int,
    override val codeLocationId: Int,
    val fieldId: Int,
    val obj: TRObject?,
    val value: TRObject?
) : TRTracePoint()

@Serializable
class TRReadLocalVariableTracePoint(
    override val threadId: Int,
    override val codeLocationId: Int,
    val localVariableId: Int,
    val value: TRObject?
) : TRTracePoint()

@Serializable
class TRWriteLocalVariableTracePoint(
    override val threadId: Int,
    override val codeLocationId: Int,
    val localVariableId: Int,
    val value: TRObject?
) : TRTracePoint()

@Serializable
class TRReadArrayTracePoint(
    override val threadId: Int,
    override val codeLocationId: Int,
    val array: TRObject?,
    val index: Int,
    val value: TRObject?
) : TRTracePoint()

@Serializable
class TRWriteArrayTracePoint(
    override val threadId: Int,
    override val codeLocationId: Int,
    val array: TRObject?,
    val index: Int,
    val value: TRObject?
) : TRTracePoint()

@Serializable
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