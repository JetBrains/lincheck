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

/**
 * This callback is plugged in to [ParallelThreadsRunner] to callect information
 * that is essential for NVM emulation during a test run.
 */
interface ExecutionCallback {
    /** This method is invoked in the beginning of each test invocation.*/
    fun beforeInit(recoverModel: RecoverabilityModel)

    /** This method is invoked after the init part and before the parallel part of a test. */
    fun beforeParallel()

    /** This method is called before the thread [threadId] starts. */
    fun onStart(threadId: Int)

    /** This method is invoked before a new actor starts. */
    fun onActorStart(threadId: Int)

    /**
     * This method is invoked before the actor's execution body starts.
     * This method may be called several time for one actor if it is re-executed.
     */
    fun onEnterActorBody(threadId: Int, actorId: Int)

    /** This method is invoked after the actor finishes. */
    fun onExitActorBody(threadId: Int, actorId: Int)

    /** This method is called after the thread [threadId] finishes. */
    fun onFinish(threadId: Int)

    /** This method is invoked after the parallel part and before the post part of a test. */
    fun beforePost()

    /** This method is invoked in the end of the test. */
    fun afterPost()

    /** This method returns crashes that occurred during the last execution. */
    fun getCrashes(): List<List<CrashError>>
}

/** Default callback with no action performed. */
private object NoRecoverExecutionCallBack : ExecutionCallback {
    override fun onStart(threadId: Int) {}
    override fun beforeInit(recoverModel: RecoverabilityModel) {}
    override fun beforeParallel() {}
    override fun beforePost() {}
    override fun afterPost() {}
    override fun onActorStart(threadId: Int) {}
    override fun onFinish(threadId: Int) {}
    override fun onEnterActorBody(threadId: Int, actorId: Int) {}
    override fun onExitActorBody(threadId: Int, actorId: Int) {}
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

/** Recovery model defines the execution details and the verification method. */
enum class Recover {
    /** No NVM support enabled. */
    NO_RECOVER,

    /**
     * Nesting-safe recoverable linearizability model.
     *
     * This model requires using of [Recoverable] methods. The recovery method is called in case of crash occurred.
     * Note that recovery method must perform the same as the method which is recovered, namely return the result such that
     * crashed method and subsequent recovery call are still linearizable. The model considers both single-thread and system-wide crashes.
     * Linearizability criterion is used for verification.
     * @see  <a href="https://www.cs.bgu.ac.il/~hendlerd/papers/NRL.pdf">Nesting-Safe Recoverable Linearizability</a>
     */
    NRL,

    /**
     * The same as [NRL], but no crashes occur. This may be used in debug purposes to temporary disable crashes,
     * but still execute under NRL model.
     */
    NRL_NO_CRASHES,

    /**
     * Durable linearizability model.
     *
     * This model considers only system-wide crashes. This model requires all successfully operations to be linearizable,
     * while the interrupted by a crash operations may be not linearizable. After a crash a structure's recovery function is called,
     * is it is markered with [DurableRecoverAll] or [DurableRecoverPerThread] annotations. Durable linearizability verification is used.
     * @see  <a href="https://www.cs.rochester.edu/u/scott/papers/2016_DISC_persistence.pdf">Durable Linearizability</a>
     */
    DURABLE,

    /**
     * Detectable execution model.
     *
     * This is a strengthening of durable linearizability, as it requires an operation to know after a crash was it completed successfully or not.
     * Practically this means that an operation is called again after a crash, and then the operation is responsible for returning a correct result.
     * Linearizability verification is used.
     * @see  <a href="http://www.cs.technion.ac.il/~erez/Papers/nvm-queue-full.pdf">Detectable execution</a>
     */
    DETECTABLE_EXECUTION,

    /**
     * Buffered durable linearizability model.
     *
     * This is a durable linearizability relaxation. This model requires that only a prefix of successfully completed operations is linearizable.
     * In practice this a sync method is used which guarantees that a data structure is persisted if this method completes successfully.
     * So buffered durable linearizability requires that all the operations before the last completed sync are linearizable.
     * @see  <a href="https://www.cs.rochester.edu/u/scott/papers/2016_DISC_persistence.pdf">Buffered Durable Linearizability</a>
     */
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
