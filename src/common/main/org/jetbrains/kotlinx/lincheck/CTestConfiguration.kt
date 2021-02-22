package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*
import kotlin.reflect.*

/**
 * Abstract configuration for different lincheck modes.
 */
abstract class CTestConfiguration(
    val testClass: TestClass,
    val iterations: Int,
    val threads: Int,
    val actorsPerThread: Int,
    val actorsBefore: Int,
    val actorsAfter: Int,
    val executionGenerator: (testConfiguration: CTestConfiguration, testStructure: CTestStructure) -> ExecutionGenerator,
    val verifierGenerator: (sequentialSpecification: SequentialSpecification<*>) -> Verifier,
    val requireStateEquivalenceImplCheck: Boolean,
    val minimizeFailedScenario: Boolean,
    val sequentialSpecification: SequentialSpecification<*>,
    val timeoutMs: Long
) {
    abstract fun createStrategy(testClass: TestClass, scenario: ExecutionScenario, validationFunctions: List<ValidationFunction>,
                                stateRepresentationFunction: StateRepresentationFunction?, verifier: Verifier): Strategy

    companion object {
        const val DEFAULT_ITERATIONS = 100
        const val DEFAULT_THREADS = 2
        const val DEFAULT_ACTORS_PER_THREAD = 5
        const val DEFAULT_ACTORS_BEFORE = 5
        const val DEFAULT_ACTORS_AFTER = 5
        const val DEFAULT_MINIMIZE_ERROR = true
        const val DEFAULT_TIMEOUT_MS: Long = 10000
    }
}