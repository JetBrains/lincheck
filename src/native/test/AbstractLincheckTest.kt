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
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.native.*
import kotlin.reflect.*
import kotlin.test.*

abstract class AbstractLincheckStressTest<TestClass>(
    private vararg val expectedFailures: KClass<out LincheckFailure>
) : VerifierState() {
    open fun <T: LincheckStressConfiguration<TestClass>> T.customize() {}
    override fun extractState(): Any = this.identityHashCode()

    private fun <T: LincheckStressConfiguration<I>, I> T.runInternalTest() {
        val failure: LincheckFailure? = checkImpl()
        if (failure === null) {
            assert(expectedFailures.isEmpty()) {
                "This test should fail, but no error has been occurred (see the logs for details)"
            }
        } else {
            failure.trace?.let { checkTraceHasNoLincheckEvents(it.toString()) }
            assert(expectedFailures.contains(failure::class)) {
                "This test has failed with an unexpected error: \n $failure"
            }
        }
    }

    @Test
    fun testWithStressStrategy(): Unit = LincheckStressConfiguration<TestClass>().run {
        commonConfiguration()
        runInternalTest()
    }
/*
    @Test
    fun testWithModelCheckingStrategy(): Unit = ModelCheckingOptions().run {
        commonConfiguration()
        runInternalTest()
    }
*/
    private fun <T: LincheckStressConfiguration<TestClass>> T.commonConfiguration(): Unit = run {
        iterations(100)
        invocationsPerIteration(500)
        actorsBefore(3)
        threads(3)
        actorsPerThread(4)
        actorsAfter(3)
        minimizeFailedScenario(false)
        customize()
    }
}


fun checkTraceHasNoLincheckEvents(trace: String) {
    val testPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck.test.").size - 1
    val lincheckPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck.").size - 1
    check(testPackageOccurrences == lincheckPackageOccurrences) { "Internal Lincheck events were found in the trace" }
}
