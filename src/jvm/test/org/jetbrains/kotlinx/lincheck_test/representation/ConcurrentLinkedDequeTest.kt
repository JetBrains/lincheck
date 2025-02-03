/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.jupiter.api.*
import java.util.concurrent.*

class ConcurrentLinkedDequeTest {
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

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        // The custom scenario is to fix the scenario to have the same interleaving caught for both JDK8 and JDK11+.
        .addCustomScenario {
            initial {
                actor(ConcurrentLinkedDequeTest::addLast, 1)
            }
            parallel {
                thread {
                    actor(::pollFirst)
                }
                thread {
                    actor(ConcurrentLinkedDequeTest::addFirst, 0)
                    actor(::peekLast)
                }
            }
        }
        .iterations(0)
        .checkImpl(this::class.java) { failure ->
            val expectedOutputFile = if (isJdk8) "concurrent_linked_deque_jdk8.txt" else "concurrent_linked_deque.txt"
            failure.checkLincheckOutput(expectedOutputFile)
        }
}
