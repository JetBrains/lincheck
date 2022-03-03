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

package org.jetbrains.kotlinx.lincheck.nvm.api

import org.jetbrains.kotlinx.lincheck.nvm.NVMStateHolder

/**
 * Create non-volatile integer.
 * @param value initial value
 */
fun nonVolatile(value: Int) = NonVolatileInt(value, state())

/**
 * Create non-volatile long.
 * @param value initial value
 */
fun nonVolatile(value: Long) = NonVolatileLong(value, state())

/**
 * Create non-volatile boolean.
 * @param value initial value
 */
fun nonVolatile(value: Boolean) = NonVolatileBoolean(value, state())


/**
 * Create non-volatile reference.
 * @param value initial value
 */
fun <T> nonVolatile(value: T) = NonVolatileRef(value, state())

/**
 * Get state or null in case of using outside Lincheck test.
 * Note that this class is reloaded by TransformationClassLoader.
 */
private fun state() = NVMStateHolder.state
