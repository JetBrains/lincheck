/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking.snapshot

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedOptions


class ConstructorArgumentsTrackingTest : AbstractSnapshotTest() {
    class ConstructorNotThisModificationVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(
            scenario: ExecutionScenario?,
            results: ExecutionResult?
        ): Boolean {
            checkForExceptions(results)
            check(staticNode.a == 1)
            check(staticSubNode.a == 2)
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(ConstructorNotThisModificationVerifier::class.java)
        threads(1)
        invocationsPerIteration(1)
        actorsPerThread(1)
    }

    open class Node(var a: Int) {
        constructor(other: Node, other2: SubNode) : this(2) {
            other.a = 0 // this change should be tracked because no 'leaking this' problem exists here

            val castedOther2 = other2 as Node
            castedOther2.a = 0 // this change should be tracked because no 'leaking this' problem exists here
        }
    }

    class SubNode(a: Int) : Node(a)

    companion object {
        val staticNode = Node(1)
        val staticSubNode = SubNode(2)
    }

    @Operation
    @Suppress("UNUSED_VARIABLE")
    fun modify() {
        val localNode = Node(staticNode, staticSubNode)
    }
}