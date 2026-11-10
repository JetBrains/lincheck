/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmus

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertSame
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmusTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Read/read coherence matrix, using the JCStress case names for individual mode combinations. */
class CoRRMemoryModelTest {

    @Test
    fun TestCoRR__PLAIN_PLAIN_PLAIN() = checkCoRR(AccessMode.PLAIN, AccessMode.PLAIN, AccessMode.PLAIN)

    @Test
    fun TestCoRR__PLAIN_PLAIN_OPAQUE() = checkCoRR(AccessMode.PLAIN, AccessMode.PLAIN, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__PLAIN_PLAIN_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.PLAIN, AccessMode.PLAIN, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__PLAIN_PLAIN_VOLATILE() = checkCoRR(AccessMode.PLAIN, AccessMode.PLAIN, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__PLAIN_OPAQUE_PLAIN() = checkCoRR(AccessMode.PLAIN, AccessMode.OPAQUE, AccessMode.PLAIN)

    @Test
    fun TestCoRR__PLAIN_OPAQUE_OPAQUE() = checkCoRR(AccessMode.PLAIN, AccessMode.OPAQUE, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__PLAIN_OPAQUE_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.PLAIN, AccessMode.OPAQUE, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__PLAIN_OPAQUE_VOLATILE() = checkCoRR(AccessMode.PLAIN, AccessMode.OPAQUE, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__PLAIN_RELEASE_ACQUIRE_PLAIN() =
        checkCoRR(AccessMode.PLAIN, AccessMode.RELEASE_ACQUIRE, AccessMode.PLAIN)

    @Test
    fun TestCoRR__PLAIN_RELEASE_ACQUIRE_OPAQUE() =
        checkCoRR(AccessMode.PLAIN, AccessMode.RELEASE_ACQUIRE, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__PLAIN_RELEASE_ACQUIRE_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.PLAIN, AccessMode.RELEASE_ACQUIRE, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__PLAIN_RELEASE_ACQUIRE_VOLATILE() =
        checkCoRR(AccessMode.PLAIN, AccessMode.RELEASE_ACQUIRE, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__PLAIN_VOLATILE_PLAIN() = checkCoRR(AccessMode.PLAIN, AccessMode.VOLATILE, AccessMode.PLAIN)

    @Test
    fun TestCoRR__PLAIN_VOLATILE_OPAQUE() = checkCoRR(AccessMode.PLAIN, AccessMode.VOLATILE, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__PLAIN_VOLATILE_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.PLAIN, AccessMode.VOLATILE, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__PLAIN_VOLATILE_VOLATILE() = checkCoRR(AccessMode.PLAIN, AccessMode.VOLATILE, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__OPAQUE_PLAIN_PLAIN() = checkCoRR(AccessMode.OPAQUE, AccessMode.PLAIN, AccessMode.PLAIN)

    @Test
    fun TestCoRR__OPAQUE_PLAIN_OPAQUE() = checkCoRR(AccessMode.OPAQUE, AccessMode.PLAIN, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__OPAQUE_PLAIN_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.OPAQUE, AccessMode.PLAIN, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__OPAQUE_PLAIN_VOLATILE() = checkCoRR(AccessMode.OPAQUE, AccessMode.PLAIN, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__OPAQUE_OPAQUE_PLAIN() = checkCoRR(AccessMode.OPAQUE, AccessMode.OPAQUE, AccessMode.PLAIN)

    @Test
    fun TestCoRR__OPAQUE_OPAQUE_OPAQUE() = checkCoRR(AccessMode.OPAQUE, AccessMode.OPAQUE, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__OPAQUE_OPAQUE_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.OPAQUE, AccessMode.OPAQUE, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__OPAQUE_OPAQUE_VOLATILE() = checkCoRR(AccessMode.OPAQUE, AccessMode.OPAQUE, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__OPAQUE_RELEASE_ACQUIRE_PLAIN() =
        checkCoRR(AccessMode.OPAQUE, AccessMode.RELEASE_ACQUIRE, AccessMode.PLAIN)

    @Test
    fun TestCoRR__OPAQUE_RELEASE_ACQUIRE_OPAQUE() =
        checkCoRR(AccessMode.OPAQUE, AccessMode.RELEASE_ACQUIRE, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__OPAQUE_RELEASE_ACQUIRE_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.OPAQUE, AccessMode.RELEASE_ACQUIRE, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__OPAQUE_RELEASE_ACQUIRE_VOLATILE() =
        checkCoRR(AccessMode.OPAQUE, AccessMode.RELEASE_ACQUIRE, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__OPAQUE_VOLATILE_PLAIN() = checkCoRR(AccessMode.OPAQUE, AccessMode.VOLATILE, AccessMode.PLAIN)

    @Test
    fun TestCoRR__OPAQUE_VOLATILE_OPAQUE() = checkCoRR(AccessMode.OPAQUE, AccessMode.VOLATILE, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__OPAQUE_VOLATILE_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.OPAQUE, AccessMode.VOLATILE, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__OPAQUE_VOLATILE_VOLATILE() = checkCoRR(AccessMode.OPAQUE, AccessMode.VOLATILE, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_PLAIN_PLAIN() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.PLAIN, AccessMode.PLAIN)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_PLAIN_OPAQUE() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.PLAIN, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_PLAIN_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.PLAIN, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_PLAIN_VOLATILE() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.PLAIN, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_OPAQUE_PLAIN() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.OPAQUE, AccessMode.PLAIN)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_OPAQUE_OPAQUE() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.OPAQUE, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_OPAQUE_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.OPAQUE, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_OPAQUE_VOLATILE() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.OPAQUE, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_RELEASE_ACQUIRE_PLAIN() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.RELEASE_ACQUIRE, AccessMode.PLAIN)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_RELEASE_ACQUIRE_OPAQUE() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.RELEASE_ACQUIRE, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_RELEASE_ACQUIRE_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.RELEASE_ACQUIRE, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_RELEASE_ACQUIRE_VOLATILE() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.RELEASE_ACQUIRE, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_VOLATILE_PLAIN() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.VOLATILE, AccessMode.PLAIN)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_VOLATILE_OPAQUE() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.VOLATILE, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_VOLATILE_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.VOLATILE, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__RELEASE_ACQUIRE_VOLATILE_VOLATILE() =
        checkCoRR(AccessMode.RELEASE_ACQUIRE, AccessMode.VOLATILE, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__VOLATILE_PLAIN_PLAIN() = checkCoRR(AccessMode.VOLATILE, AccessMode.PLAIN, AccessMode.PLAIN)

    @Test
    fun TestCoRR__VOLATILE_PLAIN_OPAQUE() = checkCoRR(AccessMode.VOLATILE, AccessMode.PLAIN, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__VOLATILE_PLAIN_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.VOLATILE, AccessMode.PLAIN, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__VOLATILE_PLAIN_VOLATILE() = checkCoRR(AccessMode.VOLATILE, AccessMode.PLAIN, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__VOLATILE_OPAQUE_PLAIN() = checkCoRR(AccessMode.VOLATILE, AccessMode.OPAQUE, AccessMode.PLAIN)

    @Test
    fun TestCoRR__VOLATILE_OPAQUE_OPAQUE() = checkCoRR(AccessMode.VOLATILE, AccessMode.OPAQUE, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__VOLATILE_OPAQUE_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.VOLATILE, AccessMode.OPAQUE, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__VOLATILE_OPAQUE_VOLATILE() = checkCoRR(AccessMode.VOLATILE, AccessMode.OPAQUE, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__VOLATILE_RELEASE_ACQUIRE_PLAIN() =
        checkCoRR(AccessMode.VOLATILE, AccessMode.RELEASE_ACQUIRE, AccessMode.PLAIN)

    @Test
    fun TestCoRR__VOLATILE_RELEASE_ACQUIRE_OPAQUE() =
        checkCoRR(AccessMode.VOLATILE, AccessMode.RELEASE_ACQUIRE, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__VOLATILE_RELEASE_ACQUIRE_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.VOLATILE, AccessMode.RELEASE_ACQUIRE, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__VOLATILE_RELEASE_ACQUIRE_VOLATILE() =
        checkCoRR(AccessMode.VOLATILE, AccessMode.RELEASE_ACQUIRE, AccessMode.VOLATILE)

    @Test
    fun TestCoRR__VOLATILE_VOLATILE_PLAIN() = checkCoRR(AccessMode.VOLATILE, AccessMode.VOLATILE, AccessMode.PLAIN)

    @Test
    fun TestCoRR__VOLATILE_VOLATILE_OPAQUE() = checkCoRR(AccessMode.VOLATILE, AccessMode.VOLATILE, AccessMode.OPAQUE)

    @Test
    fun TestCoRR__VOLATILE_VOLATILE_RELEASE_ACQUIRE() =
        checkCoRR(AccessMode.VOLATILE, AccessMode.VOLATILE, AccessMode.RELEASE_ACQUIRE)

    @Test
    fun TestCoRR__VOLATILE_VOLATILE_VOLATILE() =
        checkCoRR(AccessMode.VOLATILE, AccessMode.VOLATILE, AccessMode.VOLATILE)

    private fun checkCoRR(write: AccessMode, firstRead: AccessMode, secondRead: AccessMode) {
        val bothReadsPlain = firstRead == AccessMode.PLAIN && secondRead == AccessMode.PLAIN
        val outcomes = buildSet {
            for (first in 0..2) {
                for (second in 0..2) {
                    if (bothReadsPlain || first <= second) add(first to second)
                }
            }
        }
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var first = -1
            var second = -1
            val writer = thread {
                write.write(x, 1)
                write.write(x, 2)
            }
            val reader = thread {
                first = firstRead.read(x)
                second = secondRead.read(x)
            }
            writer.join()
            reader.join()
            first to second
        }
    }

    enum class AccessMode {
        PLAIN, OPAQUE, RELEASE_ACQUIRE, VOLATILE;

        fun read(x: AtomicInteger): Int = when (this) {
            PLAIN -> x.getPlain()
            OPAQUE -> x.getOpaque()
            RELEASE_ACQUIRE -> x.getAcquire()
            VOLATILE -> x.get()
        }

        fun write(x: AtomicInteger, value: Int) {
            when (this) {
                PLAIN -> x.setPlain(value)
                OPAQUE -> x.setOpaque(value)
                RELEASE_ACQUIRE -> x.setRelease(value)
                VOLATILE -> x.set(value)
            }
        }
    }
}
