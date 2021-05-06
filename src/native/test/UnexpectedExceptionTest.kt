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

import org.jetbrains.kotlinx.lincheck.LincheckStressConfiguration
import org.jetbrains.kotlinx.lincheck.strategy.*
import kotlin.native.concurrent.*

class UnexpectedExceptionTest : AbstractLincheckStressTest<UnexpectedExceptionTest>(UnexpectedExceptionFailure::class) {
    private var canEnterForbiddenSection: AtomicInt = AtomicInt(0)

    fun operation1() {
        // atomic because of possible compile-time optimization
        canEnterForbiddenSection.value = 1
        canEnterForbiddenSection.value = 0
    }

    fun operation2() {
        check(canEnterForbiddenSection.value == 0)
    }

    override fun extractState(): Any = canEnterForbiddenSection.value

    override fun <T : LincheckStressConfiguration<UnexpectedExceptionTest>> T.customize() {
        iterations(100)
        invocationsPerIteration(100)

        initialState { UnexpectedExceptionTest() }

        operation(UnexpectedExceptionTest::operation1)
        operation(UnexpectedExceptionTest::operation2, "operation2", listOf(IllegalArgumentException::class))
    }
}