/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import kotlin.coroutines.Continuation

internal val objectNumeration = mutableMapOf<Class<Any>, MutableMap<Any, Int>>()

/**
 * While code locations just define certain bytecode instructions,
 * code points correspond to visits of these bytecode instructions.
 */
internal sealed class CodePoint {
    protected abstract fun toStringImpl(): String
    override fun toString(): String = toStringImpl()
}

internal class ReadCodePoint(private val fieldName: String?, private val stackTraceElement: StackTraceElement) : CodePoint() {
    private var value: Any? = null

    override fun toStringImpl(): String = StringBuilder().apply {
        if (fieldName != null)
            append("$fieldName.")
        append("READ")
        append(": ${adornedStringRepresentation(value)}")
        append(" at ${stackTraceElement.shorten()}")
    }.toString()

    fun initializeReadValue(value: Any?) {
        this.value = value
    }
}

internal class WriteCodePoint(private val fieldName: String?, private val stackTraceElement: StackTraceElement) : CodePoint() {
    private var value: Any? = null

    override fun toStringImpl(): String  = StringBuilder().apply {
        if (fieldName != null)
            append("$fieldName.")
        append("WRITE(")
        append(adornedStringRepresentation(value))
        append(") at ${stackTraceElement.shorten()}")
    }.toString()

    fun initializeWrittenValue(value: Any?) {
        this.value = value
    }
}

internal class MethodCallCodePoint(private val methodName: String, private val stackTraceElement: StackTraceElement) : CodePoint() {
    var returnedValue: ValueHolder? = null
    private var parameters: Array<Any?>? = null
    private var ownerName: String? = null

    override fun toStringImpl(): String = StringBuilder().apply {
        if (ownerName != null)
            append("$ownerName.")
        append("$methodName(")
        if (parameters != null)
            append(parameters!!.joinToString(",", transform = ::adornedStringRepresentation))
        append(")")
        if (returnedValue != null)
            append(": ${adornedStringRepresentation(returnedValue!!.value)}")
        append(" at ${stackTraceElement.shorten()}")
    }.toString()

    fun initializeReturnedValue(value: Any?) {
        this.returnedValue = ValueHolder(value)
    }

    fun initializeParameters(parameters: Array<Any?>) {
        this.parameters = parameters
    }

    fun initializeOwnerName(ownerName: String?) {
        this.ownerName = ownerName
    }

    /**
     * This class is used to differentiate the cases of null value and no value (void).
     */
    internal class ValueHolder(val value: Any?)
}

internal class MonitorEnterCodePoint(private val stackTraceElement: StackTraceElement) : CodePoint() {
    override fun toStringImpl(): String = "MONITOR ENTER at " + stackTraceElement.shorten()
}

internal class MonitorExitCodePoint(private val stackTraceElement: StackTraceElement) : CodePoint() {
    override fun toStringImpl(): String = "MONITOR EXIT at " + stackTraceElement.shorten()
}

internal class WaitCodePoint(private val stackTraceElement: StackTraceElement) : CodePoint() {
    override fun toStringImpl(): String = "WAIT at " + stackTraceElement.shorten()
}

internal class NotifyCodePoint(private val stackTraceElement: StackTraceElement) : CodePoint() {
    override fun toStringImpl(): String = "NOTIFY at " + stackTraceElement.shorten()
}

internal class ParkCodePoint(private val stackTraceElement: StackTraceElement) : CodePoint() {
    override fun toStringImpl(): String = "PARK at " + stackTraceElement.shorten()
}

internal class UnparkCodePoint(private val stackTraceElement: StackTraceElement) : CodePoint() {
    override fun toStringImpl(): String = "UNPARK at " + stackTraceElement.shorten()
}

/**
 * Removes info about package in a stack trace element representation
 */
private fun StackTraceElement.shorten(): String {
    val stackTraceElement = this.toString()
    for (i in stackTraceElement.indices.reversed())
        if (stackTraceElement[i] == '/')
            return stackTraceElement.substring(i + 1 until stackTraceElement.length)
    return stackTraceElement
}

private fun adornedStringRepresentation(any: Any?): String {
    // primitive types are immutable and have trivial toString,
    // so their string representation is used
    if (any == null || any.javaClass.isPrimitiveWrapper())
        return any.toString()
    // simplified representation for Continuations
    if (any is Continuation<*>)
        return "<cont>"
    // instead of java.util.HashMap$Node@3e2a56 show Node@1
    val id = getId(any.javaClass, any)
    return "${any.javaClass.simpleName}@$id"
}

private fun getId(clazz: Class<Any>, obj: Any): Int =
    objectNumeration
        .computeIfAbsent(clazz) { mutableMapOf() }
        .computeIfAbsent(obj) { 1 + objectNumeration[clazz]!!.size }

private fun Class<out Any>?.isPrimitiveWrapper() =
    this in listOf(
        java.lang.Integer::class.java,
        java.lang.Long::class.java,
        java.lang.Short::class.java,
        java.lang.Double::class.java,
        java.lang.Float::class.java,
        java.lang.Character::class.java,
        java.lang.Byte::class.java,
        java.lang.Boolean::class.java,
        java.lang.String::class.java
    )