/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import kotlinx.serialization.Serializable

/**
 * Helps to print a magic string in output to reproduce the same test execution
 */
@Serializable
data class ReproduceSettings(
    val randomSeedGeneratorSeed: Long
)

class ReproduceSettingsFactory(
    private val seedGeneratorSeed: Long
) {

    fun createReproduceSettings() = ReproduceSettings(seedGeneratorSeed)

}