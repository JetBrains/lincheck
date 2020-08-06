/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.CTestConfiguration
import org.jetbrains.kotlinx.lincheck.Options
import java.util.*

/**
 * Options for managed strategies.
 */
abstract class ManagedOptions<OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> : Options<OPT, CTEST>() {
    protected var checkObstructionFreedom = ManagedCTestConfiguration.DEFAULT_CHECK_OBSTRUCTION_FREEDOM
    protected var hangingDetectionThreshold = ManagedCTestConfiguration.DEFAULT_HANGING_DETECTION_THRESHOLD
    protected var invocationsPerIteration = ManagedCTestConfiguration.DEFAULT_INVOCATIONS
    protected val guarantees: MutableList<ManagedStrategyGuarantee> = ArrayList(ManagedCTestConfiguration.DEFAULT_GUARANTEES)
    protected var eliminateLocalObjects: Boolean = ManagedCTestConfiguration.DEFAULT_ELIMINATE_LOCAL_OBJECTS;

    /**
     * Check obstruction freedom of the concurrent algorithm.
     * In case of finding an obstruction lincheck will immediately stop and report it.
     */
    fun checkObstructionFreedom(checkObstructionFreedom: Boolean): OPT = applyAndCast {
        this.checkObstructionFreedom = checkObstructionFreedom
    }

    /**
     * Use the specified maximum number of repetitions to detect endless loops.
     * A found loop will force managed execution to switch the executing thread.
     * In case of checkObstructionFreedom enabled it will report the obstruction instead.
     */
    fun hangingDetectionThreshold(hangingDetectionThreshold: Int): OPT = applyAndCast {
        this.hangingDetectionThreshold = hangingDetectionThreshold
    }

    /**
     * The number of invocations that managed strategy may use to search for an incorrect execution.
     * In case of small scenarios with only a few "interesting" code locations a lesser than this
     * number of invocations will be used.
     */
    fun invocationsPerIteration(invocationsPerIteration: Int): OPT = applyAndCast {
        this.invocationsPerIteration = invocationsPerIteration
    }

    /**
     * Add a guarantee that methods in some classes are either correct in terms of concurrent execution or irrelevant.
     * These guarantees can be used for optimization. For example, we can add a guarantee that all methods
     * in java.util.concurrent.ConcurrentHashMap are correct and then managed strategies will not try to switch threads
     * inside the methods. We can also mark methods in logging classes irrelevant if they do influence execution result.
     */
    fun addGuarantee(guarantee: ManagedStrategyGuarantee): OPT = applyAndCast {
        guarantees.add(guarantee)
    }

    /**
     * Internal, DO NOT USE.
     */
    internal fun eliminateLocalObjects(eliminateLocalObjects: Boolean) {
        this.eliminateLocalObjects = eliminateLocalObjects;
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private inline fun <OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> ManagedOptions<OPT, CTEST>.applyAndCast(
                block: ManagedOptions<OPT, CTEST>.() -> Unit
        ) = this.apply {
            block()
        } as OPT
    }
}