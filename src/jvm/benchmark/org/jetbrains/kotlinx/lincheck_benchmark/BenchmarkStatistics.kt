/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package org.jetbrains.kotlinx.lincheck_benchmark

import org.jetbrains.kotlinx.lincheck.LincheckMode
import kotlinx.serialization.Serializable

@Serializable
data class BenchmarkStatistics(
    val id: Int,
    val name: String,
    val mode: LincheckMode,
    val runningTimeNano: Long,
    val iterationsCount: Int,
    val invocationsCount: Int,
    val scenariosStatistics: List<ScenarioStatistics>,
) {
    companion object {
        private var id: Int = 0

        fun create(
            name: String,
            mode: LincheckMode,
            runningTimeNano: Long,
            iterationsCount: Int,
            invocationsCount: Int,
            scenariosStatistics: List<ScenarioStatistics>,
        ) = BenchmarkStatistics(
            ++id,
            name,
            mode,
            runningTimeNano,
            iterationsCount,
            invocationsCount,
            scenariosStatistics
        )
    }
}

@Serializable
data class ScenarioStatistics(
    val threads: Int,
    val operations: Int,
    val runningTimeNano: Long,
    val averageInvocationTimeNano: Long,
    val invocationsCount: Int,
)