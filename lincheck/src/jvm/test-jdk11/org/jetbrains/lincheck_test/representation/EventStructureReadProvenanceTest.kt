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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KFunction

open class ReadProvenanceFixture {
    private val wrapper = Wrapper()
    private val array = intArrayOf(0)
    private val atomic = AtomicInteger(0)

    class Wrapper {
        @JvmField var value = 0
    }

    @Operation
    fun writeField() {
        wrapper.value = 1
        wrapper.value = 2
    }

    @Operation
    fun readField(): Int = wrapper.value

    @Operation
    fun writeArray() {
        array[0] = 1
        array[0] = 2
    }

    @Operation
    fun readArray(): Int = array[0]

    @Operation
    fun writeAtomic() {
        atomic.setPlain(1)
        atomic.setPlain(2)
    }

    @Operation
    fun readAtomic(): Int = atomic.getPlain()

    @Operation
    fun writeReflectively() {
        val field = Wrapper::class.java.getField("value")
        field.setInt(wrapper, 1)
        field.setInt(wrapper, 2)
    }

    @Operation
    fun readReflectively(): Int = Wrapper::class.java.getField("value").getInt(wrapper)

    @Operation
    fun writeAtomicUpdate() {
        atomic.getAndIncrement()
        atomic.setPlain(2)
    }

    @Operation
    fun readTwice(): Int {
        val first = wrapper.value
        val second = wrapper.value
        return if (first == 1 && second == 1) 1 else -1
    }

    @Operation
    fun atomicUpdateAndRead(): Int {
        atomic.set(1)
        atomic.getAndIncrement()
        return atomic.get()
    }
}

class EventStructureReadProvenanceTest : ReadProvenanceFixture() {
    @Test
    fun relaxedJavaFields() = checkTrace("fields", ::writeField, ::readField)

    @Test
    fun releaseAcquireFields() = checkTrace("release_acquire", ::writeField, ::readField, MemoryModel.ReleaseAcquire)

    @Test
    fun arrays() = checkTrace("arrays", ::writeArray, ::readArray)

    @Test
    fun atomicCalls() = checkTrace("atomics", ::writeAtomic, ::readAtomic)

    @Test
    fun atomicUpdateSource() = checkTrace("atomic_update", ::writeAtomicUpdate, ::readAtomic)

    @Test
    fun reflectiveCalls() = checkTrace("reflection", ::writeReflectively, ::readReflectively)

    @Test
    fun sharedSource() = checkTrace("shared_source", ::writeField, ::readTwice, expectedReads = 2)

    @Test
    fun sequentialConsistency() = checkTrace(
        "sc", ::writeField, ::readField, MemoryModel.SequentialConsistency, expectedReads = 0
    )

    @Test
    fun interleavingStrategy() = checkTrace("interleaving", ::writeField, ::readField, null, expectedReads = 0)

    @Test
    fun latestWrite() = checkTrace("latest", ::writeField, ::readField, expectedReads = 0, rejectFirst = true)

    @Test
    fun atomicUpdateDoesNotReadFromItself() = checkTrace(
        "atomic_latest", ::atomicUpdateAndRead, ::readAtomic, expectedReads = 0, rejectFirst = true
    )

    @Test
    fun futureWritesDoNotLabelLatestRead() = checkTrace(
        "future_writes", ::writeField, ::readField, expectedReads = 0, rejectFirst = true, postWrite = true
    )

    private fun checkTrace(
        name: String,
        write: KFunction<*>,
        read: KFunction<*>,
        memoryModel: MemoryModel? = MemoryModel.RelaxedJava,
        expectedReads: Int = 1,
        rejectFirst: Boolean = false,
        postWrite: Boolean = false,
    ) {
        val failure = ModelCheckingOptions()
            .apply {
                if (memoryModel != null) {
                    useExperimentalModelChecking()
                    memoryModel(memoryModel)
                }
            }
            .iterations(0)
            .minimizeFailedScenario(false)
            .addCustomScenario {
                parallel {
                    thread { actor(write) }
                    thread { actor(read) }
                }
                if (postWrite) {
                    post { actor(write) }
                }
            }
            .verifier(if (rejectFirst) FailingVerifier::class.java else ReadOneVerifier::class.java)
            .detectFailure(this::class.java)
        val trace = checkNotNull(failure?.trace).trace
        val reads = trace.filter { it.dependency >= 0 }
        val writes = trace.filter { it.isDependency }
        assertEquals(expectedReads, reads.size)
        assertEquals(reads.map { it.dependency }.toSet(), writes.map { it.eventId }.toSet())
        assertTrue(reads.none { it.dependency == it.eventId })
        if (expectedReads > 0) assertEquals(1, writes.size)
        failure.checkLincheckOutput("event_structure_read_provenance_$name")
    }
}

class ReadOneVerifier(@Suppress("UNUSED_PARAMETER") testClass: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean =
        getValue<Int>(results!!.parallelResults[1].single()!!) != 1
}
