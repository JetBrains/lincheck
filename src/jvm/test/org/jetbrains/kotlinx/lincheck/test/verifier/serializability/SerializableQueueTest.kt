/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
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

    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(0)
        actorsAfter(0)
        actorsPerThread(2)
        verifier(SerializabilityVerifier::class.java)
        sequentialSpecification(SequentialIntQueue::class.java)
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
