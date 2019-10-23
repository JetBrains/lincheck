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
package org.jetbrains.kotlinx.lincheck.tests.custom.counter

import java.util.concurrent.atomic.AtomicInteger

interface Counter {
    fun incrementAndGet(): Int

    fun get(): Int
}

internal class CounterWrong0 : Counter {
    private var c: Int = 0

    override fun incrementAndGet(): Int {
        return ++c
    }

    override fun get(): Int {
        return c
    }
}

internal class CounterWrong1 : Counter {
    private var c: Int = 0

    override fun incrementAndGet(): Int {
        c++
        return c
    }

    override fun get(): Int {
        return c
    }
}

internal class CounterWrong2 : Counter {
    @Volatile
    private var c: Int = 0

    override fun incrementAndGet(): Int {
        return ++c
    }

    override fun get(): Int {
        return c
    }
}

internal class CounterCorrect : Counter {
    private val c = AtomicInteger()

    @Synchronized
    override fun incrementAndGet(): Int {
        return c.incrementAndGet()
    }

    override fun get(): Int {
        return c.get()
    }
}

internal class CounterGet {
    private var c: Int = 0

    fun get(): Int {
        return c
    }

    @Synchronized
    fun incrementAndGet(): Int {
        return ++c
    }
}