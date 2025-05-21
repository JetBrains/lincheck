/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.CTestStructure.OperationGroup
import org.jetbrains.kotlinx.lincheck.strategy.runIteration
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.ensureObjectIsTransformed
import org.jetbrains.kotlinx.lincheck.transformation.withLincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import kotlin.reflect.*
import kotlin.reflect.jvm.javaMethod
import java.lang.reflect.Method

object Lincheck {

    /**
     * This method will explore different interleavings of the [block] body and all the threads created within it,
     * searching for the first raised exception.
     *
     * @param invocations number of different interleavings of code in the [block] that should be explored.
     * @param block lambda which body will be a target for the interleavings exploration.
     */
    @JvmStatic
    @JvmOverloads
    fun runConcurrentTest(
        invocations: Int = DEFAULT_INVOCATIONS_COUNT,
        block: Runnable
    ) {
        val scenario = ExecutionScenario(
            initExecution = emptyList(),
            parallelExecution = listOf(
                listOf( // Main thread
                    Actor(method = runGPMCTestMethod, arguments = listOf(block))
                )
            ),
            postExecution = emptyList(),
            validationFunction = null
        )

        val options = ModelCheckingOptions()
            .iterations(0)
            .addCustomScenario(scenario)
            .invocationsPerIteration(invocations)
            .verifier(NoExceptionVerifier::class.java)

        val testCfg = options.createTestConfigurations(GeneralPurposeModelCheckingWrapper::class.java)
        withLincheckJavaAgent(testCfg.instrumentationMode) {
            ensureObjectIsTransformed(block)
            val verifier = testCfg.createVerifier()
            val wrapperClass = GeneralPurposeModelCheckingWrapper::class.java
            testCfg.createStrategy(wrapperClass, scenario, null, null).use { strategy ->
                val failure = strategy.runIteration(invocations, verifier)
                if (failure != null) {
                    check(strategy is ModelCheckingStrategy)
                    if (ideaPluginEnabled) {
                        runPluginReplay(
                            testCfg = testCfg,
                            testClass = wrapperClass,
                            scenario = scenario,
                            validationFunction = null,
                            stateRepresentationMethod = null,
                            invocations = invocations,
                            verifier = verifier
                        )
                        throw LincheckAssertionError(failure)
                    }
                    throw LincheckAssertionError(failure)
                }
            }
        }
    }

    fun <T> runConcurrentDataStructureTest(
        constructor: Constructor<T>,
        configure: DataStructureTestConfiguration<T>.(T) -> Unit
    ) {
        val configuration = DataStructureTestConfiguration<T>(constructor)
            .apply { configure(getInstance()) }
        val testClass = configuration.getTestClass()
        val testStructure = configuration.getCTestStructure()
        val options = configuration.getOptions()
        LinChecker(testClass, testStructure, options).check()
    }

    class DataStructureTestConfiguration<T>(internal val constructor: Constructor<T>) {
        private val _operations: MutableList<Operation> = mutableListOf()
        internal val operations: List<Operation> get() = _operations

        /**
         * TODO
         */
        fun operation(function: KFunction<*>, argumentTypes: List<KClass<*>>): Operation {
            val method = function.javaMethod ?: error("Cannot get Java method for $function")
            return Operation(method, argumentTypes).also { _operations.add(it) }
        }

        /**
         * TODO
         */
        fun operation1(function: KFunction1<*, *>): Operation {
            val method = function.javaMethod ?: error("Cannot get Java method for $function")
            return Operation(method).also { _operations.add(it) }
        }

        /**
         * TODO
         */
        fun operation2(function: KFunction2<*, *, *>): Operation {
            val method = function.javaMethod ?: error("Cannot get Java method for $function")
            return Operation(method).also { _operations.add(it) }
        }

        internal var validation: Method? = null
            private set

        /**
         * TODO
         */
        fun validation(function: KFunction<*>) {
            val method = function.javaMethod ?: error("Cannot get Java method for $function")
            validation = method
        }

        /**
         * TODO
         */
        var mode: Mode = DEFAULT_LINCHECK_MODE

        /**
         * TODO
         */
        var iterations: Int = CTestConfiguration.DEFAULT_ITERATIONS

        /**
         * TODO
         */
        var invocationsPerIteration: Int = CTestConfiguration.DEFAULT_INVOCATIONS

        /**
         * TODO
         */
        var threads: Int = CTestConfiguration.DEFAULT_THREADS

        /**
         * TODO
         */
        var actorsPerThread: Int = CTestConfiguration.DEFAULT_ACTORS_PER_THREAD

        /**
         * TODO
         */
        var actorsBefore: Int = CTestConfiguration.DEFAULT_ACTORS_BEFORE

        /**
         * TODO
         */
        var actorsAfter: Int = CTestConfiguration.DEFAULT_ACTORS_AFTER

        /**
         * TODO
         */
        var minimizeFailedScenario: Boolean = CTestConfiguration.DEFAULT_MINIMIZE_ERROR

        /**
         * TODO
         */
        var sequentialSpecification: Class<*>? = null

    }

    class Operation(val method: Method, val argumentTypes: List<KClass<*>>? = null) {
        var runOnce: Boolean = false
        var blocking: Boolean = false
        var causesBlocking: Boolean = false
        var cancelOnSuspension: Boolean = false
        var promptCancellation: Boolean = false
    }

    enum class Mode { STRESS, MODEL_CHECKING }
}

internal class GeneralPurposeModelCheckingWrapper {
    fun runGPMCTest(block: Runnable) = block.run()
}

private val runGPMCTestMethod =
    GeneralPurposeModelCheckingWrapper::class.java.getDeclaredMethod("runGPMCTest", Runnable::class.java)

/**
 * [NoExceptionVerifier] checks that the lambda passed into [Lincheck.runConcurrentTest] does not throw an exception.
 */
private class NoExceptionVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario, results: ExecutionResult): Boolean =
        results.parallelResults[0][0] !is ExceptionResult
}

private fun Lincheck.DataStructureTestConfiguration<*>.getTestClass(): Class<*> {
    val instance = getInstance()!!
    return instance::class.java
}

private fun<T> Lincheck.DataStructureTestConfiguration<T>.getInstance(): T {
    return (constructor?.invoke() as T) ?: error("No constructor for data structure was provided")
}

private fun Lincheck.DataStructureTestConfiguration<*>.getOptions(): Options<*, *> {
    // create options according to the chosen strategy
    val options = when (mode) {
        Lincheck.Mode.STRESS -> StressOptions()
        Lincheck.Mode.MODEL_CHECKING -> ModelCheckingOptions()
    }

    // fill common options
    options.iterations(iterations)
    options.invocationsPerIteration(invocationsPerIteration)
    options.threads(threads)
    options.actorsPerThread(actorsPerThread)
    options.actorsBefore(actorsBefore)
    options.actorsAfter(actorsAfter)
    options.sequentialSpecification(sequentialSpecification)

    // TODO: handle model checking specific options

    return options
}

private fun Lincheck.DataStructureTestConfiguration<*>.getCTestStructure(): CTestStructure {
    val randomProvider = RandomProvider()
    val parameterGenerators = mutableListOf<ParameterGenerator<*>>()
    val actorsGenerators: List<ActorGenerator> = operations.map { operation ->
        if (operation.argumentTypes != null) {
            check(operation.argumentTypes.size == operation.method.parameterTypes.size) {
                "Number of arguments does not match the number of parameters of the method."
            }
        }
        val method = operation.method
        val argumentTypes = operation.argumentTypes?.map { it.java } ?: method.parameterTypes.toList()
        ActorGenerator(
            method,
            argumentTypes.map { parameterType ->
                val parameterGenerator = parameterType.getDefaultParameterGenerator(randomProvider)
                check(parameterGenerator != null) {
                    "No default parameter generator found for ${parameterType.name}"
                }
                parameterGenerator.also {
                    parameterGenerators.add(it)
                }
            },
            operation.runOnce,
            operation.cancelOnSuspension,
            operation.blocking,
            operation.causesBlocking,
            operation.promptCancellation,
        )
    }
    val operationGroups: List<OperationGroup> = emptyList()
    // val validationFunction = TODO()

    // TODO: pass constructor!
    return CTestStructure(actorsGenerators, parameterGenerators, operationGroups, null, null, randomProvider)
}

private fun <T> Class<T>.getDefaultParameterGenerator(randomProvider: RandomProvider): ParameterGenerator<*>? {
    if (isEnum) {
        @Suppress("UNCHECKED_CAST")
        return CTestStructure.createEnumGenerator("", randomProvider, this as Class<Enum<*>>)
    }
    return when (this) {
        Long::class.java  , Long::class.javaPrimitiveType   -> LongGen(randomProvider, "")
        Int::class.java   , Int::class.javaPrimitiveType    -> IntGen(randomProvider, "")
        Short::class.java , Short::class.javaPrimitiveType  -> ShortGen(randomProvider, "")
        Byte::class.java  , Byte::class.javaPrimitiveType   -> ByteGen(randomProvider, "")

        Float::class.java , Float::class.javaPrimitiveType  -> FloatGen(randomProvider, "")
        Double::class.java, Double::class.javaPrimitiveType -> DoubleGen(randomProvider, "")

        Boolean::class.java, Boolean::class.javaPrimitiveType -> BooleanGen(randomProvider, "")

        String::class.java -> StringGen(randomProvider, "")

        else -> null
    }
}

private typealias Constructor<T> = () -> T

internal const val DEFAULT_INVOCATIONS_COUNT = 50_000

private val DEFAULT_LINCHECK_MODE = Lincheck.Mode.STRESS