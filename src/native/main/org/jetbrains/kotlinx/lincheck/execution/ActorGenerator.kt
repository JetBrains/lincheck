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

package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import kotlin.reflect.KClass

actual class ActorGenerator(
    val function: (Any, List<Any?>) -> Any?, // (instance, arguments) -> result
    val parameterGenerators: List<ParameterGenerator<*>>,
    val functionName: String = function.toString(),
    actual val useOnce: Boolean = false,
    actual val isSuspendable: Boolean = false,
    actual val handledExceptions: List<KClass<out Throwable>>
) {
    actual override fun toString(): String = functionName

    actual fun generate(threadId: Int): Actor {
        val arguments = parameterGenerators.map { it.generate() }.map { if (it === THREAD_ID_TOKEN) threadId else it }
        return Actor(
            function = function,
            arguments = arguments,
            functionName = functionName,
            isSuspendable = isSuspendable,
            handledExceptions = handledExceptions
        )
    }
}