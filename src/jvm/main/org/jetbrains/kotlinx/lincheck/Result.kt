/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.*
import java.io.Serializable

/**
 * Type of result used if the actor invocation returns any value.
 */
actual class ValueResult @JvmOverloads constructor(actual val value: Any?, override val wasSuspended: Boolean = false) : Result() {
    private val valueClassTransformed: Boolean get() = value?.javaClass?.classLoader is TransformationClassLoader
    private val serializedObject: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        check(value is Serializable) {
            "The result should either be a type always loaded by the system class loader " +
                "(e.g., Int, String, List<T>) or implement Serializable interface; " +
                "the actual class is ${value?.javaClass}."
        }
        if (!valueClassTransformed) {
            // The object is not transformed
            value.serialize()
        } else {
            // The object is not transformed and should be converted beforehand
            value.convertForLoader(LinChecker::class.java.classLoader).serialize()
        }
    }

    override fun toString() = wasSuspendedPrefix + "$value"

    actual override fun equals(other: Any?): Boolean {
        // Check that the classes are equal by names
        // since they can be loaded via different class loaders.
        if (javaClass.name != other?.javaClass?.name) return false
        other as ValueResult
        // Is `wasSuspended` flag the same?
        if (wasSuspended != other.wasSuspended) return false
        // When both value are not transformed, then compare them directly, otherwise serialize to compare
        return if (!valueClassTransformed && !other.valueClassTransformed) value == other.value
               else serializedObject.contentEquals(other.serializedObject)
    }

    actual override fun hashCode(): Int = if (wasSuspended) 0 else 1  // we cannot use the value here
}

// for byte-code generation
@JvmOverloads
fun createExceptionResult(tClazz: Class<out Throwable>, wasSuspended: Boolean = false) = ExceptionResult(tClazz.normalize().kotlin, false)