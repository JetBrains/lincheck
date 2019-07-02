/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
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
package org.jetbrains.kotlinx.lincheck.test.verifier.quantitative

import java.util.*
import kotlin.collections.ArrayList

class KStackSimulation<T>(val k: Int) {
    private val list = ArrayList<T>()

    private val random = Random(0)

    fun push(value: T) = synchronized(this) {
        list.add(0, value)
    }

    fun pushRelaxed(value: T) = synchronized(this) {
        val index = random.nextInt(k).coerceAtMost(list.size)
        list.add(index, value)
    }


    fun popOrNull(): T? = synchronized(this) {
        return if (list.isEmpty()) null else list.removeAt(0)
    }

    fun popOrNullRelaxed(): T? = synchronized(this) {
        if (list.isEmpty())
            return null
        val index = random.nextInt(k).coerceAtMost(list.size - 1)
        return list.removeAt(index)
    }
}
