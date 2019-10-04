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
package tests.custom.set

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ReentrantLockSet {
    private val set = mutableSetOf<Int>()
    private val lock = ReentrantLock()

    fun add(key: Int): Boolean = lock.withLock {
        set.add(key)
    }

    fun remove(key: Int): Boolean = lock.withLock {
        set.remove(key)
    }

    fun contains(key: Int): Boolean = lock.withLock {
        set.contains(key)
    }
}