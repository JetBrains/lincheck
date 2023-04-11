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
package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.util.*
import java.util.concurrent.*

/**
 * Checks that [Random] and [ThreadLocalRandom] are replaced
 * with deterministic implementations in the model checking mode.
 */
class RandomTransformationTest : VerifierState() {
    @Volatile
    private var a: Any = Any()

    @Operation
    fun random() {
        if (Random().nextInt() % 3 == 2) {
            // just add some code locations
            a = Any()
            a = Any()
        }
    }

    @Operation
    fun threadLocalRandom() {
        if (ThreadLocalRandom.current().nextInt() % 3 == 2) {
            // just add some code locations
            a = Any()
            a = Any()
        }
    }

    @Test
    fun test() {
        LincheckOptions {
            testingTimeInSeconds = 1
        }.check(this::class)
    }

    override fun extractState(): Any = 0 // constant state
}
