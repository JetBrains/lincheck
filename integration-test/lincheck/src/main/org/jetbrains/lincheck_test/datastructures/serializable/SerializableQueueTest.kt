/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.datastructures.serializable

import org.jetbrains.lincheck_test.AbstractLincheckIntegrationTest
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck_test.*
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.verifier.SerializabilityVerifier


class SerializableQueueTest : AbstractLincheckIntegrationTest() {
    private val q = SerializableIntQueue()

    @Operation
    fun put(x: Int): Unit = q.put(x)

    @Operation
    fun poll(): Int? = q.poll()

    override fun LincheckSettings.customize() {
        scenarioGeneration {
            operationsBefore = 0
            operationsAfter = 0
            operationsPerThread = 2
            verifier = SerializabilityVerifier::class.java
            sequentialSpecification = SequentialIntQueue::class.java
        }
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
