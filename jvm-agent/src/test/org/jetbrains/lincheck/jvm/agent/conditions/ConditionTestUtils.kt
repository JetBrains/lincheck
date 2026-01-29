/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.conditions

import org.objectweb.asm.Type
import java.lang.reflect.*

/**
 * Shared utilities for condition safety testing.
 */
object ConditionTestUtils {

    /**
     * Represents a method discovered via reflection.
     */
    data class MethodInfo(
        val name: String,
        val descriptor: String
    ) {
        override fun toString(): String = name
    }

    /**
     * Discovers all static methods from a class using reflection.
     * Generates method descriptors using ASM's Type utility.
     * Excludes synthetic methods, property getters/setters, and internal Kotlin methods.
     */
    fun discoverTestMethods(clazz: Class<*>): Array<MethodInfo> {
        return clazz.declaredMethods
            .filter { method ->
                // Include only static methods
                Modifier.isStatic(method.modifiers) &&
                        // Exclude synthetic and bridge methods
                        !method.isSynthetic &&
                        !method.isBridge &&
                        // Exclude property getters/setters
                        !method.name.startsWith("get") &&
                        !method.name.startsWith("set") &&
                        // Exclude special Kotlin methods
                        !method.name.contains("$")
            }
            .map { method ->
                MethodInfo(
                    name = method.name,
                    descriptor = Type.getMethodDescriptor(method)
                )
            }
            .sortedBy { it.name }
            .toTypedArray()
    }
}
