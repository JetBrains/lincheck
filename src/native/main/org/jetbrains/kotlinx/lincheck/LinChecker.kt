/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.reflect.*

class NativeAPIStressConfiguration: LincheckStressConfiguration<Any>() {
    fun runNativeTest() {
        LinChecker.check(getTestClass(), getTestStructure(), this as StressOptions)
    }

    fun setupInitialState(
        state: () -> Any
    ) = apply {
        testClass = TestClass(state)
    }

    fun setupOperationWithoutArguments(
        op: () -> Unit
    ) = apply {
        val actorGenerator = ActorGenerator(
            function = { _, _ ->
                op()
            },
            parameterGenerators = emptyList(),
            functionName = "Operation",
            useOnce = false,
            isSuspendable = false,
            handledExceptions = emptyList()
        )
        actorGenerators.add(actorGenerator)
    }

    fun setupOperation(
        pGens: List<ParameterGenerator<*>>,
        op: (Any, List<Any?>) -> Any,
        name: String = op.toString(),
        handleExceptionsAsResult: List<KClass<out Throwable>> = emptyList(),
        useOnce: Boolean = false,
        isSuspendable: Boolean = false
    ) = apply {
        val actorGenerator = ActorGenerator(
            function = { instance, arguments ->
                op(instance, arguments)
            },
            parameterGenerators = pGens,
            functionName = name,
            useOnce = useOnce,
            isSuspendable = isSuspendable,
            handledExceptions = handleExceptionsAsResult
        )
        actorGenerators.add(actorGenerator)
    }
}

// TODO StressOptions methods cast this class to StressOptions
open class LincheckStressConfiguration<Instance>(protected val testName: String = "") : StressOptions() {
    /*
    invocationsPerIteration
    iterations
    threads
    actorsPerThread
    actorsBefore
    actorsAfter
    executionGenerator(executionGenerator: (testConfiguration: CTestConfiguration, testStructure: CTestStructure) -> ExecutionGenerator)
    verifier(verifier: (sequentialSpecification: SequentialSpecification<*>) -> Verifier)
    requireStateEquivalenceImplCheck
    minimizeFailedScenario
    createTestConfigurations
    logLevel(logLevel: LoggingLevel)
    sequentialSpecification(clazz: SequentialSpecification<*>?)
    */
    protected var testClass: TestClass? = null
    protected var actorGenerators = mutableListOf<ActorGenerator>()
    protected var operationGroups = mutableListOf<OperationGroup>()
    protected var validationFunctions = mutableListOf<ValidationFunction>()
    protected var stateRepresentationFunction: StateRepresentationFunction? = null

    protected fun getTestClass(): TestClass {
        return testClass ?: throw IllegalArgumentException("initialState should be specified")
    }

    protected fun getTestStructure(): CTestStructure {
        return CTestStructure(
            actorGenerators,
            operationGroups,
            validationFunctions,
            stateRepresentationFunction
        )
    }

    fun runTest() {
        LinChecker.check(getTestClass(), getTestStructure(), this as StressOptions)
        if(testName.isNotEmpty()) {
            println("Finished test $testName")
        }
    }

    fun checkImpl(): LincheckFailure? {
        val result = LinChecker(getTestClass(), getTestStructure(), this as StressOptions).checkImpl()
        if(testName.isNotEmpty()) {
            println("Finished test $testName")
        }
        return result
    }

    // =========================== Constructor

    fun initialState(
        state: () -> Instance
    ) = apply {
        testClass = TestClass(state)
    }

    // =========================== Operation

    fun <R> operation(
        pGens: List<ParameterGenerator<*>>,
        op: Instance.(List<Any?>) -> R,
        name: String = op.toString(),
        handleExceptionsAsResult: List<KClass<out Throwable>> = emptyList(),
        useOnce: Boolean = false,
        isSuspendable: Boolean = false
    ) = apply {
        val actorGenerator = ActorGenerator(
            function = { instance, arguments ->
                instance as Instance // check that operation can be applied to instance
                instance.op(arguments)
            },
            parameterGenerators = pGens,
            functionName = name,
            useOnce = useOnce,
            isSuspendable = isSuspendable,
            handledExceptions = handleExceptionsAsResult
        )
        actorGenerators.add(actorGenerator)
    }

    fun <R> operation(
        op: Instance.() -> R,
        name: String = op.toString(),
        handleExceptionsAsResult: List<KClass<out Throwable>> = emptyList(),
        useOnce: Boolean = false,
        isSuspendable: Boolean = false
    ) = apply {
        val actorGenerator = ActorGenerator(
            function = { instance, arguments ->
                instance as Instance // check that operation can be applied to instance
                instance.op()
            },
            parameterGenerators = listOf(),
            functionName = name,
            useOnce = useOnce,
            isSuspendable = isSuspendable,
            handledExceptions = handleExceptionsAsResult
        )
        actorGenerators.add(actorGenerator)
    }

    fun <P1, R> operation(
        p1Gen: ParameterGenerator<P1>,
        op: Instance.(p1: P1) -> R,
        name: String = op.toString(),
        handleExceptionsAsResult: List<KClass<out Throwable>> = emptyList(),
        useOnce: Boolean = false,
        isSuspendable: Boolean = false
    ) = apply {
        val actorGenerator = ActorGenerator(
            function = { instance, arguments ->
                instance as Instance // check that operation can be applied to instance
                instance.op(arguments[0] as P1) // extract arguments and cast to type
            },
            parameterGenerators = listOf(p1Gen),
            functionName = name,
            useOnce = useOnce,
            isSuspendable = isSuspendable,
            handledExceptions = handleExceptionsAsResult
        )
        actorGenerators.add(actorGenerator)
    }

    fun <P1, P2, R> operation(
        p1Gen: ParameterGenerator<P1>,
        p2Gen: ParameterGenerator<P2>,
        op: Instance.(p1: P1, p2: P2) -> R,
        name: String = op.toString(),
        handleExceptionsAsResult: List<KClass<out Throwable>> = emptyList(),
        useOnce: Boolean = false,
        isSuspendable: Boolean = false
    ) = apply {
        val actorGenerator = ActorGenerator(
            function = { instance, arguments ->
                instance as Instance // check that operation can be applied to instance
                instance.op(arguments[0] as P1, arguments[1] as P2) // extract arguments and cast to type
            },
            parameterGenerators = listOf(p1Gen, p2Gen),
            functionName = name,
            useOnce = useOnce,
            isSuspendable = isSuspendable,
            handledExceptions = handleExceptionsAsResult
        )
        actorGenerators.add(actorGenerator)
    }

    // ============================= Validation Function

    fun validationFunction(
        validate: Instance.() -> Unit,
        name: String = validate.toString()
    ) = apply {
        validationFunctions.add(ValidationFunction({ instance ->
            instance as Instance // check that operation can be applied to instance
            instance.validate()
        }, name))
    }

    // ============================= State Representation Function

    fun stateRepresentation(
        state: Instance.() -> String
    ) = apply {
        stateRepresentationFunction = StateRepresentationFunction { instance ->
            instance as Instance // check that operation can be applied to instance
            instance.state()
        }
    }
}

/**
 * This class runs concurrent tests.
 */
class LinChecker(private val testClass: TestClass, private val testStructure: CTestStructure, options: Options<*, *>) {
    private val testConfigurations: List<CTestConfiguration>
    private val reporter: Reporter

    init {
        val logLevel = options?.logLevel ?: DEFAULT_LOG_LEVEL
        reporter = Reporter(logLevel)
        testConfigurations = listOf(options.createTestConfigurations(testClass))
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
    internal fun checkImpl(): LincheckFailure? {
        check(testConfigurations.isNotEmpty()) { "No Lincheck test configuration to run" }
        for (testCfg in testConfigurations) {
            val failure = testCfg.checkImpl()
            if (failure != null) return failure
        }
        return null
    }

    private fun CTestConfiguration.checkImpl(): LincheckFailure? {
        val exGen = createExecutionGenerator()
        val verifier = createVerifier()
        repeat(iterations) { i ->
            //printErr("Iteration $i") // TODO debug output
            val scenario = exGen.nextExecution()
            scenario.validate()
            reporter.logIteration(i + 1, iterations, scenario)
            val failure = scenario.run(this, verifier)
            if (failure != null) {
                val minimizedFailedIteration = if (!minimizeFailedScenario) failure
                else failure.minimize(this, verifier)
                reporter.logFailedIteration(minimizedFailedIteration)
                return minimizedFailedIteration
            }
        }
        return null
    }

    // Tries to minimize the specified failing scenario to make the error easier to understand.
    // The algorithm is greedy: it tries to remove one actor from the scenario and checks
    // whether a test with the modified one fails with error as well. If it fails,
    // then the scenario has been successfully minimized, and the algorithm tries to minimize it again, recursively.
    // Otherwise, if no actor can be removed so that the generated test fails, the minimization is completed.
    // Thus, the algorithm works in the linear time of the total number of actors.
    private fun LincheckFailure.minimize(testCfg: CTestConfiguration, verifier: Verifier): LincheckFailure {
        reporter.logScenarioMinimization(scenario)
        val parallelExecution = scenario.parallelExecution.map { it.toMutableList() }.toMutableList()
        val initExecution = scenario.initExecution.toMutableList()
        val postExecution = scenario.postExecution.toMutableList()
        for (i in scenario.parallelExecution.indices) {
            for (j in scenario.parallelExecution[i].indices) {
                val newParallelExecution = parallelExecution.map { it.toMutableList() }.toMutableList()
                newParallelExecution[i].removeAt(j)
                if (newParallelExecution[i].isEmpty()) newParallelExecution.removeAt(i) // remove empty thread
                val newScenario = ExecutionScenario(
                    initExecution,
                    newParallelExecution,
                    postExecution
                )
                val newFailedIteration = newScenario.tryMinimize(testCfg, verifier)
                if (newFailedIteration != null) return newFailedIteration.minimize(testCfg, verifier)
            }
        }
        for (i in scenario.initExecution.indices) {
            val newInitExecution = initExecution.toMutableList()
            newInitExecution.removeAt(i)
            val newScenario = ExecutionScenario(
                newInitExecution,
                parallelExecution,
                postExecution
            )
            val newFailedIteration = newScenario.tryMinimize(testCfg, verifier)
            if (newFailedIteration != null) return newFailedIteration.minimize(testCfg, verifier)
        }
        for (i in scenario.postExecution.indices) {
            val newPostExecution = postExecution.toMutableList()
            newPostExecution.removeAt(i)
            val newScenario = ExecutionScenario(
                initExecution,
                parallelExecution,
                newPostExecution
            )
            val newFailedIteration = newScenario.tryMinimize(testCfg, verifier)
            if (newFailedIteration != null) return newFailedIteration.minimize(testCfg, verifier)
        }
        return this
    }

    private fun ExecutionScenario.tryMinimize(testCfg: CTestConfiguration, verifier: Verifier) =
        if (isValid) run(testCfg, verifier) else null

    private fun ExecutionScenario.run(testCfg: CTestConfiguration, verifier: Verifier): LincheckFailure? =
        testCfg.createStrategy(
            testClass = testClass,
            scenario = this,
            validationFunctions = testStructure.validationFunctions,
            stateRepresentationFunction = testStructure.stateRepresentation,
            verifier = verifier
        ).run()

    private fun ExecutionScenario.copy() = ExecutionScenario(
        ArrayList(initExecution),
        parallelExecution.map { ArrayList(it) },
        ArrayList(postExecution)
    )

    private val ExecutionScenario.isValid: Boolean
        get() = !isParallelPartEmpty &&
            (!hasSuspendableActors() || (!hasSuspendableActorsInInitPart && !hasPostPartAndSuspendableActors))

    private fun ExecutionScenario.validate() {
        require(!isParallelPartEmpty) {
            "The generated scenario has empty parallel part"
        }
        if (hasSuspendableActors()) {
            require(!hasSuspendableActorsInInitPart) {
                "The generated scenario for the test class with suspendable methods contains suspendable actors in initial part"
            }
            require(!hasPostPartAndSuspendableActors) {
                "The generated scenario  for the test class with suspendable methods has non-empty post part"
            }
        }
    }

    private val ExecutionScenario.hasSuspendableActorsInInitPart
        get() =
            initExecution.any(Actor::isSuspendable)
    private val ExecutionScenario.hasPostPartAndSuspendableActors
        get() =
            (parallelExecution.any { actors -> actors.any { it.isSuspendable } } && postExecution.size > 0)
    private val ExecutionScenario.isParallelPartEmpty
        get() =
            parallelExecution.map { it.size }.sum() == 0


    private fun CTestConfiguration.createVerifier() =
        verifierGenerator(this.sequentialSpecification).also {
            if (requireStateEquivalenceImplCheck) it.checkStateEquivalenceImplementation()
        }

    private fun CTestConfiguration.createExecutionGenerator() =
        executionGenerator(this, testStructure)

    // This companion object is used for backwards compatibility.
    companion object {
        /**
         * Runs the specified concurrent tests. If [options] is null, the provided on
         * the testing class `@...CTest` annotations are used to specify the test parameters.
         *
         * @throws AssertionError if any of the tests fails.
         */
        fun check(testClass: TestClass, testStructure: CTestStructure, options: Options<*, *>) {
            LinChecker(testClass, testStructure, options).check()
        }
    }
}