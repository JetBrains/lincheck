package org.jetbrains.kotlinx.lincheck.tests.custom.counter

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
import org.jetbrains.kotlinx.lincheck.tests.AbstractLincheckTest
import tests.custom.counter.Counter
import tests.custom.counter.CounterWrong1

class CounterTest3 : AbstractLincheckTest(true, false) {

    private val counter = CounterWrong1()

    @Operation
    fun incAndGet(): Int {
        return counter.incrementAndGet()
    }

    override fun extractState(): Any {
        return counter.get()
    }
}
