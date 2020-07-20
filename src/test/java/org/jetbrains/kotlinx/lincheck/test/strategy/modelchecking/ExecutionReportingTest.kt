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
import org.jetbrains.kotlinx.lincheck.appendFailure
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

class ExecutionReportingTest : VerifierState() {
    @Volatile
    var a = 0
    @Volatile
    var b = 0
    @Volatile
    var canEnterForbiddenSection = false

    @Operation
    fun operation1(): Int {
        if (canEnterForbiddenSection) {
            return 1
        }
        return 0
    }

    @Operation
    fun operation2() {
        b++
        uselessIncrements()
        intermediateMethod()
    }

    fun intermediateMethod() {
        resetFlag()
    }

    fun resetFlag() {
        canEnterForbiddenSection = true
        canEnterForbiddenSection = false
    }

    fun uselessIncrements() {
        b++
        b++
    }

    @Test
    fun test() {
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "test should fail" }
        val log = StringBuilder().appendFailure(failure).toString()
        check("resetFlag" in log)
        check("operation1" in log)
        check("operation2" in log)
        check("\"uselessIncrements\" at" in log) { "increments in uselessIncrements method should be compressed" }
    }

    companion object {
        val options = ModelCheckingOptions().actorsAfter(0).actorsBefore(0).actorsPerThread(1)
    }

    override fun extractState() = "$a $b $canEnterForbiddenSection"
}
