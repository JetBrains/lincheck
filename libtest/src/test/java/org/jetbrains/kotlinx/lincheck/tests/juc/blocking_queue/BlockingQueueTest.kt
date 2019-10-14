package org.jetbrains.kotlinx.lincheck.tests.juc.blocking_queue

/*
 * #%L
 * libtest
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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.tests.AbstractLincheckTest

import java.util.ArrayList
import java.util.NoSuchElementException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue

class BlockingQueueTest : AbstractLincheckTest(false, false) {

    private val q = ArrayBlockingQueue<Int>(10)

    @Operation
    fun add(@Param(gen = IntGen::class) value: Int?): Boolean {
        return q.add(value!!)
    }

    @Operation(handleExceptionsAsResult = [NoSuchElementException::class])
    fun element(): Int? {
        return q.element()
    }

    @Operation(handleExceptionsAsResult = [NoSuchElementException::class])
    fun remove(): Int? {
        return q.remove()
    }

    @Operation(handleExceptionsAsResult = [NoSuchElementException::class])
    fun poll(): Int? {
        return q.poll()
    }

    override fun extractState(): Any {
        val elements = ArrayList<Int>()
        for (el in q) {
            elements.add(el)
        }
        return elements
    }
}

