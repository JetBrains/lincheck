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

import kotlinx.cinterop.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.reflect.*

typealias CCreator = CPointer<CFunction<() -> CPointer<*>>>
typealias CDestructor = CPointer<CFunction<(CPointer<*>) -> Unit>>
typealias EqualsCFunction = CPointer<CFunction<(CPointer<*>, CPointer<*>) -> Boolean>>
typealias HashCodeCFunction = CPointer<CFunction<(CPointer<*>) -> Int>>
// write first argument represented as string to second argument preallocated memory. Third argument is second's argument size
typealias ToStringCFunction = CPointer<CFunction<(CPointer<*>, CPointer<ByteVar>, Int) -> Unit>>

internal fun applyToStringFunction(pointer: CPointer<*>, toStringFunction: ToStringCFunction, maxLen: Int = 500): String {
    val buf = ByteArray(maxLen)
    buf.usePinned { pinned ->
        toStringFunction(pointer, pinned.addressOf(0), buf.size - 1)
    }
    return buf.toKString()
}

internal class ObjectWithDestructorAndEqualsAndHashcodeAndToString(val obj: CPointer<*>,
                                                                   val destructor: CDestructor,
                                                                   val equals: EqualsCFunction,
                                                                   val hashCode: HashCodeCFunction,
                                                                   val toString: ToStringCFunction) : Finalizable {

    override fun equals(other: Any?): Boolean {
        return if(other === this) {
            true
        } else if (other is ObjectWithDestructorAndEqualsAndHashcodeAndToString) {
            equals.invoke(obj, other.obj)
        } else {
            false
        }
    }

    override fun hashCode(): Int {
        return hashCode.invoke(obj)
    }

    override fun toString(): String {
        return applyToStringFunction(obj, toString)
    }

    override fun finalize() {
        // there are no destructors in Kotlin/Native :( https://youtrack.jetbrains.com/issue/KT-44191
        destructor.invoke(obj)
    }
}

internal class ParameterGeneratorArgument(val arg: CPointer<*>,
                                          val destructor: CDestructor,
                                          val toString: ToStringCFunction) : Finalizable {
    override fun toString(): String {
        return applyToStringFunction(arg, toString)
    }

    override fun finalize() {
        // there are no destructors in Kotlin/Native :( https://youtrack.jetbrains.com/issue/KT-44191
        destructor.invoke(arg)
    }
}

internal class ConcurrentInstance(val obj: CPointer<*>, val destructor: CDestructor) : Finalizable {

    override fun finalize() {
        // there are no destructors in Kotlin/Native :( https://youtrack.jetbrains.com/issue/KT-44191
        destructor.invoke(obj)
    }
}

internal class SequentialSpecificationInstance(val obj: CPointer<*>,
                                               val destructor: CDestructor,
                                               val equalsFunction: EqualsCFunction,
                                               val hashCodeFunction: HashCodeCFunction) : Finalizable {
    override fun equals(other: Any?): Boolean {
        return if (other === this) {
            true
        } else if (other is SequentialSpecificationInstance) {
            equalsFunction.invoke(obj, other.obj)
        } else {
            false
        }
    }

    override fun hashCode(): Int {
        return hashCodeFunction.invoke(obj)
    }

    override fun finalize() {
        // there are no destructors in Kotlin/Native :( https://youtrack.jetbrains.com/issue/KT-44191
        destructor.invoke(obj)
    }
}

fun throwKotlinValidationException(message: String) {
    throw RuntimeException("Validation failure: $message")
}

class NativeAPIStressConfiguration : LincheckStressConfiguration<Any>() {
    private var initialStateCreator: CCreator? = null
    private var initialStateDestructor: CDestructor? = null
    private var sequentialSpecificationCreator: CCreator? = null
    private var sequentialSpecificationDestructor: CDestructor? = null
    private var sequentialSpecificationEquals: EqualsCFunction? = null
    private var sequentialSpecificationHashCode: HashCodeCFunction? = null
    private var initialStateSet: Boolean = false

    init {
        // default configuration
        iterations(10)
        invocationsPerIteration(500)
    }

    fun setupInvocationsPerIteration(count: Int) {
        invocationsPerIteration(count)
    }

    fun setupIterations(count: Int) {
        iterations(count)
    }

    fun setupThreads(count: Int) {
        threads(count)
    }

    fun setupActorsPerThread(count: Int) {
        actorsPerThread(count)
    }

    fun setupActorsBefore(count: Int) {
        actorsBefore(count)
    }

    fun setupActorsAfter(count: Int) {
        actorsAfter(count)
    }

    // executionGenerator
    // verifier
    fun setupRequireStateEquivalenceImplCheck(require: Boolean) {
        requireStateEquivalenceImplCheck(require)
    }

    fun setupMinimizeFailedScenario(minimizeFailedScenario: Boolean) {
        minimizeFailedScenario(minimizeFailedScenario)
    }

    fun disableVerifier() {
        verifier { sequentialSpecification -> EpsilonVerifier(sequentialSpecification) }
    }

    fun setupValidationFunction(function: CPointer<CFunction<(CPointer<*>) -> CPointer<*>>>) {
        validationFunction({
            when (this) {
                is ConcurrentInstance -> {
                    try {
                        function.invoke(this.obj)
                    } catch(e: RuntimeException) {
                        throw IllegalStateException(e)
                    }
                }
                else -> {
                    throw RuntimeException("Internal error. ValidationFunction has invoked not on ConcurrentInstance")
                }
            }
        })
    }

    fun setupInitThreadFunction(function: CPointer<CFunction<() -> Unit>>) {
        initThreadFunction { function.invoke() }
    }

    fun setupFinishThreadFunction(function: CPointer<CFunction<() -> Unit>>) {
        finishThreadFunction { function.invoke() }
    }

    fun runNativeTest(printErrorToStderr: Boolean): String {
        if (!initialStateSet) {
            val errorMessage = "Please provide initialState, skipping..."
            if (printErrorToStderr) printErr(errorMessage)
            return errorMessage
        }
        if (sequentialSpecificationCreator == null) {
            val errorMessage = "Please provide sequentialSpecification, skipping..."
            if (printErrorToStderr) printErr(errorMessage)
            return errorMessage
        }
        if (initialStateCreator != null) {
            // different initialState and sequentialSpecification
            initialState {
                ConcurrentInstance(initialStateCreator!!.invoke(), initialStateDestructor!!)
            }
        } else {
            initialState {
                ConcurrentInstance(sequentialSpecificationCreator!!.invoke(), sequentialSpecificationDestructor!!)
            }
        }
        sequentialSpecification(
            SequentialSpecification<SequentialSpecificationInstance> {
                SequentialSpecificationInstance(sequentialSpecificationCreator!!.invoke(), sequentialSpecificationDestructor!!, sequentialSpecificationEquals!!, sequentialSpecificationHashCode!!)
            }
        )
        try {
            runTest()
        } catch (e: LincheckAssertionError) {
            val errorMessage = e.toString()
            if (printErrorToStderr) printErr(errorMessage)
            return errorMessage
        }
        return ""
    }

    fun setupInitialState(
        initialStateCreator: CCreator,
        initialStateDestructor: CDestructor
    ) {
        initialStateSet = true
        this.initialStateCreator = initialStateCreator
        this.initialStateDestructor = initialStateDestructor
    }

    fun setupInitialStateAndSequentialSpecification(
        initialStateCreator: CCreator,
        initialStateDestructor: CDestructor,
        equals: EqualsCFunction,
        hashCode: HashCodeCFunction
    ) {
        initialStateSet = true
        this.initialStateCreator = null
        this.initialStateDestructor = null
        this.sequentialSpecificationCreator = initialStateCreator
        this.sequentialSpecificationDestructor = initialStateDestructor
        this.sequentialSpecificationEquals = equals
        this.sequentialSpecificationHashCode = hashCode
    }

    fun setupSequentialSpecification(
        sequentialSpecificationCreator: CCreator,
        initialStateDestructor: CDestructor,
        equals: EqualsCFunction,
        hashCode: HashCodeCFunction
    ) {
        this.sequentialSpecificationCreator = sequentialSpecificationCreator
        this.sequentialSpecificationDestructor = initialStateDestructor
        this.sequentialSpecificationEquals = equals
        this.sequentialSpecificationHashCode = hashCode
    }

    fun setupOperation1(
        op: CPointer<CFunction<(CPointer<*>) -> CPointer<*>>>,
        seq_spec: CPointer<CFunction<(CPointer<*>) -> CPointer<*>>>,
        result_destructor: CDestructor,
        result_equals: EqualsCFunction,
        result_hashCode: HashCodeCFunction,
        result_toString: ToStringCFunction,
        operationName: String,
        nonParallelGroupName: String? = null,
        useOnce: Boolean = false,
    ) = apply {
        val actorGenerator = ActorGenerator(
            function = { instance, _ ->
                when (instance) {
                    is ConcurrentInstance -> {
                        ObjectWithDestructorAndEqualsAndHashcodeAndToString(op.invoke(instance.obj), result_destructor, result_equals, result_hashCode, result_toString)
                    }
                    is SequentialSpecificationInstance -> {
                        ObjectWithDestructorAndEqualsAndHashcodeAndToString(seq_spec.invoke(instance.obj), result_destructor, result_equals, result_hashCode, result_toString)
                    }
                    else -> {
                        throw RuntimeException("Internal error. Instance has not expected type")
                    }
                }
            },
            parameterGenerators = emptyList(),
            functionName = operationName,
            useOnce = useOnce,
            isSuspendable = false,
            handledExceptions = emptyList()
        )
        actorGenerators.add(actorGenerator)
        addToOperationGroups(nonParallelGroupName, actorGenerator)
    }

    fun setupOperation2(
        arg1_gen_initial_state: CCreator,
        arg1_gen_generate: CPointer<CFunction<(CPointer<*>) -> CPointer<*>>>,
        arg1_toString: ToStringCFunction,
        arg1_destructor: CDestructor,
        op: CPointer<CFunction<(CPointer<*>, CPointer<*>) -> CPointer<*>>>,
        seq_spec: CPointer<CFunction<(CPointer<*>, CPointer<*>) -> CPointer<*>>>,
        result_destructor: CDestructor,
        result_equals: EqualsCFunction,
        result_hashCode: HashCodeCFunction,
        result_toString: ToStringCFunction,
        operationName: String,
        nonParallelGroupName: String? = null,
        useOnce: Boolean = false,
    ) = apply {
        val arg1_paramgen = object : ParameterGenerator<ParameterGeneratorArgument> {
            val state = arg1_gen_initial_state.invoke()
            override fun generate(): ParameterGeneratorArgument {
                return ParameterGeneratorArgument(arg1_gen_generate.invoke(state), arg1_destructor, arg1_toString)
            }
        }
        val actorGenerator = ActorGenerator(
            function = { instance, arguments ->
                when (instance) {
                    is ConcurrentInstance -> {
                        ObjectWithDestructorAndEqualsAndHashcodeAndToString(op.invoke(instance.obj, (arguments[0] as ParameterGeneratorArgument).arg), result_destructor, result_equals, result_hashCode, result_toString)
                    }
                    is SequentialSpecificationInstance -> {
                        ObjectWithDestructorAndEqualsAndHashcodeAndToString(seq_spec.invoke(instance.obj, (arguments[0] as ParameterGeneratorArgument).arg), result_destructor, result_equals, result_hashCode, result_toString)
                    }
                    else -> {
                        throw RuntimeException("Internal error. Instance has not expected type")
                    }
                }
            },
            parameterGenerators = listOf(arg1_paramgen),
            functionName = operationName,
            useOnce = useOnce,
            isSuspendable = false,
            handledExceptions = emptyList()
        )
        actorGenerators.add(actorGenerator)
        addToOperationGroups(nonParallelGroupName, actorGenerator)
    }

    fun setupOperation3(
        arg1_gen_initial_state: CCreator,
        arg1_gen_generate: CPointer<CFunction<(CPointer<*>) -> CPointer<*>>>,
        arg1_toString: ToStringCFunction,
        arg1_destructor: CDestructor,
        arg2_gen_initial_state: CCreator,
        arg2_gen_generate: CPointer<CFunction<(CPointer<*>) -> CPointer<*>>>,
        arg2_toString: ToStringCFunction,
        arg2_destructor: CDestructor,
        op: CPointer<CFunction<(CPointer<*>, CPointer<*>, CPointer<*>) -> CPointer<*>>>,
        seq_spec: CPointer<CFunction<(CPointer<*>, CPointer<*>, CPointer<*>) -> CPointer<*>>>,
        result_destructor: CDestructor,
        result_equals: EqualsCFunction,
        result_hashCode: HashCodeCFunction,
        result_toString: ToStringCFunction,
        operationName: String,
        nonParallelGroupName: String? = null,
        useOnce: Boolean = false,
    ) = apply {
        val arg1Paramgen = object : ParameterGenerator<ParameterGeneratorArgument> {
            val state = arg1_gen_initial_state.invoke()
            override fun generate(): ParameterGeneratorArgument {
                return ParameterGeneratorArgument(arg1_gen_generate.invoke(state), arg1_destructor, arg1_toString)
            }
        }
        val arg2Paramgen = object : ParameterGenerator<ParameterGeneratorArgument> {
            val state = arg2_gen_initial_state.invoke()
            override fun generate(): ParameterGeneratorArgument {
                return ParameterGeneratorArgument(arg2_gen_generate.invoke(state), arg2_destructor, arg2_toString)
            }
        }
        val actorGenerator = ActorGenerator(
            function = { instance, arguments ->
                when (instance) {
                    is ConcurrentInstance -> {
                        ObjectWithDestructorAndEqualsAndHashcodeAndToString(op.invoke(instance.obj, (arguments[0] as ParameterGeneratorArgument).arg, (arguments[1] as ParameterGeneratorArgument).arg), result_destructor, result_equals, result_hashCode, result_toString)
                    }
                    is SequentialSpecificationInstance -> {
                        ObjectWithDestructorAndEqualsAndHashcodeAndToString(seq_spec.invoke(instance.obj, (arguments[0] as ParameterGeneratorArgument).arg, (arguments[1] as ParameterGeneratorArgument).arg), result_destructor, result_equals, result_hashCode, result_toString)
                    }
                    else -> {
                        throw RuntimeException("Internal error. Instance has not expected type")
                    }
                }
            },
            parameterGenerators = listOf(arg1Paramgen, arg2Paramgen),
            functionName = operationName,
            useOnce = useOnce,
            isSuspendable = false,
            handledExceptions = emptyList()
        )
        actorGenerators.add(actorGenerator)
        addToOperationGroups(nonParallelGroupName, actorGenerator)
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
    logLevel(logLevel: LoggingLevel)
    sequentialSpecification(clazz: SequentialSpecification<*>?)
    */
    protected var testClass: TestClass? = null
    protected var actorGenerators = mutableListOf<ActorGenerator>()
    protected var operationGroups = mutableMapOf<String, OperationGroup>()
    protected var validationFunctions = mutableListOf<ValidationFunction>()
    protected var stateRepresentationFunction: StateRepresentationFunction? = null

    init {
        // override invocationsPerIteration
        invocationsPerIteration(500)
    }

    protected fun getTestClass(): TestClass {
        return testClass ?: throw IllegalArgumentException("initialState should be specified")
    }

    protected fun getTestStructure(): CTestStructure {
        return CTestStructure(
            actorGenerators,
            operationGroups.values.toList(),
            validationFunctions,
            stateRepresentationFunction
        )
    }

    fun runTest() {
        val failure = checkImpl() ?: return
        throw LincheckAssertionError(failure)
    }

    fun checkImpl(): LincheckFailure? {
        //printErr("iterations: $iterations, invocations: $invocationsPerIteration")
        if (invocationsPerIteration > 500) {
            printErr("WARNING invocations count is bigger than 500, that may lead to crash") // TODO remove when bug with GC will be fixed
        }
        val result = LinChecker(getTestClass(), getTestStructure(), this as StressOptions).checkImpl()
        if (testName.isNotEmpty()) {
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

    protected fun addToOperationGroups(nonParallelGroupName: String?, actorGenerator: ActorGenerator) {
        nonParallelGroupName?.let {
            if (!operationGroups.containsKey(nonParallelGroupName)) {
                operationGroups[nonParallelGroupName] = OperationGroup(nonParallelGroupName, true)
            }
            operationGroups[nonParallelGroupName]!!.actors.add(actorGenerator)
        }
    }

    fun <R> operation(
        pGens: List<ParameterGenerator<*>>,
        op: Instance.(List<Any?>) -> R,
        name: String = op.toString(),
        handleExceptionsAsResult: List<KClass<out Throwable>> = emptyList(),
        nonParallelGroupName: String? = null,
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
        addToOperationGroups(nonParallelGroupName, actorGenerator)
    }

    fun <R> operation(
        op: Instance.() -> R,
        name: String = op.toString(),
        handleExceptionsAsResult: List<KClass<out Throwable>> = emptyList(),
        nonParallelGroupName: String? = null,
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
        addToOperationGroups(nonParallelGroupName, actorGenerator)
    }

    fun <P1, R> operation(
        p1Gen: ParameterGenerator<P1>,
        op: Instance.(p1: P1) -> R,
        name: String = op.toString(),
        handleExceptionsAsResult: List<KClass<out Throwable>> = emptyList(),
        nonParallelGroupName: String? = null,
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
        addToOperationGroups(nonParallelGroupName, actorGenerator)
    }

    fun <P1, P2, R> operation(
        p1Gen: ParameterGenerator<P1>,
        p2Gen: ParameterGenerator<P2>,
        op: Instance.(p1: P1, p2: P2) -> R,
        name: String = op.toString(),
        handleExceptionsAsResult: List<KClass<out Throwable>> = emptyList(),
        nonParallelGroupName: String? = null,
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
        addToOperationGroups(nonParallelGroupName, actorGenerator)
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
        val REPORT_PERIOD_MS = 1000
        var prevReport = currentTimeMillis() - REPORT_PERIOD_MS
        repeat(iterations) { i ->
            val verifier = createVerifier() // Could be created once, but created on every iteration to save memory and finalize scenario
            // some magic computations for beautiful values
            if (currentTimeMillis() - prevReport >= REPORT_PERIOD_MS) {
                println("${i + 1}/$iterations")
                prevReport = currentTimeMillis()
            }
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
            scenario.finalize()
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