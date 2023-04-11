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
package org.jetbrains.kotlinx.lincheck.strategy.stress

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*

@Suppress("DEPRECATION_ERROR")
internal class StressStrategy(
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?,
    timeoutMs: Long,
) : Strategy(scenario) {
    private val runner: Runner

    init {
        runner = ParallelThreadsRunner(
            strategy = this,
            testClass = testClass,
            validationFunctions = validationFunctions,
            stateRepresentationFunction = stateRepresentationFunction,
            timeoutMs = timeoutMs,
            useClocks = UseClocks.RANDOM
        )
        try {
            runner.initialize()
        } catch (t: Throwable) {
            runner.close()
            throw t
        }
    }

    override fun runInvocation(): InvocationResult {
        return runner.run()
    }

    override fun InvocationResult.tryCollectTrace(): Trace? = null

    override fun close() {
        runner.close()
    }
}