/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.detectFailure
import org.jetbrains.lincheck.scenario.ExecutionResult
import org.jetbrains.lincheck.scenario.ExecutionScenario
import org.jetbrains.lincheck.scenario.parallelResults
import org.jetbrains.lincheck_test.strategy.eventstructure.getValue
import org.jetbrains.lincheck_test.util.checkLincheckOutput
import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import org.junit.Assert.*
import org.junit.Test

open class MultiThreadProvenanceFixture {
    private val left = Cell()
    private val right = Cell()

    class Cell {
        @JvmField var value = 0
    }

    @Operation
    fun writeLeft() {
        left.value = 1
        left.value = 2
        left.value = 3
    }

    @Operation
    fun writeRight() {
        right.value = 10
        right.value = 20
        right.value = 30
    }

    @Operation
    fun readValues(): Int {
        val first = left.value
        val second = right.value
        return first + second
    }
}

class EventStructureMultiThreadProvenanceTest : MultiThreadProvenanceFixture() {
    @Test
    fun multipleSourcesAcrossFourThreads() {
        val failure = ModelCheckingOptions()
            .useExperimentalModelChecking()
            .memoryModel(MemoryModel.RelaxedJava)
            .iterations(0)
            .invocationsPerIteration(10_000)
            .minimizeFailedScenario(false)
            .addCustomScenario {
                parallel {
                    thread { actor(::writeLeft) }
                    thread { actor(::writeRight) }
                    thread { actor(::readValues) }
                    thread { actor(::readValues) }
                }
            }
            .verifier(MultipleReadSourcesVerifier::class.java)
            .detectFailure(this::class.java)
        val trace = checkNotNull(failure?.trace).trace
        val reads = trace.filter { it.dependency >= 0 }
        val writes = trace.filter { it.isDependency }
        assertEquals(4, reads.size)
        assertEquals(4, writes.size)
        assertEquals(setOf(0, 1), writes.map { it.iThread }.toSet())
        assertEquals(setOf(2, 3), reads.map { it.iThread }.toSet())
        assertEquals(reads.map { it.dependency }.toSet(), writes.map { it.eventId }.toSet())
        failure.checkLincheckOutput("event_structure_read_provenance_four_threads")
    }
}

class MultipleReadSourcesVerifier(@Suppress("UNUSED_PARAMETER") testClass: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
        val reads = results!!.parallelResults
        return getValue<Int>(reads[2].single()!!) != 11 || getValue<Int>(reads[3].single()!!) != 22
    }
}
