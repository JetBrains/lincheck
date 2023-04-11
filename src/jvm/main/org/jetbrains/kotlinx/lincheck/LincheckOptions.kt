/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*
import kotlin.reflect.*

interface LincheckOptions {
    /**
     * The maximal amount of time in seconds dedicated to testing.
     */
    var testingTimeInSeconds: Long

    var maxThreads: Int
    var maxOperationsInThread: Int

    /**
     * The verifier class used to check consistency of the execution.
     */
    var verifier: Class<out Verifier>

    /**
     * The specified class defines the sequential behavior of the testing data structure;
     * it is used by [Verifier] to build a labeled transition system,
     * and should have the same methods as the testing data structure.
     *
     * By default, the provided concurrent implementation is used in a sequential way.
     */
    var sequentialImplementation: Class<*>?

    /**
     * Set to `true` to check the testing algorithm for obstruction-freedom.
     * It also extremely useful for lock-free and wait-free algorithms.
     */
    var checkObstructionFreedom: Boolean

    /**
     * Add the specified custom scenario additionally to the generated ones.
     */
    fun addCustomScenario(scenario: ExecutionScenario)

    /**
     * Runs the Lincheck test on the specified class.
     */
    fun check(testClass: Class<*>)
}

/**
 * Creates new instance of LincheckOptions class.
 */
fun LincheckOptions(): LincheckOptions = LincheckOptionsImpl()

fun LincheckOptions(configurationBlock: LincheckOptions.() -> Unit): LincheckOptions {
    val options = LincheckOptionsImpl()
    options.configurationBlock()
    return options
}

/**
 * Add the specified custom scenario additionally to the generated ones.
 */
fun LincheckOptions.addCustomScenario(scenarioBuilder: DSLScenarioBuilder.() -> Unit): Unit =
    addCustomScenario(scenario { scenarioBuilder() })

fun LincheckOptions.check(testClass: KClass<*>) = check(testClass.java)

// For internal tests only.
internal enum class LincheckMode {
    Stress, ModelChecking, Hybrid
}

internal class LincheckOptionsImpl : LincheckOptions {
    private val customScenarios = mutableListOf<ExecutionScenario>()

    override var testingTimeInSeconds: Long = DEFAULT_TESTING_TIME
    override var verifier: Class<out Verifier> = LinearizabilityVerifier::class.java
    override var sequentialImplementation: Class<*>? = null
    override var checkObstructionFreedom: Boolean = false

    internal var mode = LincheckMode.Hybrid
    internal var minimizeFailedScenario = true
    internal var invocationTimeoutMs: Long = CTestConfiguration.DEFAULT_TIMEOUT_MS
    internal var generateBeforeAndAfterParts = true

    override fun addCustomScenario(scenario: ExecutionScenario) {
        customScenarios.add(scenario)
    }

    override fun check(testClass: Class<*>) {
        checkImpl(testClass)?.let { throw LincheckAssertionError(it) }
    }

    fun checkImpl(testClass: Class<*>): LincheckFailure? {
        TODO()
    }
}

private const val DEFAULT_TESTING_TIME = 10L