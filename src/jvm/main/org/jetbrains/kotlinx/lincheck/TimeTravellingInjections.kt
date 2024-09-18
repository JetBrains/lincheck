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
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.jvm.javaMethod

object TimeTravellingInjections {
    val classUnderTimeTravel: String? = System.getProperty("rr.className")
    val methodUnderTimeTravel: String? = System.getProperty("rr.methodName")

    @JvmStatic
    var firstRun = true

    @JvmStatic
    fun runWithLincheck(testClassName: String, testMethodName: String) {
        firstRun = false

        val testClass = Class.forName(testClassName)
        // TODO: get the name from the system property
        val testMethod = testClass.getMethod(testMethodName)!!

        val isStaticMethod = Modifier.isStatic(testMethod.modifiers)
        val instanceClass = if (isStaticMethod) StaticMethodWrapper::class.java else testClass

        val scenario = ExecutionScenario(
            emptyList(),
            listOf(
                listOf(
                    // takes no arguments, ok for prototype
                    if (isStaticMethod) {
                        Actor(StaticMethodWrapper::callStaticMethod.javaMethod!!, listOf(testMethod))
                    } else {
                        Actor(testMethod, emptyList())
                    }
                )
            ),
            emptyList(),
            null
        )

        val lincheckOptions = ModelCheckingOptions()
            .iterations(0)
            .invocationsPerIteration(1)
            .addCustomScenario(scenario)
            .addGuarantee(forClasses(TimeTravellingInjections::class).allMethods().ignore())
            .verifier(FailingVerifier::class.java)
            .logLevel(LoggingLevel.OFF)

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        val failure = lincheckOptions.checkImpl(instanceClass)

        val result = failure!!.results.threadsResults[0][0]
        if (result is ExceptionResult) throw result.throwable
        val trace = constructTraceForPlugin(failure, failure.trace!!)
        println(trace.joinToString("\n"))
        // Otherwise, we just finish. For simplicity, the function always returns nothing.
    }

    @JvmStatic
    fun isFirstRun(): Boolean {
        return firstRun
    }

    class FailingVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?) = false
    }
}

class StaticMethodWrapper {
    fun callStaticMethod(method: Method) {
        method.invoke(null)
    }
}

