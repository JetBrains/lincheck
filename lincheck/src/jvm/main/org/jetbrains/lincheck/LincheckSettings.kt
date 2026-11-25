/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressStrategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.EventStructureStrategy
import org.jetbrains.lincheck.datastructures.*
import org.jetbrains.lincheck.datastructures.verifier.*
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.util.*

/**
 * Configuration of Lincheck tests.
 * Allows configuring the testing strategy, scenario generation options, timeouts, and other parameters.
 *
 * Lambda-based and data-structure-based APIs share the same settings class;
 * each API rejects settings it does not support with an [IllegalArgumentException].
 *
 * Supports both Kotlin builders over mutable properties and Java-style builder methods.
 *
 * Kotlin:
 * ```kotlin
 * val settings = LincheckSettings {
 *     timeoutMs = 10_000
 *     modelChecking {
 *         checkObstructionFreedom = true
 *         loopDetection { loopBound = 100 }
 *     }
 *     scenarioGeneration {
 *         threads = 3
 *         actorsPerThread = 4
 *     }
 * }
 * Lincheck.runConcurrentTest(settings, MyQueueTest::class)
 * ```
 *
 * Java:
 * ```java
 * LincheckSettings settings = new LincheckSettings()
 *     .timeoutMs(10_000)
 *     .strategy(new TestingStrategy.ModelChecking()
 *         .checkObstructionFreedom(true)
 *         .loopDetection(new LoopDetection().loopBound(100))
 *     )
 *     .scenarioGeneration(new ScenarioGeneration()
 *         .threads(3)
 *         .actorsPerThread(4)
 *     );
 * Lincheck.runConcurrentTest(settings, MyQueueTest.class);
 * ```
 */
class LincheckSettings() {

    /**
     * Applies [configure] to fresh default settings.
     */
    constructor(configure: LincheckSettings.() -> Unit) : this() {
        configure()
    }


    // ========================================================
    //   General settings
    // ========================================================

    /**
     * Timeout for a single invocation, in milliseconds.
     *
     * Must be positive.
     */
    var timeoutMs: Long = DEFAULT_TIMEOUT_MS
        set(value) {
            require(value > 0) { "timeoutMs must be positive, but was $value" }
            field = value
        }

    /**
     * Configures timeout for a single invocation.
     *
     * @param value timeout in milliseconds.
     * @throws IllegalArgumentException if [value] is not positive.
     */
    fun timeoutMs(value: Long) = apply { timeoutMs = value }

    /**
     * Controls additional diagnostic output.
     */
    var logLevel: LoggingLevel = DEFAULT_LOG_LEVEL

    /**
     * Configures logging level.
     */
    fun logLevel(value: LoggingLevel) = apply { logLevel = value }


    // ========================================================
    //   Scenario generation
    // ========================================================

    /**
     * Configures scenario generation and verification for data-structure-based tests.
     *
     * Lambda-based API does not support these settings and rejects any non-default value.
     *
     * @see ScenarioGeneration
     */
    var scenarioGeneration: ScenarioGeneration = ScenarioGeneration()

    /**
     * Configures scenario generation and verification.
     *
     * @see ScenarioGeneration
     */
    fun scenarioGeneration(value: ScenarioGeneration) = apply { scenarioGeneration = value }

    /**
     * Applies [configure] to the current [scenarioGeneration].
     */
    @JvmSynthetic
    fun scenarioGeneration(configure: ScenarioGeneration.() -> Unit) {
        scenarioGeneration.configure()
    }

    /**
     * Configuration of the scenario generation and verification for data-structure-based tests.
     */
    class ScenarioGeneration {

        // ======== Generated scenarios ========

        /**
         * Number of generated scenarios.
         * Zero value runs only [customScenarios].
         *
         * Must be non-negative.
         */
        var iterations: Int = DEFAULT_ITERATIONS
            set(value) {
                require(value >= 0) { "iterations must be non-negative, but was $value" }
                field = value
            }

        /**
         * Configures the number of generated scenarios.
         *
         * @throws IllegalArgumentException if [value] is negative.
         */
        fun iterations(value: Int) = apply { iterations = value }

        /**
         * Maximum number of invocations for each scenario.
         * Exploration can finish earlier if all interleavings are explored.
         *
         * Must be positive.
         */
        var invocationsPerIteration: Int = DEFAULT_INVOCATIONS_PER_ITERATION
            set(value) {
                require(value > 0) { "invocationsPerIteration must be positive, but was $value" }
                field = value
            }

        /**
         * Configures the maximum number of invocations for each scenario.
         *
         * @throws IllegalArgumentException if [value] is not positive.
         */
        fun invocationsPerIteration(value: Int) = apply { invocationsPerIteration = value }

        /**
         * Number of parallel threads in each generated scenario.
         *
         * Must be positive.
         */
        var threads: Int = DEFAULT_THREADS
            set(value) {
                require(value > 0) { "threads must be positive, but was $value" }
                field = value
            }

        /**
         * Configures the number of parallel threads in generated scenarios.
         *
         * @throws IllegalArgumentException if [value] is not positive.
         */
        fun threads(value: Int) = apply { threads = value }

        /**
         * Number of operations generated for each parallel thread.
         *
         * Must be positive.
         */
        var actorsPerThread: Int = DEFAULT_ACTORS_PER_THREAD
            set(value) {
                require(value > 0) { "actorsPerThread must be positive, but was $value" }
                field = value
            }

        /**
         * Configures the number of operations per parallel thread.
         *
         * @throws IllegalArgumentException if [value] is not positive.
         */
        fun actorsPerThread(value: Int) = apply { actorsPerThread = value }

        /**
         * Number of sequential operations generated before the parallel part.
         *
         * Must be non-negative.
         */
        var actorsBefore: Int = DEFAULT_ACTORS_BEFORE
            set(value) {
                require(value >= 0) { "actorsBefore must be non-negative, but was $value" }
                field = value
            }

        /**
         * Configures the number of sequential operations before the parallel part.
         *
         * @throws IllegalArgumentException if [value] is negative.
         * @see actorsBefore
         */
        fun actorsBefore(value: Int) = apply { actorsBefore = value }

        /**
         * Number of sequential operations generated after the parallel part.
         *
         * Must be non-negative.
         */
        var actorsAfter: Int = DEFAULT_ACTORS_AFTER
            set(value) {
                require(value >= 0) { "actorsAfter must be non-negative, but was $value" }
                field = value
            }

        /**
         * Configures the number of sequential operations after the parallel part.
         *
         * @throws IllegalArgumentException if [value] is negative.
         */
        fun actorsAfter(value: Int) = apply { actorsAfter = value }

        /**
         * Scenario generator class.
         * Must have a constructor accepting [LincheckSettings], [CTestStructure], and [RandomProvider].
         */
        var executionGenerator: Class<out ExecutionGenerator> = DEFAULT_EXECUTION_GENERATOR

        /**
         * Configures the scenario generator class.
         *
         * @see executionGenerator
         */
        fun executionGenerator(value: Class<out ExecutionGenerator>) = apply { executionGenerator = value }


        // ======== Custom scenarios ========

        /**
         * Custom scenarios checked before the generated ones, in insertion order.
         *
         * Custom scenarios are never minimized.
         *
         * @see addCustomScenario
         */
        val customScenarios: List<ExecutionScenario> get() = _customScenarios
        private val _customScenarios: MutableList<ExecutionScenario> = mutableListOf()

        /**
         * Adds a custom scenario.
         *
         * @see customScenarios
         */
        fun addCustomScenario(value: ExecutionScenario) = apply { _customScenarios.add(value) }

        /**
         * Adds a custom scenario built via the DSL.
         *
         * @see customScenarios
         */
        fun addCustomScenario(build: DSLScenarioBuilder.() -> Unit) = addCustomScenario(scenario(build))

        /**
         * Removes all custom scenarios.
         *
         * @see customScenarios
         */
        fun removeCustomScenarios() = apply { _customScenarios.clear() }


        // ======== Verification ========

        /**
         * If enabled, minimizes failing generated scenarios before reporting.
         */
        var minimizeFailedScenario: Boolean = DEFAULT_MINIMIZE_ERROR

        /**
         * Configures failing scenario minimization.
         */
        fun minimizeFailedScenario(value: Boolean) = apply { minimizeFailedScenario = value }

        /**
         * Verifier class which is used to check results against the sequential specification.
         * By default, a linearizability verifier is used.
         *
         * Any custom verifier class must have a constructor accepting the sequential specification class.
         */
        var verifier: Class<out Verifier> = DEFAULT_VERIFIER

        /**
         * Configures the verifier class.
         */
        fun verifier(value: Class<out Verifier>) = apply { verifier = value }

        /**
         * Sequential specification class.
         * Used to check the results of a parallel scenario execution against its sequential execution.
         *
         * By default, the test class itself is used.
         */
        var sequentialSpecification: Class<*>? = null

        /**
         * Configures the sequential specification class.
         */
        fun sequentialSpecification(value: Class<*>?) = apply { sequentialSpecification = value }


        // ======== Copying ========

        /**
         * Copies scenario generation settings.
         */
        fun copy() = ScenarioGeneration().also {
            it.invocationsPerIteration = invocationsPerIteration
            it.verifier = verifier
            it.sequentialSpecification = sequentialSpecification
            it.minimizeFailedScenario = minimizeFailedScenario
            it._customScenarios.addAll(_customScenarios.map { scenario -> scenario.copy() })
            it.iterations = iterations
            it.threads = threads
            it.actorsPerThread = actorsPerThread
            it.actorsBefore = actorsBefore
            it.actorsAfter = actorsAfter
            it.executionGenerator = executionGenerator
        }


        // ======== Internal utils ========

        // Names of the settings that differ from a new configuration; the lambda API rejects them.
        internal fun nonDefaultSettings(): List<String> {
            val defaults = ScenarioGeneration()
            return listOfNotNull(
                "iterations".takeIf { iterations != defaults.iterations },
                "invocationsPerIteration".takeIf { invocationsPerIteration != defaults.invocationsPerIteration },
                "threads".takeIf { threads != defaults.threads },
                "actorsPerThread".takeIf { actorsPerThread != defaults.actorsPerThread },
                "actorsBefore".takeIf { actorsBefore != defaults.actorsBefore },
                "actorsAfter".takeIf { actorsAfter != defaults.actorsAfter },
                "executionGenerator".takeIf { executionGenerator != defaults.executionGenerator },
                "customScenarios".takeIf { _customScenarios.isNotEmpty() },
                "minimizeFailedScenario".takeIf { minimizeFailedScenario != defaults.minimizeFailedScenario },
                "verifier".takeIf { verifier != defaults.verifier },
                "sequentialSpecification".takeIf { sequentialSpecification != defaults.sequentialSpecification },
            )
        }


        // ======== Defaults ========

        companion object {

            /**
             * Default value of [iterations], configurable via the `lincheck.defaultIterations` system property.
             */
            @JvmField
            val DEFAULT_ITERATIONS: Int = Integer.getInteger("lincheck.defaultIterations", 100)

            /**
             * Default value of [invocationsPerIteration], configurable via the
             * `lincheck.defaultInvocationsPerIteration` system property.
             */
            @JvmField
            val DEFAULT_INVOCATIONS_PER_ITERATION: Int = Integer.getInteger("lincheck.defaultInvocationsPerIteration", 10_000)

            /**
             * Default value of [threads] for a new configuration.
             */
            const val DEFAULT_THREADS = 2

            /**
             * Default value of [actorsPerThread] for a new configuration.
             */
            const val DEFAULT_ACTORS_PER_THREAD = 5

            /**
             * Default value of [actorsBefore] for a new configuration.
             */
            const val DEFAULT_ACTORS_BEFORE = 5

            /**
             * Default value of [actorsAfter] for a new configuration.
             */
            const val DEFAULT_ACTORS_AFTER = 5

            /**
             * Default value of [executionGenerator] for a new configuration.
             */
            val DEFAULT_EXECUTION_GENERATOR: Class<out ExecutionGenerator> = RandomExecutionGenerator::class.java

            /**
             * Default value of [minimizeFailedScenario] for a new configuration.
             */
            const val DEFAULT_MINIMIZE_ERROR = true

            /**
             * Default value of [verifier] for a new configuration.
             */
            val DEFAULT_VERIFIER: Class<out Verifier> = LinearizabilityVerifier::class.java
        }
    }


    // ========================================================
    //   Testing strategy
    // ========================================================

    /**
     * Selects the testing strategy:
     * - [TestingStrategy.Stress] --- stress strategy runs the code repeatedly under the JVM scheduler.
     * - [TestingStrategy.ModelChecking] --- model-checking strategy systematically explores
     *     different thread interleavings under a controlled environment and a cooperative thread scheduler.
     *
     * Lambda-based API currently supports only [TestingStrategy.ModelChecking].
     */
    var strategy: TestingStrategy = TestingStrategy.ModelChecking()

    /**
     * Configures the testing strategy.
     */
    fun strategy(value: TestingStrategy) = apply { strategy = value }

    /**
     * Selects [TestingStrategy.Stress].
     */
    @JvmSynthetic
    fun stress() {
        strategy = TestingStrategy.Stress()
    }

    /**
     * Selects [TestingStrategy.ModelChecking] and applies [configure] to it.
     * If model checking is already selected, its current configuration is kept and extended.
     */
    @JvmSynthetic
    fun modelChecking(configure: TestingStrategy.ModelChecking.() -> Unit = {}) {
        val modelChecking = strategy as? TestingStrategy.ModelChecking
            ?: TestingStrategy.ModelChecking().also { strategy = it }
        modelChecking.configure()
    }

    /**
     * A sealed parent class for all testing strategies configuration classes.
     */
    sealed class TestingStrategy {

        /**
         * Stress strategy runs code repeatedly under the JVM scheduler.
         */
        class Stress : TestingStrategy()

        /**
         * Model-checking strategy systematically explores different thread interleavings
         * under a controlled environment and a cooperative thread scheduler.
         */
        class ModelChecking : TestingStrategy() {

            // ======== Analysis scope ========

            /**
             * Enables or disables the analysis of standard-library classes.
             * Enabled by default.
             */
            var analyzeStdLib: Boolean = DEFAULT_STDLIB_ANALYSIS_ENABLED

            /**
             * Configures standard-library analysis.
             *
             * @see analyzeStdLib
             */
            fun analyzeStdLib(value: Boolean) = apply { analyzeStdLib = value }

            /**
             * Method guarantees, in insertion order.
             *
             * @see addGuarantee
             */
            val guarantees: List<ManagedStrategyGuarantee> get() = _guarantees
            private val _guarantees: MutableList<ManagedStrategyGuarantee> = DEFAULT_GUARANTEES.toMutableList()

            /**
             * Adds a method guarantee.
             */
            fun addGuarantee(value: ManagedStrategyGuarantee) = apply { _guarantees.add(value) }

            /**
             * Removes all method guarantees.
             *
             * @see guarantees
             */
            fun removeGuarantees() = apply { _guarantees.clear() }


            // ======== Progress checking ========

            /**
             * If enabled, reports executions that cannot make progress
             * when a non-blocking operation runs in isolation.
             */
            var checkObstructionFreedom: Boolean = DEFAULT_CHECK_OBSTRUCTION_FREEDOM

            /**
             * Configures obstruction-freedom checking.
             */
            @JvmOverloads
            fun checkObstructionFreedom(value: Boolean = true) = apply { checkObstructionFreedom = value }

            /**
             * Configures spin loop detection parameters.
             *
             * @see LoopDetection
             */
            var loopDetection: LoopDetection = LoopDetection()

            /**
             * Configures spin loop detection parameters.
             *
             * @see LoopDetection
             */
            fun loopDetection(value: LoopDetection) = apply { loopDetection = value }

            /**
             * Applies [configure] to the current [loopDetection].
             */
            @JvmSynthetic
            fun loopDetection(configure: LoopDetection.() -> Unit) {
                loopDetection.configure()
            }


            // ======== Exploration algorithm ========

            /**
             * Selects the memory model to be used to explore thread interleavings and shared memory behavior.
             *
             * By default, uses [MemoryModel.SequentialConsistency] memory model.
             * Settings [MemoryModel.RelaxedJava] enables relaxed Java Memory Model semantics.
             *
             * Any non-sequential-consistent model requires to user [Algorithm.EventStructure] algorithm.
             */
            var memoryModel: MemoryModel = MemoryModel.SequentialConsistency

            /**
             * Configures the memory model used by model checker.
             */
            fun memoryModel(value: MemoryModel) = apply { memoryModel = value }

            /**
             * Enables partial order reduction optimization that decreases
             * the exploration space at the cost of more heavyweight analysis.
             *
             * Partial order reduction allows skipping exploration of interleavings
             * that are equivalent up to a reordering of non-conflicting (commutative) events.
             * It relies on tracking and recording performed events,
             * which allows reversing data races at the end of each execution.
             */
            var partialOrderReduction: Boolean = false

            /**
             * Configures partial order reduction optimization.
             */
            @JvmOverloads
            fun enablePartialOrderReduction(enabled: Boolean = true) = apply { partialOrderReduction = enabled }

            /**
             * The model-checking algorithm to use:
             * - [Algorithm.InterleavingTree] --- an interleaving-tree-based algorithm,
             *      explores a tree of interleavings under sequential consistency.
             *      Based on the paper:
             *      - Iterative context bounding for systematic testing of multithreaded program
             *        (https://dl.acm.org/doi/pdf/10.1145/1273442.1250785)
             *
             * - [Algorithm.EventStructure] --- an event-structure-based partial-order-reduction algorithm,
             *      tracks and records performed events and reverses data races to explore new interleavings.
             *      Can intercept read operations results and supports relaxed memory models.
             *      Based on the paper:
             *      - Truly Stateless, Optimal Dynamic Partial Order Reduction
             *        (https://dl.acm.org/doi/pdf/10.1145/3498711).
             *
             *
             * By default, [Algorithm.InterleavingTree] is used.
             * Calling [enablePartialOrderReduction] selects [Algorithm.EventStructure].
             * Setting any memory model other than [MemoryModel.SequentialConsistency]
             * would also automatically select [Algorithm.EventStructure].
             */
            var algorithm: Algorithm? = null

            /**
             * Configures model checking algorith.
             */
            fun algorithm(value: Algorithm) = apply { algorithm = value }

            /**
             * Implementation used to explore interleavings and shared memory behavior.
             */
            enum class Algorithm {
                /**
                 * Explores a tree of interleavings under sequential consistency.
                 */
                InterleavingTree,

                /**
                 * Explores event structures with partial-order reduction and configurable memory model semantics.
                 */
                EventStructure,
            }


            // ======== Reporting ========

            /**
             * If enabled, appends the failing execution graph to the report.
             *
             * Requires [Algorithm.EventStructure].
             */
            var printExecutionGraph: Boolean = false

            /**
             * Controls inclusion of the failing execution graph; requires [Algorithm.EventStructure].
             */
            fun printExecutionGraph(value: Boolean) = apply { printExecutionGraph = value }


            // ======== Copying ========

            /**
             * Copies model checking settings.
             */
            fun copy() = ModelChecking().also {
                it.analyzeStdLib = analyzeStdLib
                it.checkObstructionFreedom = checkObstructionFreedom
                it.loopDetection = loopDetection.copy()
                it._guarantees.addAll(_guarantees)
                it.printExecutionGraph = printExecutionGraph
                it.partialOrderReduction = partialOrderReduction
                it.memoryModel = memoryModel
                it.algorithm = algorithm
            }


            // ======== Internal utils ========

            internal fun resolveAlgorithm(): Algorithm {
                val nonScMemoryModel = (memoryModel != MemoryModel.SequentialConsistency)
                val needsEventStructure = partialOrderReduction || nonScMemoryModel
                val selected = algorithm ?: if (
                    needsEventStructure ||
                    System.getProperty("lincheck.useExperimentalModelChecking").toBoolean()
                ) Algorithm.EventStructure else Algorithm.InterleavingTree


                require(!partialOrderReduction || selected == Algorithm.EventStructure) {
                    "Partial-order reduction requires the Event Structure algorithm"
                }
                require(!nonScMemoryModel || selected == Algorithm.EventStructure) {
                    "Non-sequentially-consistent memory model requires the Event Structure algorithm"
                }
                require(!printExecutionGraph || selected == Algorithm.EventStructure) {
                    "printExecutionGraph requires the Event Structure algorithm"
                }

                return selected
            }


            // ======== Defaults ========

            companion object {
                /**
                 * Default value of [analyzeStdLib] for a new configuration.
                 */
                val DEFAULT_STDLIB_ANALYSIS_ENABLED = AnalysisProfile.DEFAULT.analyzeStdLib

                /**
                 * Default value of [guarantees] for a new configuration.
                 */
                val DEFAULT_GUARANTEES: List<ManagedStrategyGuarantee> = emptyList()

                /**
                 * Default value of [checkObstructionFreedom] for a new configuration.
                 */
                const val DEFAULT_CHECK_OBSTRUCTION_FREEDOM = false
            }
        }
    }


    // ========================================================
    //   Loop detection
    // ========================================================

    /**
     * Configuration of the spin loop detection.
     */
    class LoopDetection {

        // ======== Bounds ========

        /**
         * Number of repeated loop iterations before attempting a thread switch.
         *
         * Must be positive.
         */
        var loopIterationsBeforeThreadSwitch: Int = DEFAULT_LOOP_ITERATIONS_BEFORE_THREAD_SWITCH
            set(value) {
                require(value > 0) { "loopIterationsBeforeThreadSwitch must be positive, but was $value" }
                field = value
            }

        /**
         * Configures the number of repeated loop iterations before attempting a thread switch.
         *
         * @throws IllegalArgumentException if [value] is not positive.
         * @see loopIterationsBeforeThreadSwitch
         */
        fun loopIterationsBeforeThreadSwitch(value: Int) = apply { loopIterationsBeforeThreadSwitch = value }

        /**
         * Loop-iteration bound after which execution is considered stuck.
         *
         * Must be positive.
         */
        var loopBound: Int = DEFAULT_LOOP_BOUND
            set(value) {
                require(value > 0) { "loopBound must be positive, but was $value" }
                field = value
            }

        /**
         * Configures the loop-iteration bound.
         *
         * @throws IllegalArgumentException if [value] is not positive.
         * @see loopBound
         */
        fun loopBound(value: Int) = apply { loopBound = value }

        /**
         * Recursive-call bound after which execution is considered stuck.
         *
         * Must be positive.
         */
        var recursionBound: Int = DEFAULT_RECURSION_BOUND
            set(value) {
                require(value > 0) { "recursionBound must be positive, but was $value" }
                field = value
            }

        /**
         * Configures the recursive-call bound.
         *
         * @throws IllegalArgumentException if [value] is not positive.
         * @see recursionBound
         */
        fun recursionBound(value: Int) = apply { recursionBound = value }


        // ======== Await-loops analysis ========

        /**
         * If enabled, detects read-only busy-wait paths and suggests thread switches when they are taken.
         */
        var awaitLoopsAnalysisEnabled: Boolean = DEFAULT_AWAIT_LOOPS_ANALYSIS_ENABLED

        /**
         * Configures await-loops analysis.
         *
         * @see awaitLoopsAnalysisEnabled
         */
        @JvmOverloads
        fun enableAwaitLoopsAnalysis(value: Boolean = true) = apply { awaitLoopsAnalysisEnabled = value }


        // ======== Copying ========

        /**
         * Copies loop detection settings.
         */
        fun copy() = LoopDetection().also {
            it.loopIterationsBeforeThreadSwitch = loopIterationsBeforeThreadSwitch
            it.loopBound = loopBound
            it.recursionBound = recursionBound
            it.awaitLoopsAnalysisEnabled = awaitLoopsAnalysisEnabled
        }


        // ======== Defaults ========

        companion object {
            /**
             * Default value of [loopIterationsBeforeThreadSwitch] for a new configuration.
             */
            const val DEFAULT_LOOP_ITERATIONS_BEFORE_THREAD_SWITCH = 10

            /**
             * Default value of [loopBound] for a new configuration.
             */
            const val DEFAULT_LOOP_BOUND = 50

            /**
             * Default value of [recursionBound] for a new configuration.
             */
            const val DEFAULT_RECURSION_BOUND = 20

            /**
             * Legacy livelock-event threshold retained for options compatibility.
             */
            const val DEFAULT_LIVELOCK_EVENTS_THRESHOLD = 10001

            /**
             * Default value of [awaitLoopsAnalysisEnabled] for a new configuration.
             */
            const val DEFAULT_AWAIT_LOOPS_ANALYSIS_ENABLED = true
        }
    }


    // ========================================================
    //   Memory model
    // ========================================================

    /**
     * Memory model defining which values shared-memory reads can observe.
     *
     * @see TestingStrategy.ModelChecking.memoryModel
     */
    enum class MemoryModel {
        /**
         * Every execution is an interleaving of thread operations.
         */
        SequentialConsistency,

        /**
         * Release-acquire memory model.
         */
        ReleaseAcquire,

        /**
         * Relaxed Java Memory Model semantics.
         */
        RelaxedJava,
    }


    // ========================================================
    //   Internal settings
    // ========================================================

    /**
     * Indicates whether the replay mode for the IntelliJ IDEA plugin is enabled.
     */
    internal var ideaPluginReplayModeEnabled: Boolean = false

    /**
     * A nullable factory function used for creating instances of [ExecutionGenerator]
     * with legacy [Options]-based configuration classes.
     */
    internal var legacyExecutionGeneratorFactory: ((CTestStructure, RandomProvider) -> ExecutionGenerator)? = null


    // ========================================================
    //   Copying and validation
    // ========================================================

    /**
     * Copies the settings.
     */
    fun copy(): LincheckSettings = LincheckSettings().also {
        it.timeoutMs = timeoutMs
        it.logLevel = logLevel
        it.strategy = when (val strategy = strategy) {
            is TestingStrategy.Stress -> TestingStrategy.Stress()
            is TestingStrategy.ModelChecking -> strategy.copy()
        }
        it.scenarioGeneration = scenarioGeneration.copy()
        it.ideaPluginReplayModeEnabled = ideaPluginReplayModeEnabled
        it.legacyExecutionGeneratorFactory = legacyExecutionGeneratorFactory
    }

    internal fun copy(forLambda: Boolean): LincheckSettings {
        if (forLambda) {
            require(strategy is TestingStrategy.ModelChecking) {
                "Lambda-based runConcurrentTest currently supports only model checking; " +
                "select it with modelChecking { } or strategy(TestingStrategy.ModelChecking())"
            }
            val nonDefaultScenarioSettings = scenarioGeneration.nonDefaultSettings()
            require(nonDefaultScenarioSettings.isEmpty()) {
                "Lambda-based runConcurrentTest does not support scenario generation settings; " +
                "reset these to their defaults: ${nonDefaultScenarioSettings.joinToString()}"
            }
        }
        return copy().also {
            (it.strategy as? TestingStrategy.ModelChecking)?.let { strategy ->
                strategy.algorithm = strategy.resolveAlgorithm()
            }
        }
    }

    // ========================================================
    //   Defaults
    // ========================================================

    companion object {
        /**
         * Default value of [timeoutMs] for a new configuration.
         */
        const val DEFAULT_TIMEOUT_MS: Long = 30_000
    }
}


// ========================================================
//   Internal Utils
// ========================================================

internal val LincheckSettings.instrumentationMode: InstrumentationMode
    get() = when (val strategy = strategy) {
        is TestingStrategy.Stress -> InstrumentationMode.STRESS
        is TestingStrategy.ModelChecking -> when (strategy.resolveAlgorithm()) {
            TestingStrategy.ModelChecking.Algorithm.InterleavingTree -> InstrumentationMode.MODEL_CHECKING
            TestingStrategy.ModelChecking.Algorithm.EventStructure -> InstrumentationMode.EXPERIMENTAL_MODEL_CHECKING
        }
    }

internal val LincheckSettings.effectiveTimeoutMs: Long
    // Interactive debugging may pause an invocation indefinitely; keep the runner alive during plugin replay.
    get() = if (ideaPluginReplayModeEnabled) 1000L * 60 * 60 * 24 * 365 else timeoutMs


internal fun LincheckSettings.createStrategy(
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunction: Actor?,
): Strategy = createStrategy(ExecutionScenarioRunner(
    scenario = scenario,
    testClass = testClass,
    validationFunction = validationFunction,
    timeoutMs = effectiveTimeoutMs,
    useClocks = if (strategy is TestingStrategy.Stress) UseClocks.RANDOM else UseClocks.ALWAYS,
))

internal fun LincheckSettings.createStrategy(runner: AbstractActiveThreadPoolRunner): Strategy {
    val result = when (val strategy = strategy) {
        is TestingStrategy.Stress -> StressStrategy(runner)
        is TestingStrategy.ModelChecking -> when (strategy.resolveAlgorithm()) {
            TestingStrategy.ModelChecking.Algorithm.InterleavingTree -> ModelCheckingStrategy(
                runner, strategy, ideaPluginReplayModeEnabled, LincheckInstrumentation.context,
            )
            TestingStrategy.ModelChecking.Algorithm.EventStructure -> EventStructureStrategy(
                runner, strategy, LincheckInstrumentation.context, strategy.memoryModel,
                inIdeaPluginReplayMode = ideaPluginReplayModeEnabled,
                printExecutionGraph = strategy.printExecutionGraph,
            )
        }
    }
    runner.initializeStrategy(result)
    return result
}


internal fun LincheckSettings.createVerifier(testClass: Class<*>) =
    scenarioGeneration.verifier.getConstructor(Class::class.java)
        .newInstance(scenarioGeneration.sequentialSpecification ?: testClass)

internal inline fun <T> LincheckSettings.withIdeaPluginReplayModeEnabled(block: () -> T): T {
    val previous = ideaPluginReplayModeEnabled
    ideaPluginReplayModeEnabled = true
    try {
        return block()
    } finally {
        ideaPluginReplayModeEnabled = previous
    }
}
