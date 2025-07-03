/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.lincheck.datastructures.ManagedStrategyGuarantee

/**
 * Represents the configuration settings for the [ManagedStrategy].
 */
internal data class ManagedStrategySettings(
    /**
     * @property timeoutMs The maximum allowed duration (in milliseconds) for each invocation.
     * If the execution exceeds the timeout, it gets terminated.
     */
    val timeoutMs: Long,

    /**
     * @property hangingDetectionThreshold the parameter used to tune the spin-loop detector.
     *   Defines the number of times a code location should be hit after which the loop detector
     *   decides that an execution has hit an active spin-loop.
     */
    val hangingDetectionThreshold: Int,

    /**
     * @property checkObstructionFreedom Indicates whether obstruction freedom checks should be performed.
     */
    val checkObstructionFreedom: Boolean,

    /**
     * @property analyzeStdLib Controls whether standard library code should be analyzed.
     */
    val analyzeStdLib: Boolean,

    /**
     * @property guarantees A list of methods' guarantees.
     *
     * @see org.jetbrains.lincheck.datastructures.ManagedStrategyGuarantee
     */
    val guarantees: List<ManagedStrategyGuarantee>?
)