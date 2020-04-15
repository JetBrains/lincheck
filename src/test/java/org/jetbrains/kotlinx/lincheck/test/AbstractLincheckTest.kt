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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import kotlin.reflect.*

abstract class AbstractLincheckTest(
    private vararg val expectedErrors: KClass<out FailedIteration>
) : VerifierState() {
    open fun <O: Options<O, *>> O.customize() {}
    override fun extractState(): Any = System.identityHashCode(this)

    private fun <O : Options<O, *>> O.runInternalTest() {
        val failedIteration: FailedIteration? = checkImpl(this@AbstractLincheckTest::class.java)
        if (failedIteration === null) {
            assert(expectedErrors.isEmpty()) {
                "This test should fail, but no error has been occurred (see the logs for details)"
            }
        } else {
            assert(expectedErrors.contains(failedIteration::class)) {
                "This test has been failed with an unexpected error: \n $failedIteration"
            }
        }
    }

    @Test(timeout = 100_000)
    fun testWithStressStrategy(): Unit = StressOptions().run {
        invocationsPerIteration(10_000)
        iterations(30)
        minimizeFailedScenario(false)
        customize()
        runInternalTest()
    }
}
