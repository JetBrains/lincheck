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

package org.jetbrains.kotlinx.lincheck.test.verifier.nlr

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.nvm.Persistent
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

@OpGroupConfig(name = "write", nonParallel = true)
@StressCTest(
    sequentialSpecification = Sequential::class
)
internal class PersistentTest {
    private val x = Persistent(0)

    @Operation
    fun read() = x.read()

    @Operation(group = "write")
    fun write(value: Int) {
        x.write(value = value)
        x.flush()
    }

    @Test
    fun test() = LinChecker.check(this::class.java)
}

internal class Sequential : VerifierState() {
    private var x = 0
    fun read() = x
    fun write(value: Int) {
        x = value
    }

    override fun extractState() = x
}
