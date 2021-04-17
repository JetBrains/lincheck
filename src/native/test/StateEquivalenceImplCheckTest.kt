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

import org.jetbrains.kotlinx.lincheck.*
import kotlin.native.concurrent.*
import kotlin.test.*

class StateEquivalenceImplCheckTest {
    private var i = AtomicInt(0)

    fun incAndGet() = i.addAndGet(1)

    @Test
    fun test() {
        try {
            LincheckStressConfiguration<StateEquivalenceImplCheckTest>("StateEquivalenceImplCheckTest").apply {
                initialState { StateEquivalenceImplCheckTest() }
                invocationsPerIteration(500)

                operation(StateEquivalenceImplCheckTest::incAndGet)
            }.runTest()
        } catch(e: IllegalStateException) {
            // Check that IllegalStateException is thrown if `requireStateEquivalenceImplCheck` option is true by default
            // and hashCode/equals methods are not implemented
        }
    }
}
