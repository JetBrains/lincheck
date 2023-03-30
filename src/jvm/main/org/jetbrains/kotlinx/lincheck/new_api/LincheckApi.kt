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

@file:JvmName("Lincheck")

package org.jetbrains.kotlinx.lincheck.new_api

import kotlinx.coroutines.flow.Flow
import kotlin.internal.*
import kotlin.reflect.*
import kotlin.reflect.jvm.ExperimentalReflectionOnLambdas
import kotlin.reflect.jvm.reflect

fun interface LincheckTestConfigurationLambda<T: Any> {
    fun T.build(configuration: LincheckTestConfiguration<T>)
}

fun interface ScenarioBuilderLambda<T: Any> {
    fun ScenarioBuilder<T>.build()
}

fun interface ScenarioThreadBuilderLambda<T: Any> {
    fun ScenarioThreadBuilder<T>.build()
}



fun <T : Any> runLincheckTest(init: () -> T, configure: LincheckTestConfigurationLambda<T>) {
    // TODO
}

@LincheckApi
class LincheckTestConfiguration<T : Any> : OperationsDeclaration<T>() {
    var testingTimeInSeconds: Int = TODO()

    fun nonParallel(group: OperationsDeclaration<T>.() -> Unit) {

    }

    fun nonParallel(vararg operations: Operation) {}

    @ExperimentalLincheckApi
    infix fun Operation.configuredWith(configure: Operation.() -> Unit) {

    }

    fun addCustomScenario(scenario: ScenarioBuilderLambda<T>) {

    }

    fun addValidationFunction(function: KFunction0<*>) {

    }

    fun addValidationFunction(function: KProperty0<*>) {

    }

    fun addValidationFunction(name: String, function: KFunction0<*>) {

    }
    fun <R> addValidationFunction(name: String, function: T.() -> R) {

    }

    @ExperimentalLincheckApi
    var checkObstructionFreedom: Boolean = TODO()

    @ExperimentalLincheckApi
    var maxThreads: Int = TODO()

    @ExperimentalLincheckApi
    var maxOperationsInThread: Int = TODO()

    @ExperimentalLincheckApi
    var correctnessProperty: CorrectnessProperty = TODO()

    @ExperimentalLincheckApi
    var sequentialSpecification: () -> Any = TODO()
}

@LincheckApi
class ScenarioBuilder<T: Any> {
    fun init(init: ScenarioThreadBuilderLambda<T>) {

    }
    fun thread(thread: ScenarioThreadBuilderLambda<T>) {

    }
}

@LincheckApi
class ScenarioThreadBuilder<T> {
    fun operation(function: KFunction0<*>) {

    }
    fun operation(name: String, function: KFunction0<*>) {

    }
    fun <R> operation(name: String, code: T.() -> R) {

    }

    fun <P> operation(function: KFunction1<P, *>, param: P) {

    }
    fun <P> operation(name: String, function: KFunction1<P, *>, param: P) {

    }
    fun <P, R> operation(name: String, param: P, code: T.(P) -> R) {

    }
}

@DslMarker
annotation class LincheckApi

@OptIn(ExperimentalReflectionOnLambdas::class)
@LincheckApi
sealed class OperationsDeclaration<T> {
    fun operation(function: KProperty<*>): Operation = TODO()
    fun operation(function: KFunction<*>): Operation = TODO()
    fun operation(function: KFunction<*>, blocking: Boolean = true, abortable: Boolean = true): Operation  = TODO()
    fun operation(name: String, function: KFunction<*>, blocking: Boolean = true, abortable: Boolean = true): Operation  = TODO()
    fun operation(name: String, function: KFunction<*>): Operation  = TODO()

    fun operation0(function: KFunction0<*>, blocking: Boolean = true, abortable: Boolean = true) = operation(function)
    fun operation0(function: KFunction0<*>) = operation(function)
    fun operation0(name: String, function: KFunction0<*>, blocking: Boolean = true, abortable: Boolean = true) = operation(name, function)
    fun operation0(name: String, function: KFunction0<*>) = operation(name, function)

    fun operation1(function: KFunction1<*, *>) = operation(function)
    fun operation1(name: String, function: KFunction1<*, *>, blocking: Boolean = true, abortable: Boolean = true) = operation(name, function)
    fun operation1(name: String, function: KFunction1<*, *>) = operation(name, function)

    fun operation2(function: KFunction2<*, *, *>) = operation(function)
    fun operation2(name: String, function: KFunction2<*, *, *>) = operation(name, function)

    fun operation3(function: KFunction3<*, *, *, *>) = operation(function)
    fun operation3(name: String, function: KFunction3<*, *, *, *>) = operation(name, function)

    fun operation4(function: KFunction4<*, *, *, *, *>) = operation(function)
    fun operation4(name: String, function: KFunction4<*, *, *, *, *>) = operation(name, function)

    fun operation5(function: KFunction5<*, *, *, *, *, *>) = operation(function)
    fun operation5(name: String, function: KFunction5<*, *, *, *, *, *>) = operation(name, function)

    fun <R> operation0(name: String, code: suspend T.() -> R) = operation(name, code.reflect()!!)
    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    @LowPriorityInOverloadResolution
    fun <R> operation(name: String, code: T.() -> R) = operation(name, code.reflect()!!)

    fun <P, R> operation1(name: String, code: suspend T.(P) -> R) = operation(name, code.reflect()!!)
    fun <P, R> operation1(name: String, blocking: Boolean = true, abortable: Boolean = true, code: suspend T.(P) -> R) = operation(name, code.reflect()!!)
    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    @LowPriorityInOverloadResolution
    fun <P, R> operation(name: String, code: T.(P) -> R) = operation(name, code.reflect()!!)

    fun <P1, P2, R> operation2(name: String, code: suspend T.(P1, P2) -> R) = operation(name, code.reflect()!!)
    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    @LowPriorityInOverloadResolution
    fun <P1, P2, R> operation(name: String, code: T.(P1, P2) -> R) = operation(name, code.reflect()!!)
}
private const val CANNOT_EXTRACT_OPERATION_NAME_MESSAGE = "Lincheck cannot extract the operation name, please specify it explicitly via `operation(name, function)`."

interface Operation {
    @ExperimentalLincheckApi
    var abortable: Boolean // TODO: cancellable? with "onSuspension" or "onPark" suffix?

    @ExperimentalLincheckApi
    var blocking: Boolean
}




/**
 * Marks [Flow]-related API as a feature preview.
 *
 * Flow preview has **no** backward compatibility guarantees, including both binary and source compatibility.
 * Its API and semantics can and will be changed in next releases.
 *
 * Feature preview can be used to evaluate its real-world strengths and weaknesses, gather and provide feedback.
 * According to the feedback, [Flow] will be refined on its road to stabilization and promotion to a stable API.
 *
 * The best way to speed up preview feature promotion is providing the feedback on the feature.
 */
@MustBeDocumented
@Retention(value = AnnotationRetention.BINARY)
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This declaration is in a preview state and can be changed in a backwards-incompatible manner with a best-effort migration. " +
            "Its usage should be marked with '@kotlinx.coroutines.FlowPreview' or '@OptIn(kotlinx.coroutines.FlowPreview::class)' " +
            "if you accept the drawback of relying on preview API"
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.TYPEALIAS, AnnotationTarget.PROPERTY)
public annotation class ExperimentalLincheckApi