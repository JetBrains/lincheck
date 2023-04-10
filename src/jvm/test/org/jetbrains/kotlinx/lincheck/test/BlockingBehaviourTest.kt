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

package org.jetbrains.kotlinx.lincheck.test

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

class BlockingOperationTest {
    @Operation(blocking = true)
    fun blocking(): Unit = synchronized(this) {}

    @Test
    fun test() = ModelCheckingOptions()
        .checkObstructionFreedom()
        .verifier(EpsilonVerifier::class.java)
        .actorsBefore(0)
        .actorsAfter(0)
        .check(this::class)
}

class CausesBlockingOperationTest {
    private val counter = atomic(0)

    @Operation
    fun operation() {
        while (counter.value % 2 != 0) {}
    }

    @Operation(causesBlocking = true)
    fun causesBlocking() {
        counter.incrementAndGet()
        counter.incrementAndGet()
    }

    @Test
    fun test() = ModelCheckingOptions()
        .checkObstructionFreedom()
        .verifier(EpsilonVerifier::class.java)
        .iterations(20)
        .actorsBefore(0)
        .actorsAfter(0)
        .check(this::class)
}