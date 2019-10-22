package org.jetbrains.kotlinx.lincheck.tests.guava

/*
 * #%L
 * libtest
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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import com.google.common.collect.ConcurrentHashMultiset
import org.jetbrains.kotlinx.lincheck.tests.AbstractLincheckTest

@Param(name = "count", gen = IntGen::class, conf = "1:10")
class MultisetCorrect : AbstractLincheckTest(shouldFail = false, checkObstructionFreedom = false) {
    private val q = ConcurrentHashMultiset.create<Int>()

    @Operation
    fun add(value: Int, @Param(name = "count") count: Int): Int = q.add(value, count)

    @Operation
    fun remove(value: Int, @Param(name = "count") count: Int): Int = q.remove(value, count)

    override fun extractState(): Any = q
}
