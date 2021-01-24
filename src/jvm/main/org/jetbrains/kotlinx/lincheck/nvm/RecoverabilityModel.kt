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

import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTestConfiguration
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressStrategy
import org.objectweb.asm.ClassVisitor
import java.lang.reflect.Method

enum class Recover {
    NO_RECOVER,
    NRL,
    NRL_NO_CRASHES,
    DURABLE,
    DURABLE_NO_CRASHES;

    fun createModel() = when (this) {
        NO_RECOVER -> NoRecoverModel()
        NRL -> NRLModel()
        NRL_NO_CRASHES -> NRLModel(crashes = false)
        DURABLE -> DurableModel()
        DURABLE_NO_CRASHES -> DurableModel(crashes = false)
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
}

class NRLModel(override val crashes: Boolean = true) : RecoverabilityModel {
    override fun createTransformer(cv: ClassVisitor, clazz: Class<*>): ClassVisitor {
        var result: ClassVisitor = RecoverabilityTransformer(cv)
        if (crashes) {
            result = CrashTransformer(result, clazz)
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
}

class DurableModel(override val crashes: Boolean = true) : RecoverabilityModel {
    override fun createTransformer(cv: ClassVisitor, clazz: Class<*>) = if (crashes) {
        CrashTransformer(cv, clazz)
    } else {
        cv
    }

    override fun createRunner(
        strategy: StressStrategy,
        testClass: Class<*>,
        validationFunctions: List<Method>,
        stateRepresentationFunction: Method?,
        testCfg: StressCTestConfiguration
    ): Runner = RecoverableParallelThreadsRunner(
        strategy, testClass, validationFunctions, stateRepresentationFunction,
        testCfg.timeoutMs, UseClocks.ALWAYS, this
    )

    override fun createActorCrashHandlerGenerator() = DurableActorCrashHandlerGenerator()
    override fun systemCrashProbability() = 1.0f
    override fun defaultExpectedCrashes() = 1
}
