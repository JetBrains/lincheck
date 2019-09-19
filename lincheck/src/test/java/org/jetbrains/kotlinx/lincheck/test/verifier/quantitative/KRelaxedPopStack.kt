/*-
 * #%L
 * lincheck
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

class KRelaxedPopStack<T>(private val k: Int) {
    private val list = LinkedList<T>()
    private val random = Random()

    @Synchronized
    fun push(value: T) {
        list.push(value)
    }

    @Synchronized
    fun push1(value: T) {
        list.push(value)
    }

    @Synchronized
    fun push2(value: T) {
        list.push(value)
    }

    @Synchronized
    fun pop(): T? {
        if (list.isEmpty()) {
            return null
        }
        val index = random.nextInt(k + 1).coerceAtMost(list.size - 1)
        return list.removeAt(index)
    }

}
