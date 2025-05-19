/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.util.allDeclaredFieldWithSuperclasses
import org.jetbrains.kotlinx.lincheck.util.readFieldSafely

/**
 * Represents a shadow stack frame used to reflect the program's stack in [ManagedStrategy].
 *
 * @property instance the object on which the method was invoked, null in the case of a static method.
  */
internal class ShadowStackFrame(val instance: Any?) {
    private val localVariables: MutableMap<String, LocalVariableState> = mutableMapOf()

    private var accessCounter: Int = 0

    private data class LocalVariableState(
        val value: Any?,
        val accessCounter: Int,
    )

    fun getLocalVariables(): List<Pair<String, Any?>> =
        localVariables.map { (name, state) -> name to state.value }

    fun getLocalVariable(name: String): Any? {
        return localVariables[name]
    }

    fun setLocalVariable(name: String, value: Any?) {
        localVariables[name] = LocalVariableState(value, accessCounter++)
    }

    fun getLastAccessVariable(value: Any?): String? {
        return localVariables
            .filter { (_, state) -> state.value === value }
            .maxByOrNull { (_, state) -> state.accessCounter }
            ?.key
    }

    /**
     * Finds the field name of the instance object (current `this`)
     * in the current stack frame that directly references the given object.
     *
     * @param obj the target object to search for in instance fields of the current stack frame.
     * @return the name of the field that references the given object, or null if no such field is found.
     */
    fun findInstanceFieldNameReferringTo(obj: Any): String? {
        val currentThis = this.instance
        currentThis?.javaClass?.allDeclaredFieldWithSuperclasses?.forEach { field ->
            if (readFieldSafely(currentThis, field).getOrNull() === obj) {
                return field.name
            }
        }
        return null
    }
}

/**
 * Finds the field name of the instance object (current `this`)
 * in the current stack frame that directly references the given object.
 *
 * @param obj the target object to search for in instance fields of the current stack frame.
 * @return the name of the field that references the given object, or null if no such field is found.
 */
fun Any.findInstanceFieldNameReferringTo(obj: Any): String? {
    this.javaClass.allDeclaredFieldWithSuperclasses.forEach { field ->
        if (readFieldSafely(this, field).getOrNull() === obj) {
            return field.name
        }
    }
    return null
}