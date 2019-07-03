/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.annotations.*
import java.lang.reflect.Method
import kotlin.coroutines.Continuation

/**
 * The actor entity describe the operation with its parameters
 * which is executed during the testing.
 *
 * @see Operation
 */
data class Actor(
    val method: Method,
    val arguments: List<Any?>,
    val handledExceptions: List<Class<out Throwable>>
) {
    override fun toString() = method.name + arguments.joinToString(prefix = "(", postfix = ")", separator = ",") { it.toString() }

    val handlesExceptions = handledExceptions.isNotEmpty()

    val isSuspendable = method.isSuspendable()
}

fun Method.isSuspendable() = parameterTypes.isNotEmpty() && (kotlin.coroutines.Continuation::class.java).isAssignableFrom(parameterTypes.last())