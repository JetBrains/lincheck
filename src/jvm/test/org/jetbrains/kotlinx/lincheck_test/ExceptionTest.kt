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
import org.jetbrains.kotlinx.lincheck.strategy.*
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.lincheck.datastructures.Operation

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

/*
 * This test checks that Lincheck correctly handles exceptions with empty stack traces.
 *
 * JVM can throw exceptions with empty stack traces in some cases as an optimization.
 * If the same exception (from a predefined list of classes, like `ClassCastException`)
 * is thrown multiple times from the same line of code,
 * at some point the JVM will stop collecting the stack trace of this exception
 * and will return an empty stack trace.
 *
 * See:
 * - https://stackoverflow.com/questions/2411487/nullpointerexception-in-java-with-no-stacktrace
 * - https://yoshihisaonoue.wordpress.com/2021/02/07/jvm-option-xx-omitstacktraceinfastthrow/
 * - https://github.com/JetBrains/lincheck/issues/381
 *
 * To trigger this JVM behavior, in this test we use a scenario with multiple actors
 * all throwing `ClassCastException`, and enable the scenario minimization
 * to force the Lincheck to execute the code multiple times.
 * At some large enough minimization iteration, the exception will be thrown with an empty stack trace.
 */
class EmptyStackTraceExceptionTest : AbstractLincheckTest(IncorrectResultsFailure::class) {

    @Operation
    fun operation() {
        var counter = 0
        while (true) {
            var obj: Any?
            if (counter % 2 == 0) {
                obj = A()
            } else {
                obj = B()
            }
            // Cause `ClassCastException`
            obj as A

            counter++
        }
    }

    class A
    class B

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        threads(1)
        actorsBefore(0)
        actorsAfter(0)
        actorsPerThread(200)
        minimizeFailedScenario(true)
        sequentialSpecification(ExceptionTestSequentialImplementation::class.java)
    }

}


class ExceptionTestSequentialImplementation {
    fun operation() {}
    fun idle() {}
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