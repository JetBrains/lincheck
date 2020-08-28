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
package org.jetbrains.kotlinx.lincheck.test.strategy.modelchecking

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.appendFailure
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.forClasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.lang.IllegalStateException
import java.lang.StringBuilder

open class StateReportingTest : VerifierState() {
    @Volatile
    var a = 0

    @Operation
    fun operation(): Int {
        ++a
        return ++a
    }

    override fun extractState(): Any = a

    @StateRepresentation
    fun stateRepresentation() = a.toString()

    @Test
    fun test() {
        val options = ModelCheckingOptions()
                .actorsPerThread(1)
                .actorsBefore(0)
                .actorsAfter(0)
                .addGuarantee(forClasses(this::class.java.name)
                .methods("inc").treatAsAtomic())
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "the test should fail" }
        val log = StringBuilder().appendFailure(failure).toString()
        check("STATE: 1" in log)
        check("STATE: 2" in log)
    }
}

class StateRepresentationInParentClassTest : StateReportingTest()

class TwoStateRepresentationFunctionsTest : VerifierState() {
    @Volatile
    var a = 0

    @Operation
    fun operation(): Int {
        ++a
        return inc()
    }

    private fun inc(): Int = ++a

    override fun extractState(): Any = a


    @StateRepresentation
    fun stateRepresentation1() = a.toString()

    @StateRepresentation
    fun stateRepresentation2() = a.toString()

    @Test(expected = IllegalStateException::class)
    fun test() {
        ModelCheckingOptions()
            .actorsPerThread(1)
            .actorsBefore(0)
            .actorsAfter(0)
            .checkImpl(this::class.java)
    }
}
