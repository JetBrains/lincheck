/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
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

package org.jetbrains.kotlinx.lincheck.test.verifier.serializability

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.serializability.SerializabilityVerifier
import org.junit.Test

@StressCTest(actorsBefore = 2,
             threads = 2, actorsPerThread = 4,
             actorsAfter = 2,
             verifier = SerializabilityVerifier::class)
class SerializableQueueSimulationTest {
    val q = SerializableQueueSimulation<Int>()

    @Operation
    fun push(@Param(gen = IntGen::class) item: Int) = q.push(item)

    @Operation
    fun pop(): Int? = q.pop()

    @Test
    fun test() = LinChecker.check(SerializableQueueSimulationTest::class.java)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SerializableQueueSimulationTest

        if (q != other.q) return false

        return true
    }

    override fun hashCode(): Int {
        return q.hashCode()
    }


}
