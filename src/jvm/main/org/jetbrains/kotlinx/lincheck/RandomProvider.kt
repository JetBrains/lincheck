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

import java.util.Random

/**
 * Used to provide [Random] with different seeds to parameters generators and method generator
 * Is being created every time on each test to make an execution deterministic.
 */
class RandomProvider {
    /**
     * This field is exposed to provide it as information to reproduce the failure
     */
    val seedGeneratorSeed: Long
    private val seedGenerator: Random = Random()

    constructor() {
        seedGeneratorSeed = seedGenerator.nextLong()
        seedGenerator.setSeed(seedGeneratorSeed)
    }

    constructor(seedGeneratorSeed: Long) {
        this.seedGeneratorSeed = seedGeneratorSeed
        seedGenerator.setSeed(seedGeneratorSeed)
    }

    fun createRandom(): Random = Random(seedGenerator.nextLong())
}