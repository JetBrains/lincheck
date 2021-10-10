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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.durable.BufferedDurableLinearizabilityVerifier
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.durable.DurableLinearizabilityVerifier
import org.objectweb.asm.ClassVisitor

interface ExecutionCallback {
    fun onStart(iThread: Int)
    fun beforeInit(recoverModel: RecoverabilityModel)
    fun beforeParallel()
    fun beforePost()
    fun afterPost()
    fun onActorStart(iThread: Int)
    fun onFinish(iThread: Int)
    fun onEnterActorBody(iThread: Int, iActor: Int)
    fun onExitActorBody(iThread: Int, iActor: Int)
    fun getCrashes(): List<List<CrashError>>
}

private object NoRecoverExecutionCallBack : ExecutionCallback {
    override fun onStart(iThread: Int) {}
    override fun beforeInit(recoverModel: RecoverabilityModel) {}
    override fun beforeParallel() {}
    override fun beforePost() {}
    override fun afterPost() {}
    override fun onActorStart(iThread: Int) {}
    override fun onFinish(iThread: Int) {}
    override fun onEnterActorBody(iThread: Int, iActor: Int) {}
    override fun onExitActorBody(iThread: Int, iActor: Int) {}
    override fun getCrashes() = emptyList<List<CrashError>>()
}


internal enum class StrategyRecoveryOptions {
    STRESS, MANAGED;

    fun createCrashTransformer(cv: ClassVisitor): ClassVisitor = when (this) {
        STRESS -> CrashRethrowTransformer(CrashTransformer(cv))
        MANAGED -> CrashRethrowTransformer(cv) // add crashes in ManagedStrategyTransformer
    }

    fun isStress() = this == STRESS
}

enum class Recover {
    NO_RECOVER,
    NRL,
    NRL_NO_CRASHES,
    DURABLE,
    DETECTABLE_EXECUTION,
    BUFFERED_DURABLE;

    internal fun createModel(strategyRecoveryOptions: StrategyRecoveryOptions) = when (this) {
        NO_RECOVER -> NoRecoverModel
        NRL -> NRLModel(true, strategyRecoveryOptions)
        NRL_NO_CRASHES -> NRLModel(false, strategyRecoveryOptions)
        DURABLE -> DurableModel(strategyRecoveryOptions)
        DETECTABLE_EXECUTION -> DetectableExecutionModel(strategyRecoveryOptions)
        BUFFERED_DURABLE -> BufferedDurableModel(strategyRecoveryOptions)
    }
}

interface RecoverabilityModel {
    val crashes: Boolean

    fun needsTransformation(): Boolean
    fun createTransformer(cv: ClassVisitor, clazz: Class<*>): ClassVisitor
    fun createTransformerWrapper(cv: ClassVisitor, clazz: Class<*>) = cv
    fun createActorCrashHandlerGenerator(): ActorCrashHandlerGenerator
    fun systemCrashProbability(): Double
    fun defaultExpectedCrashes(): Int
    fun getExecutionCallback(): ExecutionCallback
    fun createProbabilityModel(statistics: Statistics, maxCrashes: Int): ProbabilityModel
    fun checkTestClass(testClass: Class<*>) {}
    val awaitSystemCrashBeforeThrow: Boolean
    val verifierClass: Class<out Verifier>

    fun nonSystemCrashSupported() = systemCrashProbability() < 1.0
    fun reset(loader: ClassLoader, scenario: ExecutionScenario)

    companion object {
        val default = Recover.NO_RECOVER.createModel(StrategyRecoveryOptions.STRESS)
    }
}

internal object NoRecoverModel : RecoverabilityModel {
    override val crashes get() = false
    override fun needsTransformation() = false
    override fun createTransformer(cv: ClassVisitor, clazz: Class<*>) = cv
    override fun createActorCrashHandlerGenerator() = ActorCrashHandlerGenerator()
    override fun systemCrashProbability() = 0.0
    override fun defaultExpectedCrashes() = 0
    override fun getExecutionCallback(): ExecutionCallback = NoRecoverExecutionCallBack
    override fun createProbabilityModel(statistics: Statistics, maxCrashes: Int) = NoCrashesProbabilityModel(statistics, maxCrashes)
    override val awaitSystemCrashBeforeThrow get() = true
    override val verifierClass get() = LinearizabilityVerifier::class.java
    override fun reset(loader: ClassLoader, scenario: ExecutionScenario) {}
}

internal abstract class RecoverabilityModelImpl : RecoverabilityModel {
    private lateinit var state: NVMState
    override fun getExecutionCallback() = state
    override fun needsTransformation() = true
    override fun reset(loader: ClassLoader, scenario: ExecutionScenario) {
        state = NVMState(scenario, this)
        NVMStateHolder.setState(loader, state)
    }
}

private class NRLModel(
    override val crashes: Boolean,
    private val strategyRecoveryOptions: StrategyRecoveryOptions
) : RecoverabilityModelImpl() {
    override fun createActorCrashHandlerGenerator() = ActorCrashHandlerGenerator()
    override fun systemCrashProbability() = 0.1
    override fun defaultExpectedCrashes() = 10
    override fun createProbabilityModel(statistics: Statistics, maxCrashes: Int) = if (strategyRecoveryOptions.isStress())
        DetectableExecutionProbabilityModel(statistics, maxCrashes) else NoCrashesProbabilityModel(statistics, maxCrashes)

    override val awaitSystemCrashBeforeThrow get() = true
    override val verifierClass get() = LinearizabilityVerifier::class.java
    override fun createTransformer(cv: ClassVisitor, clazz: Class<*>): ClassVisitor {
        var result: ClassVisitor = RecoverabilityTransformer(cv)
        if (crashes) {
            result = strategyRecoveryOptions.createCrashTransformer(result)
        }
        return result
    }

    override fun checkTestClass(testClass: Class<*>) {
        var clazz: Class<*>? = testClass
        while (clazz !== null) {
            clazz.declaredMethods.forEach { method ->
                val isOperation = method.isAnnotationPresent(Operation::class.java)
                val isRecoverable = method.isAnnotationPresent(Recoverable::class.java)
                require(!isOperation || isRecoverable) {
                    "Every operation must have a Recovery annotation, but ${method.name} operation in ${clazz!!.name} class is not Recoverable."
                }
            }
            clazz = clazz.superclass
        }
    }
}

private open class DurableModel(val strategyRecoveryOptions: StrategyRecoveryOptions) : RecoverabilityModelImpl() {
    override val crashes get() = true
    override fun createActorCrashHandlerGenerator(): ActorCrashHandlerGenerator = DurableActorCrashHandlerGenerator()
    override fun systemCrashProbability() = 1.0
    override fun defaultExpectedCrashes() = 1
    override fun createProbabilityModel(statistics: Statistics, maxCrashes: Int) = if (strategyRecoveryOptions.isStress())
        DurableProbabilityModel(statistics, maxCrashes) else NoCrashesProbabilityModel(statistics, maxCrashes)

    override val awaitSystemCrashBeforeThrow get() = false
    override val verifierClass: Class<out Verifier> get() = DurableLinearizabilityVerifier::class.java
    override fun createTransformerWrapper(cv: ClassVisitor, clazz: Class<*>) = DurableRecoverAllGenerator(cv, clazz)
    override fun createTransformer(cv: ClassVisitor, clazz: Class<*>): ClassVisitor =
        strategyRecoveryOptions.createCrashTransformer(DurableOperationRecoverTransformer(cv, clazz))
}

private class DetectableExecutionModel(strategyRecoveryOptions: StrategyRecoveryOptions) :
    DurableModel(strategyRecoveryOptions) {
    override fun createActorCrashHandlerGenerator() = DetectableExecutionActorCrashHandlerGenerator()
    override fun defaultExpectedCrashes() = 5
    override fun createProbabilityModel(statistics: Statistics, maxCrashes: Int) = if (strategyRecoveryOptions.isStress())
        DetectableExecutionProbabilityModel(statistics, maxCrashes) else NoCrashesProbabilityModel(statistics, maxCrashes)

    override val verifierClass get() = LinearizabilityVerifier::class.java
}

private class BufferedDurableModel(strategyRecoveryOptions: StrategyRecoveryOptions) :
    DurableModel(strategyRecoveryOptions) {
    override val verifierClass get() = BufferedDurableLinearizabilityVerifier::class.java
}
