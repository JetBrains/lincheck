/*-
 * #%L
 * Lincheck
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
package org.jetbrains.kotlinx.lincheck.tests.custom.queue

import org.jetbrains.kotlinx.lincheck.ErrorType
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.tests.AbstractLinCheckTest

class WrapperQueueCorrectTest : AbstractLinCheckTest(expectedError = ErrorType.NO_ERROR) {
    private val queue = QueueSynchronized(10)

    @Operation(handleExceptionsAsResult = [QueueFullException::class])
    fun put(@Param(gen = IntGen::class) args: Int) = queue.put(args)

    @Operation(handleExceptionsAsResult = [QueueEmptyException::class])
    fun get(): Int = queue.get()

    override fun extractState(): Any = queue
}

class WrapperQueueWrong1Test : AbstractLinCheckTest(expectedError = ErrorType.INCORRECT_RESULTS) {
    private val queue = QueueWrong1(10)

    @Operation(handleExceptionsAsResult = [QueueFullException::class])
    fun put(@Param(gen = IntGen::class) x: Int) = queue.put(x)

    @Operation(handleExceptionsAsResult = [QueueEmptyException::class])
    fun get(): Int = queue.get()

    override fun extractState(): Any = queue
}

class WrapperQueueWrong2Test : AbstractLinCheckTest(expectedError = ErrorType.INCORRECT_RESULTS) {
    private val queue = QueueWrong2(10)

    @Operation(handleExceptionsAsResult = [QueueFullException::class])
    fun put(@Param(gen = IntGen::class) args: Int) = queue.put(args)

    @Operation(handleExceptionsAsResult = [QueueEmptyException::class])
    fun get(): Int = queue.get()

    override fun extractState(): Any = queue
}

class WrapperQueueWrong3Test : AbstractLinCheckTest(expectedError = ErrorType.INCORRECT_RESULTS) {
    private val queue = QueueWrong3(10)

    @Operation(handleExceptionsAsResult = [QueueFullException::class])
    fun put(@Param(gen = IntGen::class) args: Int) = queue.put(args)

    @Operation(handleExceptionsAsResult = [QueueEmptyException::class])
    fun get(): Int = queue.get()

    override fun extractState(): Any = queue
}