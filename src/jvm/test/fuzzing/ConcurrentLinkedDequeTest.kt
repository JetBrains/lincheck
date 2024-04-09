/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package fuzzing

import fuzzing.utils.AbstractFuzzerBenchmarkTest
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.reflect.jvm.jvmName


class ConcurrentLinkedDequeTest : AbstractFuzzerBenchmarkTest() {
    private val deque = ConcurrentLinkedDeque<Int>()

    @Operation
    fun addFirst(e: Int) = deque.addFirst(e)

    @Operation
    fun addLast(e: Int) = deque.addLast(e)

    @Operation
    fun pollFirst() = deque.pollFirst()

    @Operation
    fun pollLast() = deque.pollLast()

    @Operation
    fun peekFirst() = deque.peekFirst()

    @Operation
    fun peekLast() = deque.peekLast()

    override fun <O : Options<O, *>> O.customizeModelCheckingCoverage() {
        logLevel(LoggingLevel.INFO)
        iterations(40)
        coverageConfigurationForModelChecking(
            listOf(this@ConcurrentLinkedDequeTest::class.jvmName),
            listOf("java\\.util\\.concurrent.*")
        )
    }

    override fun <O : Options<O, *>> O.customizeFuzzingCoverage() {
        iterations(40)
        coverageConfigurationForFuzzing(
            listOf(this@ConcurrentLinkedDequeTest::class.jvmName),
            listOf("java\\.util\\.concurrent.*"),
        )
    }
}
