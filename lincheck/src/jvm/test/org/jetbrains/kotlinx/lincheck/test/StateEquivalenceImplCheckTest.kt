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
package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.junit.Test
import java.lang.IllegalStateException
import java.util.concurrent.atomic.AtomicInteger

@StressCTest(requireStateEquivalenceImplCheck = true)
class StateEquivalenceImplCheckTest {
    private var i = AtomicInteger(0)

    @Operation
    fun incAndGet() = i.incrementAndGet()

    // Check that IllegalStateException is thrown if `requireStateEquivalenceImplCheck` option is true by default
    // and hashCode/equals methods are not implemented
    @Test(expected = IllegalStateException::class)
    fun test() = LinChecker.check(this::class.java)
}
