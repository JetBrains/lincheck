package com.devexperts.dxlab.lincheck.tests

import com.devexperts.dxlab.lincheck.LinChecker
import com.devexperts.dxlab.lincheck.annotations.Operation
import com.devexperts.dxlab.lincheck.strategy.randomswitch.RandomSwitchCTest
import com.devexperts.dxlab.lincheck.strategy.stress.StressCTest
import org.junit.Test

@StressCTest
class DequeLinearizabilityTest {
    private val deque = LockFreeDeque<Int>();

/*-
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

    @Operation
    fun pushLeft(value: Int) = deque.pushLeft(value)

    @Operation
    fun pushRight(value: Int) = deque.pushRight(value)

    @Operation
    fun popLeft(): Int? = deque.popLeft()

    @Operation
    fun popRight(): Int? = deque.popRight()

    @Test
    fun test() = LinChecker.check(DequeLinearizabilityTest::class.java)
}
