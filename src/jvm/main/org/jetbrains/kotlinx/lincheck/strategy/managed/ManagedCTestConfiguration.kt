/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.verifier.*

/**
 * A common configuration for managed strategies.
 */
abstract class ManagedCTestConfiguration(
    testClass: Class<*>,
    iterations: Int,
    threads: Int,
    actorsPerThread: Int,
    actorsBefore: Int,
    actorsAfter: Int,
    generatorClass: Class<out ExecutionGenerator>,
    verifierClass: Class<out Verifier>,
    val checkObstructionFreedom: Boolean,
    val hangingDetectionThreshold: Int,
    val invocationsPerIteration: Int,
    val guarantees: List<ManagedStrategyGuarantee>,
    minimizeFailedScenario: Boolean,
    sequentialSpecification: Class<*>,
    timeoutMs: Long,
    val eliminateLocalObjects: Boolean,

    customScenarios: List<ExecutionScenario>
) : CTestConfiguration(
    testClass = testClass,
    iterations = iterations,
    threads = threads,
    actorsPerThread = actorsPerThread,
    actorsBefore = actorsBefore,
    actorsAfter = actorsAfter,
    generatorClass = generatorClass,
    verifierClass = verifierClass,
    minimizeFailedScenario = minimizeFailedScenario,
    sequentialSpecification = sequentialSpecification,
    timeoutMs = timeoutMs,
    customScenarios = customScenarios
) {
    companion object {
        const val DEFAULT_INVOCATIONS = 10000
        const val DEFAULT_CHECK_OBSTRUCTION_FREEDOM = false
        const val DEFAULT_ELIMINATE_LOCAL_OBJECTS = true
        const val DEFAULT_HANGING_DETECTION_THRESHOLD = 101
        const val LIVELOCK_EVENTS_THRESHOLD = 10001
        val DEFAULT_GUARANTEES = listOf( // These classes use WeakHashMap, and thus, their code is non-deterministic.
            // Non-determinism should not be present in managed executions, but luckily the classes
            // can be just ignored, so that no thread context switches are added inside their methods.
            forClasses("kotlinx.coroutines.internal.StackTraceRecoveryKt").allMethods().ignore(),
            // Some atomic primitives are common and can be analyzed from a higher level of abstraction.
            forClasses { className: String -> isTrustedPrimitive(className) }.allMethods().treatAsAtomic()
        )
    }
}

/**
 * Some atomic primitives are common and can be analyzed from a higher level
 * of abstraction or can not be transformed (i.e, Unsafe or AFU).
 * Thus, we do not transform them and improve the trace representation.
 *
 * For example, in the execution trace where `AtomicLong.get()` happens,
 * we print the code location where this atomic method is called
 * instead of going deeper inside it.
 */
private fun isTrustedPrimitive(className: String) =
    className == "java.lang.invoke.VarHandle" ||
    className == "sun.misc.Unsafe" ||
    className == "jdk.internal.misc.Unsafe" ||
    className.startsWith("java.util.concurrent.atomic.Atomic") || // AFUs and Atomic[Integer/Long/...]
    className.startsWith("kotlinx.atomicfu.Atomic")
