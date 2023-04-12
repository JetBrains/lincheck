/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck

import java.util.Random

private const val SEED_GENERATOR_SEED = 0L

/**
 * Used to provide [Random] with different seeds to parameters generators and method generator
 * Is being created every time on each test to make an execution deterministic.
 */
class RandomProvider {

    private val seedGenerator = Random(SEED_GENERATOR_SEED)

    fun createRandom(): Random = Random(seedGenerator.nextLong())
}