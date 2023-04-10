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
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.junit.*
import java.lang.IllegalStateException

/**
 * This test checks that model checking strategy can find a many switch bug.
 */
class ManySwitchBugTest {
    private var canEnterSection1 = false
    private var canEnterSection2 = false
    private var canEnterSection3 = false
    private var canEnterSection4 = false
    private var canEnterSection5 = false

    @Operation
    fun foo() {
        canEnterSection1 = true
        canEnterSection1 = false
        if (canEnterSection2) {
            canEnterSection3 = true
            canEnterSection3 = false
            if (canEnterSection4) {
                canEnterSection5 = true
                canEnterSection5 = false
            }
        }
    }

    @Operation
    fun bar() {
        if (canEnterSection1) {
            canEnterSection2 = true
            canEnterSection2 = false
            if (canEnterSection3) {
                canEnterSection4 = true
                canEnterSection4 = false
                if (canEnterSection5)
                    throw IllegalStateException()
            }
        }
    }

    @Test
    fun test() {
        val failure = ModelCheckingOptions()
            .actorsAfter(0)
            .actorsBefore(0)
            .actorsPerThread(1)
            .checkImpl(this::class.java)
        check(failure is UnexpectedExceptionFailure) { "The test should fail" }
    }
}
