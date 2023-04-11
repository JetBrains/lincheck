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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.test.*
import org.jetbrains.kotlinx.lincheck.verifier.*


class SerializableQueueTest : AbstractLincheckTest() {
    private val q = SerializableIntQueue()

    @Operation
    fun put(x: Int): Unit = q.put(x)

    @Operation
    fun poll(): Int? = q.poll()

    override fun LincheckOptionsImpl.customize() {
        maxThreads = 3
        verifier = SerializabilityVerifier::class.java
        sequentialImplementation = SequentialIntQueue::class.java
    }
}

data class SequentialIntQueue(
    private val elements: MutableList<Int> = ArrayList()
) {
    fun put(x: Int) {
        elements += x
    }

    fun poll(): Int? = if (elements.isEmpty()) null else elements.removeAt(0)
}

data class SerializableIntQueue(
    private val elements: MutableList<Int> = ArrayList()
) {
    fun put(x: Int) = synchronized(this) {
        elements += x
    }

    fun poll(): Int? = synchronized(this) {
        elements.shuffle()
        return if (elements.isEmpty()) null else elements.removeAt(0)
    }
}
