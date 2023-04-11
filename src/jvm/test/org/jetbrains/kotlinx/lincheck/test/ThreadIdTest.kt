/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
  package org.jetbrains.kotlinx.lincheck.test

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.*

class ThreadIdTest : AbstractLincheckTest() {
    private val balances = IntArray(5)
    private val counter = atomic(0)

    @Operation
    fun inc(@Param(gen = ThreadIdGen::class) threadId: Int): Int = counter.incrementAndGet()
        .also { balances[threadId]++ }

    @Operation
    fun decIfNotNegative(@Param(gen = ThreadIdGen::class) threadId: Int) {
        if (balances[threadId] == 0) return
        balances[threadId]--
        val c = counter.decrementAndGet()
        if (c < 0) error("The counter cannot be negative")
    }

}
