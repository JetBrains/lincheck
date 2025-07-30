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

fun List<ShadowStackFrame>.isCurrentStackFrameReceiver(obj: Any): Boolean =
    (obj === lastOrNull()?.instance)

fun ShadowStackFrame.findCurrentReceiverFieldReferringTo(obj: Any): FieldAccessLocation? {
    val field = instance?.findInstanceFieldReferringTo(obj)
    return field?.toAccessLocation()
}

fun ShadowStackFrame.findLocalVariableReferringTo(obj: Any): LocalVariableAccess? {
    // Filter out two patterns which are used as virtual `this` in inlined code.
    // They should not be used as "owner" of the call, as they are "hidden".
    // Otherwise, inline calls will be attributed to this variable, as the local variable has higher precedence
    // in resolving `owner` than other places, like fields.
    // When other "inlined" variables are converted to arguments to inlined functions, they will be
    // filtered out too.
    // Keep the current behavior of such variables for now.
    return localVariables
        .filter { (name, _) -> !name.startsWith("this_\$iv") }
        .filter { (name, _) -> !name.contains(Regex("^\\\$this\\$.+?(\\\$iv)+$")) }
        .filter { (_, state) -> state.value === obj }
        .maxByOrNull { (_, state) -> state.accessCounter }
        ?.key
        ?.let { LocalVariableAccess(it) }
}

fun ShadowStackFrame.findLocalVariableFieldReferringTo(obj: Any): OwnerName? {
    for ((varName, value) in getLocalVariables()) {
        if (value === null || value === instance /* do not return `this` */) continue
        val ownerName = LocalVariableAccess(varName).toOwnerName()
        val field = value.findInstanceFieldReferringTo(obj)
        if (field != null) {
            return ownerName + field.toAccessLocation()
        }
    }
    return null
}