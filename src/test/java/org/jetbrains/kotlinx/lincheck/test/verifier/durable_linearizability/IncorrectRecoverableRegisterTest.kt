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
package org.jetbrains.kotlinx.lincheck.test.verifier.durable_linearizability

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.RecoverableMode.DETECTABLE_EXECUTION
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.concurrent.atomic.*

@Param(name = "key", gen = IntGen::class, conf = "0:1")
class IncorrectRecoverableRegisterTest : VerifierState() {
    val register = AtomicInteger(0)

    @Operation
    fun cas(@Param(name = "key") key: Int): Boolean {
        val result = register.compareAndSet(key, 1 - key)
        register.get()
        return result
    }

    @Operation
    fun get() = register.get()

    @Test
    fun test() {
        val options = ModelCheckingOptions().recoverable(mode = DETECTABLE_EXECUTION)
        val failure = options.checkImpl(this::class.java)
        check(failure is IncorrectResultsFailure) { "this test should fail" }
    }

    override fun extractState(): Any = register.get()
}