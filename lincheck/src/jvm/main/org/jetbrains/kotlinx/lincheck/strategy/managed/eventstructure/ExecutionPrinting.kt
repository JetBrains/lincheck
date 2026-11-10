/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.util.MemoryOrdering
import java.util.IdentityHashMap

/**
 * Renders the current execution without replaying it or computing additional consistency witnesses.
 * Event references use thread positions; object names are local to this rendering.
 * Only recorded values and safe scalar object values are printed, never user-defined `toString` methods.
 */
internal fun ExtendedExecution.toExecutionGraph(
    memoryModel: MemoryModel,
    context: TraceContext,
    objectValue: (Int) -> Any? = { null },
): String = ExecutionPrinter(this, context, objectValue).render(memoryModel)

private class ExecutionPrinter(
    private val execution: ExtendedExecution,
    private val context: TraceContext,
    private val objectValue: (Int) -> Any?,
) {
    private val objectNames = linkedMapOf<Int, Int>()
    private val argumentNames = IdentityHashMap<Any, Int>()
    private val classNames = buildMap {
        execution.forEach { event ->
            when (val label = event.label) {
                is ObjectAllocationLabel -> put(label.objectID, label.className)
                is InitializationLabel -> label.objectsAllocations.forEach { (id, allocation) ->
                    put(id, allocation.className)
                }
                else -> Unit
            }
        }
    }

    fun render(memoryModel: MemoryModel): String = buildString {
        appendLine("Execution graph ($memoryModel):")
        appendLine("Events in each thread are in program order; Tn:position identifies an event.")
        appendLine("rf = reads-from; hb = latest observed event in each other thread (happens-before frontier).")
        for (tid in execution.threadIDs.sorted()) {
            appendLine("Thread $tid:")
            for (event in execution[tid].orEmpty()) {
                append("  ${event.ref()}: ${label(event.label)}")
                val access = event.label as? MemoryAccessLabel
                if (access != null && access.codeLocation >= 0) {
                    append(" at ${context.stackTrace(access.codeLocation)}")
                }
                appendLine()
                if (event.senders.isNotEmpty()) {
                    val relation = if (access is ReadAccessLabel) "rf" else "senders"
                    appendLine("    $relation: ${references(event.senders)}")
                }
                val hb = execution.threadIDs.sorted().filter { it != tid }.mapNotNull { other ->
                    execution[other, event.happensBeforeClock[other]]
                }
                if (hb.isNotEmpty()) appendLine("    hb: ${references(hb)}")
            }
        }
        appendLine("Coherence (adjacent atomic writes, including initialization):")
        if (!execution.coherenceOrder.computed || !execution.coherenceOrder.consistent) {
            appendLine("  No completed coherence witness for this execution prefix.")
            return@buildString
        }
        val locations = execution.memoryAccessEventIndex.locations.sortedBy(::location)
        for (loc in locations) {
            val writes = execution.coherenceOrder[loc]
            if (writes.isNotEmpty()) {
                appendLine("  ${location(loc)}: ${writes.joinToString(" -> ") { it.ref() }}")
            }
        }
        val fullCoherence = execution.fullCoherenceOrder
        if (fullCoherence != null && fullCoherence.computed && fullCoherence.consistent) {
            appendLine("Additional coherence edges involving plain writes:")
            for (loc in locations) {
                val writes = execution.memoryAccessEventIndex.getWrites(loc).sortedWith(
                    compareBy({ it.threadId }, { it.threadPosition })
                )
                val edges = writes.flatMap { from ->
                    writes.filter { to ->
                        (from.isPlainWrite() || to.isPlainWrite()) && fullCoherence(from, to)
                    }.map { to -> "${from.ref()} -> ${to.ref()}" }
                }
                if (edges.isNotEmpty()) appendLine("  ${location(loc)}: ${edges.joinToString()}")
            }
        }
    }

    private fun label(label: EventLabel): String = when (label) {
        is InitializationLabel -> "Init"
        is ObjectAllocationLabel -> "Alloc ${objectRef(label.objectID)}"
        is FreezeLabel -> "Freeze ${label.locations.map(::location).sorted().joinToString()}"
        is MemoryAccessLabel -> buildString {
            append("${label.accessKind}${label.kind.repr} ${label.memoryOrdering} ${location(label.location)}")
            if (label.location.isFinal) append(" final")
            if (label.isRead && !label.isRequest) append(" read=${value(label.location.type, label.readValue)}")
            if (label.isWrite) append(" write=${value(label.location.type, label.writeValue)}")
            label.readModifyWriteDescriptor?.let { append(" ${rmw(it, label.location.type)}") }
        }
        is FenceLabel -> "Fence ${label.memoryOrdering}"
        is ActorLabel -> "Actor ${label.spanKind} ${label.actor.method.name}" +
            label.actor.arguments.joinToString(prefix = "(", postfix = ")", transform = ::argument)
        is ThreadForkLabel -> "ThreadFork ${label.forkThreadIds.sorted()}"
        is ThreadStartLabel -> "ThreadStart${label.kind.repr} T${label.threadId}"
        is ThreadFinishLabel -> "ThreadFinish ${label.finishedThreadIds.sorted()}"
        is ThreadJoinLabel -> "ThreadJoin${label.kind.repr} ${label.joinThreadIds.sorted()}"
        is MutexLabel -> "${label.operationKind}${label.kind.repr} ${objectRef(label.mutexID)}"
        is ParkingEventLabel -> "${label.operationKind}${label.kind.repr} T${label.threadId}"
        is CoroutineLabel -> "${label.type}${label.kind.repr} T${label.threadId} actor=${label.actorId}"
        is RandomLabel -> "Random ${label.value}"
    }

    private fun location(location: MemoryLocation): String = when (location) {
        is StaticFieldMemoryLocation -> "${location.className}.${location.fieldName}"
        is ObjectFieldMemoryLocation -> {
            val owner = if (classNames[location.objID] == location.className) "" else "${location.className}."
            "${objectRef(location.objID)}.$owner${location.fieldName}"
        }
        is ArrayElementMemoryLocation -> "${objectRef(location.objID)}[${location.index}]"
        is AtomicPrimitiveMemoryLocation -> "${objectRef(location.objID)}.value"
        else -> "${location.javaClass.simpleName}(${objectRef(location.objID)})"
    }

    private fun value(type: Types.Type, value: Long): String = when (type) {
        Types.BOOLEAN_TYPE, Types.BOOLEAN_TYPE_BOXED -> (value != 0L).toString()
        Types.CHAR_TYPE, Types.CHAR_TYPE_BOXED -> "'${escape(value.toInt().toChar().toString())}'"
        Types.BYTE_TYPE, Types.BYTE_TYPE_BOXED, Types.SHORT_TYPE, Types.SHORT_TYPE_BOXED,
        Types.INT_TYPE, Types.INT_TYPE_BOXED, Types.LONG_TYPE, Types.LONG_TYPE_BOXED -> value.toString()
        // The event-structure tracker represents other values, including floating-point values, as object IDs.
        else -> objectRef(value.toInt())
    }

    private fun objectRef(id: Int): String {
        if (id == NULL_OBJECT_NUMBER) return "null"
        val number = objectNames.getOrPut(id) { objectNames.size + 1 }
        val name = classNames[id]?.substringBefore('/')?.substringAfterLast('.') ?: "Object"
        val scalar = scalar(objectValue(id))
        return "$name#$number" + if (scalar == null) "" else "($scalar)"
    }

    private fun argument(value: Any?): String = when (value) {
        null -> "null"
        else -> scalar(value) ?: "${value.javaClass.simpleName.substringBefore('/')}" +
            "@argument${argumentNames.getOrPut(value) { argumentNames.size + 1 }}"
    }

    private fun scalar(value: Any?): String? = when (value) {
        is String -> "\"${escape(value)}\""
        is Char -> "'${escape(value.toString())}'"
        is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value.toString()
        else -> null
    }

    private fun rmw(descriptor: ReadModifyWriteDescriptor, type: Types.Type): String = when (descriptor) {
        is ReadModifyWriteDescriptor.GetAndSetDescriptor -> "getAndSet(${value(type, descriptor.newValue)})"
        is ReadModifyWriteDescriptor.CompareAndSetDescriptor ->
            "compareAndSet(${value(type, descriptor.expectedValue)}, ${value(type, descriptor.newValue)})"
        is ReadModifyWriteDescriptor.CompareAndExchangeDescriptor ->
            "compareAndExchange(${value(type, descriptor.expectedValue)}, ${value(type, descriptor.newValue)})"
        is ReadModifyWriteDescriptor.FetchAndAddDescriptor -> "fetchAndAdd(${value(type, descriptor.delta)}, ${descriptor.kind})"
    }

    private fun references(events: List<ThreadEvent>): String =
        events.sortedWith(compareBy({ it.threadId }, { it.threadPosition })).joinToString { it.ref() }

    private fun ThreadEvent.ref(): String = "T$threadId:$threadPosition"

    private fun ThreadEvent.isPlainWrite(): Boolean =
        (label as? WriteAccessLabel)?.memoryOrdering == MemoryOrdering.PLAIN

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            append(when (char) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\'' -> "\\'"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> if (char.isISOControl()) "\\u${char.code.toString(16).padStart(4, '0')}" else char
            })
        }
    }
}
