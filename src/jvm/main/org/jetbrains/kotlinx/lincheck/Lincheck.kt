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

    fun <T> runConcurrentDataStructureTest(configure: DataStructureTestConfiguration<T>.() -> Unit) {
        val configuration = DataStructureTestConfiguration<T>().apply(configure)
        val testClass = configuration.getTestClass()
        val testStructure = configuration.getCTestStructure()
        val options = configuration.getOptions()
        LinChecker(testClass, testStructure, options).check()
        // val method = ConcurrentHashMap<Int, String>::contains.javaMethod!!
    }

    class DataStructureTestConfiguration<T> {
        private val _operations: MutableList<Operation> = mutableListOf()
        internal val operations: List<Operation> get() = _operations

        fun operation(function: KFunction<*>): Operation {
            val method = function.javaMethod ?: error("Cannot get Java method for $function")
            return Operation(method)
        }

        fun operation1(function: KFunction1<*, *>): Operation {
            val method = function.javaMethod ?: error("Cannot get Java method for $function")
            return Operation(method)
        }

        fun operation2(function: KFunction2<*, *, *>): Operation {
            val method = function.javaMethod ?: error("Cannot get Java method for $function")
            return Operation(method)
        }

    }

    class Operation(val method: Method) {
        var runOnce: Boolean = false
        var blocking: Boolean = false
        var causesBlocking: Boolean = false
        var cancelOnSuspension: Boolean = false
        var promptCancellation: Boolean = false
    }
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
    TODO()
}

private fun Lincheck.DataStructureTestConfiguration<*>.getOptions(): Options<*, *> {
    TODO()
}

private fun Lincheck.DataStructureTestConfiguration<*>.getCTestStructure(): CTestStructure {
    val randomProvider = RandomProvider()
    val parameterGenerators = mutableListOf<ParameterGenerator<*>>()
    val actorsGenerators: List<ActorGenerator> = operations.map { operation ->
        ActorGenerator(
            operation.method,
            operation.method.parameterTypes.map { parameterClass ->
                val parameterGenerator = parameterClass.getDefaultParameterGenerator(randomProvider)
                check(parameterGenerator != null) {
                    "No default parameter generator found for ${parameterClass.name}"
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

internal const val DEFAULT_INVOCATIONS_COUNT = 50_000