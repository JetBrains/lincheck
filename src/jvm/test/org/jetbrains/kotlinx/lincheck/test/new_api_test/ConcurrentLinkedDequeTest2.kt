/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.test.new_api_test

import org.jetbrains.kotlinx.lincheck.new_api.ExperimentalLincheckApi
import org.jetbrains.kotlinx.lincheck.new_api.QuiescentConsistency
import org.jetbrains.kotlinx.lincheck.new_api.runLincheckTest
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedDeque

@OptIn(ExperimentalLincheckApi::class)
class ConcurrentLinkedDequeTest2 {
    @Test
    fun extensivelyCustomizedTestWithNonTrivialHazards() {
        runLincheckTest({ ConcurrentLinkedDeque<Int>() }) {
            with(it) {
                operation(::add)
                // TODO: the specification below is also correct but not idiomatic. IDEA inspection?
                operation(ConcurrentLinkedDeque<Int>::contains)
                operation("offer", ::offer) // custom operation names
                nonParallel {
                    operation(::peek)
                    // TODO: to distinguish overloaded functions, we need specialized `operation<number-of-parameters>` functions.
                    operation0(::remove) // remove()
                    operation1(::remove) // remove(element)
                }
                operation(HashMap<Int, Int>::put) // TODO: how to forbid non-related functions at compile time? Printing an error + IDEA inspection should be fine.
                // We need a way to specify operations with custom implementation.
                operation0("myPoll") {
                    maxThreads = 4 // TODO: incorrect, should not be accessible here.
                    poll() // TODO: this is incorrect, and it would be great to make using "it" incorrect here. How?
                }
                operation0("myPoll") {
                    poll() // this is correct.
                }
                operation1("offer") { element: Int ->
                    offer(element) // this is also correct.
                }

                operation(
                    ::peek,
                    blocking = true,
                    abortable = false
                ) // TODO: looks good but using multiple boolean parameters is weird
                operation("offer", ::offer, blocking = true, abortable = false)
                operation1("offer", blocking = true, abortable = false) { element: Int ->
                    offer(element) // this is also correct.
                }
                addValidationFunction(::isEmpty)
                addValidationFunction("isEmpty", ::isEmpty)
                addValidationFunction("noMemoryLeaks") { isEmpty() }

                addCustomScenario {
                    init {
                        operation(::addAll, listOf(1, 2, 3, 4, 5, 6))
                    }
                    thread {
                        operation(::isEmpty)
                        operation("poll", ::poll)
                        operation("peek") { peek() }
                    }
                    thread {
                        operation(::offer, 1)
                        operation("add", ::add, 2)
                        operation("myAdd", 3) { element -> add(element) }
                    }
                }

                testingTimeInSeconds = 3

                correctnessProperty = QuiescentConsistency
                checkObstructionFreedom = true
                sequentialSpecification = { ArrayDeque<Int>() }

                maxThreads = 4
                maxOperationsInThread = 5
            }
        }
    }
}
