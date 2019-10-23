/*-
 * #%L
 * libtest
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.tests.linearizability

import kotlinx.coroutines.sync.Mutex
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.tests.AbstractLincheckTest
import java.lang.IllegalStateException

@Param(name = "value", gen = IntGen::class, conf = "1:5")
class MutexStressTest : AbstractLincheckTest(shouldFail = false, checkObstructionFreedom = false) {
    private val mutex = Mutex(true)

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    fun tryLock(e: Int) = mutex.tryLock(e)

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    suspend fun lock(e: Int) = mutex.lock(e)

    @Operation
    fun holdsLock(e: Int) = mutex.holdsLock(e)

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    fun unlock(e: Int) = mutex.unlock(e)

    override fun extractState() = mutex.isLocked
}
