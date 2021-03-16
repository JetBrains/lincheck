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

/**
 * The actor entity describe the operation with its parameters
 * which is executed during the testing.
 *
 * @see Operation
 */
actual class Actor(
    val function: (Any, List<Any?>) -> Any?, // (instance, arguments) -> result
    val arguments: List<Any?>,
    actual val isSuspendable: Boolean = false,
    actual val allowExtraSuspension: Boolean = false,
    actual val promptCancellation: Boolean = false,
    actual val cancelOnSuspension: Boolean = false
) {

    actual override fun toString(): String =
        function.toString() +
        arguments.joinToString(prefix = "(", postfix = ")", separator = ", ") { it.toString() } +
        (if (cancelOnSuspension) " + " else "") +
        (if (promptCancellation) "prompt_" else "") +
        (if (cancelOnSuspension) "cancel" else "")
}

actual val Actor.isQuiescentConsistent: Boolean
    get() = false // TODO check