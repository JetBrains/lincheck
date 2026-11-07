/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(Parameterized::class)
class CoRRMemoryModelTest(
    private val writeMode: AccessMode,
    private val firstReadMode: AccessMode,
    private val secondReadMode: AccessMode,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "writes={0}, reads={1}/{2}")
        fun accessModes(): List<Array<AccessMode>> = buildList {
            for (write in AccessMode.values()) {
                for (firstRead in AccessMode.values()) {
                    for (secondRead in AccessMode.values()) add(arrayOf(write, firstRead, secondRead))
                }
            }
        }
    }

    @Test
    fun testCoRR() {
        val write = writeMode
        val firstRead = firstReadMode
        val secondRead = secondReadMode
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
