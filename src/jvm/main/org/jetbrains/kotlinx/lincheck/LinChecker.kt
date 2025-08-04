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

import kotlinx.atomicfu.locks.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.*
import org.jetbrains.lincheck.datastructures.verifier.*
import org.jetbrains.lincheck.util.*
import kotlin.reflect.*

/**
 * This class runs concurrent tests.
 */
class LinChecker
@Deprecated(
    level = DeprecationLevel.WARNING,
    message = "Use StressOptions.check() or ModelCheckingOptions.check() instead.",
)
constructor(private val testClass: Class<*>, options: Options<*, *>?) {

    private val testStructure = CTestStructure.getFromTestClass(testClass)

    private val testConfigurations: List<CTestConfiguration> =
        options?.let { listOf(it.createTestConfigurations(testClass)) } ?: createFromTestClassAnnotations(testClass)

    private val reporter: Reporter = run {
        val logLevel = options?.logLevel ?: getLoggingLevel(testClass) ?: DEFAULT_LOG_LEVEL
        Reporter(logLevel)
    }

    init {
        // Currently, we extract validation functions from the `testClass` structure,
        // so for custom scenarios declared with DSL, we have to set it up when `testClass` is scanned
        testConfigurations.forEach { cTestConfiguration ->
            cTestConfiguration.customScenarios.forEach { it.validationFunction = testStructure.validationFunction }
        }
    }

    /**
     * @throws LincheckAssertionError if the testing data structure is incorrect.
     */
    fun check() {
        checkImpl { failure ->
            if (failure != null) throw LincheckAssertionError(failure)
        }
    }

    /**
     * Runs Lincheck to check the tested class under given configurations.
     *
     * @param cont Optional continuation taking [LincheckFailure] as an argument.
     *   The continuation is run in the context when Lincheck java-agent is still attached.
     * @return [LincheckFailure] if a failure is discovered, null otherwise.
     */
    internal fun checkImpl(cont: LincheckFailureContinuation? = null): LincheckFailure? =
        LINCHECK_TEST_LOCK.withLock {
            check(testConfigurations.isNotEmpty()) { "No Lincheck test configuration to run" }
            lincheckVerificationStarted()
            for (testCfg in testConfigurations) {
                withLincheckJavaAgent(testCfg.instrumentationMode) {
                    val failure = testCfg.checkImpl()
                    if (failure != null) {
                        if (cont != null) cont(failure)
                        return failure
                    }
                }
            }
            if (cont != null) cont(null)
            return null
        }

    private fun CTestConfiguration.checkImpl(): LincheckFailure? {
        var verifier = createVerifier()
        val generator = createExecutionGenerator(testStructure.randomProvider)
        // create a sequence that generates scenarios lazily on demand
        val randomScenarios = generateSequence {
            generator.nextExecution().also {
                // reset the parameter generator ranges to start with the same initial bounds for each scenario.
                testStructure.parameterGenerators.forEach { it.reset() }
            }
        }
        // we want to handle custom scenarios and random scenarios uniformly by a single loop;
        // to achieve this, we join the custom scenarios list and
        // random scenarios generator into a single sequence;
        // this way the random scenarios are still generated lazily on demand
        // only after all custom scenarios are checked
        val scenarios = customScenarios.asSequence() + randomScenarios.take(iterations)
        val scenariosSize = customScenarios.size + iterations
        scenarios.forEachIndexed { i, scenario ->
            val isCustomScenario = (i < customScenarios.size)
            // For performance reasons, verifier re-uses LTS from previous iterations.
            // This behavior is similar to a memory leak and can potentially cause OutOfMemoryError.
            // This is why we periodically create a new verifier to still have increased performance
            // from re-using LTS and limit the size of potential memory leak.
            // https://github.com/Kotlin/kotlinx-lincheck/issues/124
            if ((i + 1) % VERIFIER_REFRESH_CYCLE == 0)
                verifier = createVerifier()
            scenario.validate()
            reporter.logIteration(i + 1, scenariosSize, scenario)
            var failure = scenario.run(this, verifier)
            if (failure == null)
                return@forEachIndexed
            if (minimizeFailedScenario && !isCustomScenario) {
                reporter.logScenarioMinimization(scenario)
                failure = failure.minimize { minimizedScenario ->
                    minimizedScenario.run(this, createVerifier())
                }
            }
            reporter.logFailedIteration(failure)
            runReplayForPlugin(failure, verifier)
            return failure
        }
        return null
    }

    /**
     * Enables replay mode and re-runs the failed scenario if Lincheck IDEA plugin is enabled.
     * We cannot initiate the failed interleaving replaying in the strategy code,
     * as the failing scenario might need to be minimized first.
     */
    private fun CTestConfiguration.runReplayForPlugin(failure: LincheckFailure, verifier: Verifier) {
        if (ideaPluginEnabled && this is ManagedCTestConfiguration) {
            runPluginReplay(
                settings = this.createSettings(),
                testClass = testClass,
                scenario = failure.scenario,
                validationFunction = testStructure.validationFunction,
                stateRepresentationMethod = testStructure.stateRepresentation,
                invocations = invocationsPerIteration,
                verifier = verifier
            )
        }
    }

    private fun ExecutionScenario.run(
        testCfg: CTestConfiguration,
        verifier: Verifier,
    ): LincheckFailure? {
        val strategy = testCfg.createStrategy(this)
        return strategy.use {
            it.runIteration(testCfg.invocationsPerIteration, verifier)
        }
    }

    private fun CTestConfiguration.createStrategy(scenario: ExecutionScenario) =
        createStrategy(
            testClass = testClass,
            scenario = scenario,
            validationFunction = testStructure.validationFunction,
            stateRepresentationMethod = testStructure.stateRepresentation,
        )

    private fun CTestConfiguration.createExecutionGenerator(randomProvider: RandomProvider): ExecutionGenerator {
        if (iterations > 0) {
            checkAtLeastOneMethodIsMarkedAsOperation()
        }
        val constructor = generatorClass.getConstructor(
            CTestConfiguration::class.java,
            CTestStructure::class.java,
            RandomProvider::class.java
        )
        return constructor.newInstance(this, testStructure, randomProvider)
    }

    private fun checkAtLeastOneMethodIsMarkedAsOperation() {
        check(testStructure.hasOperations()) { NO_OPERATION_ERROR_MESSAGE }
    }

    // This companion object is used for backwards compatibility.
    companion object {
        /**
         * Runs the specified concurrent tests. If [options] is null, the provided on
         * the testing class `@...CTest` annotations are used to specify the test parameters.
         *
         * @throws AssertionError if any of the tests fails.
         */
        @Deprecated(
            "Use StressOptions.check() or ModelCheckingOptions.check() instead.",
            level = DeprecationLevel.WARNING
        )
        @JvmOverloads
        @JvmStatic
        fun check(testClass: Class<*>, options: Options<*, *>? = null) {
            @Suppress("DEPRECATION")
            LinChecker(testClass, options).check()
        }

        private fun getLoggingLevel(testClass: Class<*>): LoggingLevel? {
            @Suppress("DEPRECATION")
            return testClass.getAnnotation(org.jetbrains.kotlinx.lincheck.annotations.LogLevel::class.java)?.value
        }

        private const val VERIFIER_REFRESH_CYCLE = 100
    }
}

// Tries to minimize the specified failing scenario to make the error easier to understand.
// The algorithm is greedy: it tries to remove one actor from the scenario and checks
// whether a test with the modified one fails with error as well. If it fails,
// then the scenario has been successfully minimized, and the algorithm tries to minimize it again, recursively.
// Otherwise, if no actor can be removed so that the generated test fails, the minimization is completed.
// Thus, the algorithm works in the linear time of the total number of actors.
private fun LincheckFailure.minimize(checkScenario: (ExecutionScenario) -> LincheckFailure?): LincheckFailure {
    var minimizedFailure = this
    while (true) {
        minimizedFailure = minimizedFailure.scenario.tryMinimize(checkScenario)
            ?: break
    }
    return minimizedFailure
}

private fun ExecutionScenario.tryMinimize(checkScenario: (ExecutionScenario) -> LincheckFailure?): LincheckFailure? {
    // Try to remove only one operation.
    for (threadId in threads.indices.reversed()) {
        for (actorId in threads[threadId].indices.reversed()) {
            tryMinimize(threadId, actorId)?.run(checkScenario)?.let { return it }
        }
    }
    // Try to remove two operations. For some data structure, such as a queue,
    // you need to remove pairwise operations, such as enqueue(e) and dequeue(),
    // to minimize the scenario and keep the error.
    for (threadId1 in threads.indices.reversed()) {
        for (actorId1 in threads[threadId1].indices.reversed()) {
            // Try to remove two operations at once.
            val minimizedScenario = tryMinimize(threadId1, actorId1) ?: continue
            for (threadId2 in minimizedScenario.threads.indices.reversed()) {
                for (actorId2 in minimizedScenario.threads[threadId2].indices.reversed()) {
                    minimizedScenario.tryMinimize(threadId2, actorId2)?.run(checkScenario)?.let { return it }
                }
            }
        }
    }
    // Try to move one of the first operations to the initial (pre-parallel) part.
    parallelExecution.forEachIndexed { threadId: Int, actors: List<Actor> ->
        if (actors.isNotEmpty()) {
            val newInitExecution = initExecution + actors.first()
            val newParallelExecution = parallelExecution.mapIndexed { t: Int, it: List<Actor> ->
                if (t == threadId) {
                    it.drop(1)
                } else {
                    ArrayList(it)
                }
            }.filter { it.isNotEmpty() }
            val newPostExecution = ArrayList(postExecution)
            val optimizedScenario = ExecutionScenario(
                initExecution = newInitExecution,
                parallelExecution = newParallelExecution,
                postExecution = newPostExecution,
                validationFunction = validationFunction
            )
            if (optimizedScenario.isValid) {
                optimizedScenario.run(checkScenario)?.let { return it }
            }
        }
    }
    // Try to move one of the last operations to the post (post-parallel) part.
    parallelExecution.forEachIndexed { threadId: Int, actors: List<Actor> ->
        if (actors.isNotEmpty()) {
            val newInitExecution = ArrayList(initExecution)
            val newParallelExecution = parallelExecution.mapIndexed { t: Int, it: List<Actor> ->
                if (t == threadId) {
                    it.dropLast(1)
                } else {
                    ArrayList(it)
                }
            }.filter { it.isNotEmpty() }
            val newPostExecution = listOf(actors.last()) + postExecution
            val optimizedScenario = ExecutionScenario(
                initExecution = newInitExecution,
                parallelExecution = newParallelExecution,
                postExecution = newPostExecution,
                validationFunction = validationFunction
            )
            if (optimizedScenario.isValid) {
                optimizedScenario.run(checkScenario)?.let { return it }
            }
        }
    }
    return null
}

/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass, options)
 * ```
 */
@Deprecated("Use StressOptions.check() or ModelCheckingOptions.check() instead.", level = DeprecationLevel.ERROR)
fun <O : Options<O, *>> O.check(testClass: Class<*>) = check(testClass)

/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass.java, options)
 * ```
 */
@Deprecated("Use StressOptions.check() or ModelCheckingOptions.check() instead.", level = DeprecationLevel.ERROR)
fun <O : Options<O, *>> O.check(testClass: KClass<*>) = check(testClass)

/**
 * Runs Lincheck to check the tested class under given configurations.
 *
 * @param testClass Tested class.
 * @return [LincheckFailure] if a failure is discovered, null otherwise.
 */
internal fun <O : Options<O, *>> O.checkImpl(testClass: Class<*>): LincheckFailure? =
    @Suppress("DEPRECATION")
    LinChecker(testClass, this).checkImpl()

/**
 * Runs Lincheck to check the tested class under given configurations.
 *
 * Takes the [LincheckFailureContinuation] as an argument.
 * This is required due to current limitations of our testing infrastructure.
 * Some tests need to inspect the internals of the failure object
 * (for example, the stack traces of exceptions thrown during the execution).
 * However, because Lincheck dynamically installs java-agent and then uninstalls it,
 * this process can invalidate some internal state of the failure object
 * (for example, the source code mapping information in the stack traces is typically lost).
 * To overcome this problem, we run the continuation in the context when Lincheck java-agent is still attached.
 *
 * @param testClass Tested class.
 * @param cont Continuation taking [LincheckFailure] as an argument.
 *   The continuation is run in the context when Lincheck java-agent is still attached.
 * @return [LincheckFailure] if a failure is discovered, null otherwise.
 */
internal fun <O : Options<O, *>> O.checkImpl(testClass: Class<*>, cont: LincheckFailureContinuation) {
    @Suppress("DEPRECATION")
    LinChecker(testClass, this).checkImpl(cont)
}

internal typealias LincheckFailureContinuation = (LincheckFailure?) -> Unit

internal const val NO_OPERATION_ERROR_MESSAGE =
    "You must specify at least one operation to test. Please refer to the user guide: https://kotlinlang.org/docs/introduction.html"

/**
 * We provide lincheck version to [testFailed] method to the plugin be able to
 * determine if this version is compatible with the plugin version.
 */
internal val lincheckVersion by lazy {
    LinChecker::class.java.`package`.implementationVersion ?: System.getProperty("lincheck.version")
}
