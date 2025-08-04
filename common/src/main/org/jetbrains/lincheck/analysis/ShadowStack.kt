/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.analysis

import org.jetbrains.lincheck.descriptors.*
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.util.*

/**
 * Represents a shadow stack frame used to reflect the program's stack in [org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy].
 *
 * @property instance the object on which the method was invoked, null in the case of a static method.
  */
class ShadowStackFrame(val instance: Any?) {
    private val _localVariables: MutableMap<String, LocalVariableState> = mutableMapOf()
    val localVariables: Map<String, LocalVariableState> get() = _localVariables

    private var accessCounter: Int = 0

    data class LocalVariableState(
        val value: Any?,
        val accessCounter: Int,
    )

    fun getLocalVariables(): List<Pair<String, Any?>> =
        _localVariables.map { (name, state) -> name to state.value }

    fun getLocalVariable(name: String): Any? {
        return _localVariables[name]
    }

    fun setLocalVariable(name: String, value: Any?) {
        _localVariables[name] = LocalVariableState(value, accessCounter++)
    }
}

fun ShadowStackFrame.isCurrentStackFrameReceiver(obj: Any): Boolean =
    (obj === instance)

fun ShadowStackFrame.findCurrentReceiverFieldReferringTo(obj: Any): FieldAccessLocation? {
    val field = instance?.findInstanceFieldReferringTo(obj)
    return field?.toAccessLocation()
}

fun ShadowStackFrame.findLocalVariableReferringTo(obj: Any): LocalVariableAccessLocation? {
    return localVariables
        .filter { (name, state) -> (state.value === obj) && !isInlineThisIVName(name) }
        .maxByOrNull { (_, state) -> state.accessCounter }
        ?.let {
            val descriptor = TRACE_CONTEXT.getVariableDescriptor(it.key)
            LocalVariableAccessLocation(descriptor)
        }
}

fun ShadowStackFrame.findLocalVariableFieldReferringTo(obj: Any): OwnerName? {
    for ((varName, value) in getLocalVariables()) {
        if (value === null || value === instance /* do not return `this` */) continue
        val descriptor = TRACE_CONTEXT.getVariableDescriptor(varName)
        val ownerName = LocalVariableAccessLocation(descriptor).toOwnerName()
        val field = value.findInstanceFieldReferringTo(obj)
        if (field != null) {
            return ownerName + field.toAccessLocation()
        }
    }
    return null
}