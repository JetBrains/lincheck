/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class ExceptionInParallelPartTest : AbstractLincheckTest(IncorrectResultsFailure::class) {

    @Operation
    fun operation() {
        throw IllegalStateException()
    }

    val scenario = scenario {
        parallel {
            thread {
                actor(::operation)
            }
            thread {
                actor(::operation)
            }
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(0)
        addCustomScenario(scenario)
        minimizeFailedScenario(false)
        sequentialSpecification(ExceptionTestSequentialImplementation::class.java)
    }

}

class ExceptionInInitPartTest : AbstractLincheckTest(IncorrectResultsFailure::class) {

    @Operation
    fun operation() {
        throw IllegalStateException()
    }

    @Operation
    fun idle() {}

    val scenario = scenario {
        initial {
            actor(ExceptionInInitPartTest::operation)
        }
        parallel {
            thread {
                actor(ExceptionInInitPartTest::idle)
            }
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(0)
        addCustomScenario(scenario)
        minimizeFailedScenario(false)
        sequentialSpecification(ExceptionTestSequentialImplementation::class.java)
    }

}

class ExceptionInPostPartTest : AbstractLincheckTest(IncorrectResultsFailure::class) {

    @Operation
    fun operation() {
        throw IllegalStateException()
    }

    @Operation
    fun idle() {}

    val scenario = scenario {
        parallel {
            thread {
                actor(ExceptionInPostPartTest::idle)
            }
        }
        post {
            actor(ExceptionInPostPartTest::operation)
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(0)
        addCustomScenario(scenario)
        minimizeFailedScenario(false)
        sequentialSpecification(ExceptionTestSequentialImplementation::class.java)
    }

}

class ExceptionTestSequentialImplementation {
    fun operation() {}
    fun idle() {}
}

class ExceptionTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private var canEnterForbiddenSection = false

    @Operation
    fun operation1() {
        canEnterForbiddenSection = true
        canEnterForbiddenSection = false
    }

    @Operation
    fun operation2() {
        if (canEnterForbiddenSection) throw IllegalStateException()
    }

    val scenario = scenario {
        parallel {
            thread {
                actor(ExceptionTest::operation1)
            }
            thread {
                actor(ExceptionTest::operation2)
            }
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(0)
        addCustomScenario(scenario)
        minimizeFailedScenario(false)
        if (this is StressOptions) {
            invocationsPerIteration(20_000)
        }
    }

}

class CoroutineResumedWithExceptionTest : AbstractLincheckTest(IncorrectResultsFailure::class) {

    @Operation
    suspend fun operation() {
        suspendCancellableCoroutine<Unit> { cont ->
            cont.resumeWithException(IllegalStateException())
        }
    }

    val scenario = scenario {
        parallel {
            thread {
                actor(::operation)
            }
            thread {
                actor(::operation)
            }
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(0)
        addCustomScenario(scenario)
        minimizeFailedScenario(false)
        sequentialSpecification(CoroutineExceptionTestSequentialImplementation::class.java)
    }
}

class ExceptionInCancellationHandlerTest : AbstractLincheckTest(IncorrectResultsFailure::class) {

    @Operation(cancellableOnSuspension = true)
    suspend fun operation() {
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                throw IllegalStateException()
            }
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(10)
        sequentialSpecification(CoroutineExceptionTestSequentialImplementation::class.java)
    }
}

class CoroutineExceptionTestSequentialImplementation {
    suspend fun operation() {
        suspendCancellableCoroutine<Unit> {}
    }
}