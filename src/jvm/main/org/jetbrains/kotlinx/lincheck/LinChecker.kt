/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.transformation.withLincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.reflect.*

/**
 * This class runs concurrent tests.
 */
class LinChecker (private val testClass: Class<*>, options: Options<*, *>?) {
    private val testStructure = CTestStructure.getFromTestClass(testClass)
    private val testConfigurations: List<CTestConfiguration>
    private val reporter: Reporter

    init {
        val logLevel = options?.logLevel ?: testClass.getAnnotation(LogLevel::class.java)?.value ?: DEFAULT_LOG_LEVEL
        reporter = Reporter(logLevel)
        testConfigurations = if (options != null) listOf(options.createTestConfigurations(testClass))
                             else createFromTestClassAnnotations(testClass)
        // Currently, we extract validation functions from testClass structure, so for custom scenarios declared
        // with DSL, we have to set up it when testClass is scanned
        testConfigurations.forEach { cTestConfiguration ->
            cTestConfiguration.customScenarios.forEach { it.validationFunction = testStructure.validationFunction }
        }
    }

    /**
     * @throws LincheckAssertionError if the testing data structure is incorrect.
     */
    fun check() {
        val failure = checkImpl() ?: return
        throw LincheckAssertionError(failure)
    }

    /**
     * @return TestReport with information about concurrent test run.
     */
    @Synchronized // never run Lincheck tests in parallel
    internal fun checkImpl(): LincheckFailure? {
        check(testConfigurations.isNotEmpty()) { "No Lincheck test configuration to run" }
        lincheckVerificationStarted()
        for (testCfg in testConfigurations) {
            withLincheckJavaAgent(testCfg.instrumentationMode) {
                val failure = testCfg.checkImpl()
                if (failure != null) return failure
            }
        }
        return null
    }

    private fun CTestConfiguration.checkImpl(): LincheckFailure? {
        val exGen = createExecutionGenerator(testStructure.randomProvider)
        for (i in customScenarios.indices) {
            val verifier = createVerifier()
            val scenario = customScenarios[i]
            scenario.validate()
            reporter.logIteration(i + 1, customScenarios.size, scenario)
            val failure = scenario.run(this, verifier)
            if (failure != null) {
                runReplayForPlugin(failure, verifier)
                return failure
            }
        }
        var verifier = createVerifier()
        repeat(iterations) { i ->
            // For performance reasons, verifier re-uses LTS from previous iterations.
            // This behaviour is similar to a memory leak and can potentially cause OutOfMemoryError.
            // This is why we periodically create a new verifier to still have increased performance
            // from re-using LTS and limit the size of potential memory leak.
            // https://github.com/Kotlin/kotlinx-lincheck/issues/124
            if ((i + 1) % VERIFIER_REFRESH_CYCLE == 0)
                verifier = createVerifier()
            val scenario = exGen.nextExecution()
            scenario.validate()
            reporter.logIteration(i + 1 + customScenarios.size, iterations, scenario)
            val failure = scenario.run(this, verifier)
            if (failure != null) {
                val minimizedFailedIteration = if (!minimizeFailedScenario) failure else failure.minimize(this)
                reporter.logFailedIteration(minimizedFailedIteration)
                runReplayForPlugin(minimizedFailedIteration, verifier)
                return minimizedFailedIteration
            }
            // Reset the parameter generator ranges to start with the same initial bounds on each scenario generation.
            testStructure.parameterGenerators.forEach { it.reset() }
        }
        return null
    }

    /**
     * Enables replay mode and re-runs the failed scenario if Lincheck IDEA plugin is enabled.
     * We cannot initiate the failed interleaving replaying in the strategy code,
     * as the failing scenario might need to be minimized first.
     */
    private fun CTestConfiguration.runReplayForPlugin(failure: LincheckFailure, verifier: Verifier) {
        if (ideaPluginEnabled() && this is ModelCheckingCTestConfiguration) {
            reporter.logFailedIteration(failure, loggingLevel = LoggingLevel.WARN)
            enableReplayModeForIdeaPlugin()
            failure.scenario.run(this, verifier)
        } else {
            reporter.logFailedIteration(failure)
        }
    }

    // Tries to minimize the specified failing scenario to make the error easier to understand.
    // The algorithm is greedy: it tries to remove one actor from the scenario and checks
    // whether a test with the modified one fails with error as well. If it fails,
    // then the scenario has been successfully minimized, and the algorithm tries to minimize it again, recursively.
    // Otherwise, if no actor can be removed so that the generated test fails, the minimization is completed.
    // Thus, the algorithm works in the linear time of the total number of actors.
    private fun LincheckFailure.minimize(testCfg: CTestConfiguration): LincheckFailure {
        reporter.logScenarioMinimization(scenario)
        var minimizedFailure = this
        while (true) {
            minimizedFailure = minimizedFailure.scenario.tryMinimize(testCfg) ?: break
        }
        return minimizedFailure
    }

    private fun ExecutionScenario.tryMinimize(testCfg: CTestConfiguration): LincheckFailure? {
        // Reversed indices to avoid conflicts with in-loop removals
        for (i in threads.indices.reversed()) {
            for (j in threads[i].indices.reversed()) {
                tryMinimize(i, j)
                    ?.run(testCfg, testCfg.createVerifier())
                    ?.let { return it }
            }
        }
        return null
    }

    private fun ExecutionScenario.run(testCfg: CTestConfiguration, verifier: Verifier): LincheckFailure? =
        testCfg.createStrategy(
            testClass = testClass,
            scenario = this,
            validationFunction = testStructure.validationFunction,
            stateRepresentationMethod = testStructure.stateRepresentation,
            verifier = verifier
        ).run()

    private fun CTestConfiguration.createVerifier() =
        verifierClass.getConstructor(Class::class.java).newInstance(sequentialSpecification)

    private fun CTestConfiguration.createExecutionGenerator(randomProvider: RandomProvider) =
        generatorClass.getConstructor(
            CTestConfiguration::class.java,
            CTestStructure::class.java,
            RandomProvider::class.java
        ).newInstance(this, testStructure, randomProvider)

    // This companion object is used for backwards compatibility.
    companion object {
        /**
         * Runs the specified concurrent tests. If [options] is null, the provided on
         * the testing class `@...CTest` annotations are used to specify the test parameters.
         *
         * @throws AssertionError if any of the tests fails.
         */
        @JvmOverloads
        @JvmStatic
        fun check(testClass: Class<*>, options: Options<*, *>? = null) {
            LinChecker(testClass, options).check()
        }

        private const val VERIFIER_REFRESH_CYCLE = 100
    }
}


/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass, options)
 * ```
 */
fun <O : Options<O, *>> O.check(testClass: Class<*>) = LinChecker.check(testClass, this)

/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass.java, options)
 * ```
 */
fun <O : Options<O, *>> O.check(testClass: KClass<*>) = this.check(testClass.java)

internal fun <O : Options<O, *>> O.checkImpl(testClass: Class<*>) = LinChecker(testClass, this).checkImpl()