/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.datastructures

import org.jetbrains.kotlinx.lincheck.IncorrectResultsFailure
import org.jetbrains.lincheck_test.AbstractLincheckIntegrationTest
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy

/**
 * Should fail with invalid execution results.
 */
class FaaQueueTest : AbstractLincheckIntegrationTest(IncorrectResultsFailure::class) {

    private val faaQueue = FAAQueue<Int>()

    @Operation
    fun dequeue() = faaQueue.dequeue()

    @Operation
    fun enqueue(x: Int) = faaQueue.enqueue(x)

    override fun LincheckSettings.customize() {
        if (strategy is TestingStrategy.Stress) scenarioGeneration {
            invocationsPerScenario = 10_000_000
            randomScenarios = 0
        }
        scenarioGeneration.addCustomScenario {
            initial { actor(FaaQueueTest::dequeue) }
            parallel {
                thread {
                    actor(FaaQueueTest::enqueue, 1)
                    actor(FaaQueueTest::enqueue, 1)
                    actor(FaaQueueTest::dequeue)
                    actor(FaaQueueTest::enqueue, 0)
                }
                thread {
                    actor(FaaQueueTest::enqueue, 1)
                }
            }
            post { actor(FaaQueueTest::dequeue) }
        }
    }
}
