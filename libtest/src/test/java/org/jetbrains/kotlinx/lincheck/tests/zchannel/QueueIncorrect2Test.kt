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
package org.jetbrains.kotlinx.lincheck.tests.zchannel

import org.jetbrains.kotlinx.lincheck.ErrorType
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.tests.AbstractLinCheckTest
import z.channel.GenericMPMCQueue

/**
 * http://landz.github.io/
 */
class QueueIncorrect2Test : AbstractLinCheckTest(expectedError = ErrorType.INCORRECT_RESULTS) {
    private val q = GenericMPMCQueue<Int>(16)

    @Operation
    fun offer(@Param(gen = IntGen::class) value: Int): Boolean = q.offer(value)

    @Operation
    fun poll(): Int? = q.poll()

    override fun extractState(): Any {
        val elements = mutableListOf<Int>()
        while (true) {
            val element = q.poll() ?: break
            elements.add(element)
        }
        return elements
    }
}