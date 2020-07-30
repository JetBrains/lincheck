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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.appendFailure
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.lang.StringBuilder


class SuspendExecutionReportingTest : VerifierState() {
    val lock = Mutex()
    var canEnterForbiddenSection: Boolean = false
    var canEnterStrangeSection: Boolean = false
    var counter: Int = 0

    @Operation(cancellableOnSuspension = false)
    suspend fun operation1() {
        if (canEnterStrangeSection) // to ensure certain execution ordering
            canEnterForbiddenSection = true
        lock.withLock {
            counter++
        }
        canEnterForbiddenSection = false
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun operation2(): Int {
        canEnterStrangeSection = true // to ensure certain execution ordering
        lock.withLock {
            counter++
        }
        if (canEnterForbiddenSection)
            return -1
        return 0
    }

    @Test
    fun test() {
        val options = ModelCheckingOptions()
                .actorsPerThread(1)
                .actorsBefore(0)
                .actorsAfter(0)
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "the test should fail" }
        val log = StringBuilder().appendFailure(failure).toString()
        println(log)
        check("label" !in log) { "suspend state machine related fields should not be reported" }
        check("L$0" !in log) { "suspend state machine related fields should not be reported" }
        check("operation1()".numberOfOccurencesIn(log) == 2) {
            "suspended function should be mentioned exactly twice (once in parallel and once in parallel execution)"
        }
        check("canEnterStrangeSection.READ: true" in log) { "this code location after suspension should be reported" }
        check("tryLock(null): false" in log) { "code locations that are related to suspension should be reported" }
    }

    override fun extractState(): Any = counter

    private fun String.numberOfOccurencesIn(text: String): Int {
        var result = 0
        for (i in text.indices) {
            if (i + length <= text.length && text.substring(i, i + length) == this)
                result++
        }
        return result
    }
}
