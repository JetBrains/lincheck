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

package org.jetbrains.kotlinx.lincheck.nvm

import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner
import org.jetbrains.kotlinx.lincheck.runner.Runner
import org.jetbrains.kotlinx.lincheck.runner.UseClocks
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTestConfiguration
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressStrategy
import org.objectweb.asm.ClassVisitor
import java.lang.reflect.Method

enum class StrategyRecoveryOptions {
    STRESS, MANAGED;

    fun createCrashTransformer(cv: ClassVisitor, clazz: Class<*>): ClassVisitor = when (this) {
        STRESS -> CrashTransformer(cv, clazz)
        MANAGED -> cv // add this transformer in ManagedStrategyTransformer
    }
}

enum class Recover {
    NO_RECOVER,
    NRL,
    NRL_NO_CRASHES,
    DURABLE,
    DETECTABLE_EXECUTION,
    DURABLE_NO_CRASHES;

    fun createModel(strategyRecoveryOptions: StrategyRecoveryOptions) = when (this) {
        NO_RECOVER -> NoRecoverModel()
        NRL -> NRLModel(true, strategyRecoveryOptions)
        NRL_NO_CRASHES -> NRLModel(false, strategyRecoveryOptions)
        DURABLE -> DurableModel(true, strategyRecoveryOptions)
        DETECTABLE_EXECUTION -> DetectableExecutionModel(strategyRecoveryOptions)
        DURABLE_NO_CRASHES -> DurableModel(false, strategyRecoveryOptions)
    }
}

interface RecoverabilityModel {
    val crashes: Boolean

    fun createTransformer(cv: ClassVisitor, clazz: Class<*>): ClassVisitor
    fun createRunner(
        strategy: StressStrategy,
        testClass: Class<*>,
        validationFunctions: List<Method>,
        stateRepresentationFunction: Method?,
        testCfg: StressCTestConfiguration
    ): Runner

    fun createActorCrashHandlerGenerator(): ActorCrashHandlerGenerator
    fun systemCrashProbability(): Float
    fun defaultExpectedCrashes(): Int
    val awaitSystemCrashBeforeThrow: Boolean
}

class NoRecoverModel : RecoverabilityModel {
    override val crashes get() = false
    override fun createTransformer(cv: ClassVisitor, clazz: Class<*>) = cv
    override fun createRunner(
        strategy: StressStrategy,
        testClass: Class<*>,
        validationFunctions: List<Method>,
        stateRepresentationFunction: Method?,
        testCfg: StressCTestConfiguration
    ): Runner = ParallelThreadsRunner(
        strategy, testClass, validationFunctions, stateRepresentationFunction,
        testCfg.timeoutMs, UseClocks.RANDOM, this
    )

    override fun createActorCrashHandlerGenerator() = ActorCrashHandlerGenerator()
    override fun systemCrashProbability() = 0.0f
    override fun defaultExpectedCrashes() = 0
    override val awaitSystemCrashBeforeThrow get() = true
}

private class NRLModel(
    override val crashes: Boolean,
    private val strategyRecoveryOptions: StrategyRecoveryOptions
) : RecoverabilityModel {
    override fun createTransformer(cv: ClassVisitor, clazz: Class<*>): ClassVisitor {
        var result: ClassVisitor = RecoverabilityTransformer(cv)
        if (crashes) {
            result = strategyRecoveryOptions.createCrashTransformer(result, clazz)
        }
        return result
    }

    override fun createRunner(
        strategy: StressStrategy,
        testClass: Class<*>,
        validationFunctions: List<Method>,
        stateRepresentationFunction: Method?,
        testCfg: StressCTestConfiguration
    ): Runner = RecoverableParallelThreadsRunner(
        strategy, testClass, validationFunctions, stateRepresentationFunction,
        testCfg.timeoutMs, UseClocks.RANDOM, this
    )

    override fun createActorCrashHandlerGenerator() = ActorCrashHandlerGenerator()
    override fun systemCrashProbability() = 0.1f
    override fun defaultExpectedCrashes() = 10
    override val awaitSystemCrashBeforeThrow get() = true
}

open class DurableModel(
    override val crashes: Boolean,
    private val strategyRecoveryOptions: StrategyRecoveryOptions
) : RecoverabilityModel {
    override fun createTransformer(cv: ClassVisitor, clazz: Class<*>): ClassVisitor {
        var result: ClassVisitor = DurableOperationRecoverTransformer(cv, clazz)
        if (crashes) {
            result = strategyRecoveryOptions.createCrashTransformer(result, clazz)
        }
        return result
    }

    override fun createRunner(
        strategy: StressStrategy,
        testClass: Class<*>,
        validationFunctions: List<Method>,
        stateRepresentationFunction: Method?,
        testCfg: StressCTestConfiguration
    ): Runner = RecoverableParallelThreadsRunner(
        strategy, testClass, validationFunctions, stateRepresentationFunction,
        testCfg.timeoutMs, UseClocks.RANDOM, this
    )

    override fun createActorCrashHandlerGenerator(): ActorCrashHandlerGenerator = DurableActorCrashHandlerGenerator()
    override fun systemCrashProbability() = 1.0f
    override fun defaultExpectedCrashes() = 1
    override val awaitSystemCrashBeforeThrow get() = false
}

private class DetectableExecutionModel(strategyRecoveryOptions: StrategyRecoveryOptions) :
    DurableModel(true, strategyRecoveryOptions) {
    override fun createActorCrashHandlerGenerator() = DetectableExecutionActorCrashHandlerGenerator()
    override fun defaultExpectedCrashes() = 5
}
