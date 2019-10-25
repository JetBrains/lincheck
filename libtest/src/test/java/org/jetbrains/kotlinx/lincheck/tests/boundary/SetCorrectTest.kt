package org.jetbrains.kotlinx.lincheck.tests.boundary

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
import org.cliffc.high_scale_lib.NonBlockingHashSet
import org.jetbrains.kotlinx.lincheck.ErrorType
import org.jetbrains.kotlinx.lincheck.tests.AbstractLinCheckTest

@Param(name = "key", gen = IntGen::class)
class SetCorrectTest : AbstractLinCheckTest(expectedError = ErrorType.NO_ERROR) {
    private val q = NonBlockingHashSet<Int>()

    @Operation(params = ["key"])
    fun add(key: Int) = q.add(key)

    @Operation(params = ["key"])
    fun remove(key: Int) = q.remove(key)

    override fun extractState(): Any = q
}
