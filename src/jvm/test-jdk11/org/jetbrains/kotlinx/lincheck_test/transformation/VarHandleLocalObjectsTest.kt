/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.junit.Test
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

/**
 * This test checks that despite some object isn't explicitly assigned to some shared value,
 * is won't be treated like a local object if we wrote it into some shared object using [VarHandle].
 *
 * If we hadn't such check, this test would hang due to infinite spin-loop on a local object operations with
 * no chances to detect a cycle and switch.
 */
@Suppress("Since15")
class VarHandleLocalObjectsTest {

    companion object {
        val nodeHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleLocalObjectsTest::class.java)
            .findVarHandle(VarHandleLocalObjectsTest::class.java, "node", Node::class.java)
    }

    @Volatile
    var node: Node = Node(0)

    @Volatile
    var flag = false

    @Operation
    fun checkWithSpinCycle() {
        val curr = node
        while (curr.value == 1) {
            // spin-wait
        }
    }

    @Operation
    fun setWithVarHandle()  = updateNode { nextNode ->
        nodeHandle.set(this, nextNode)
    }

    @Operation
    fun setVolatileWithVarHandle() = updateNode { nextNode ->
        nodeHandle.setVolatile(this, nextNode)
    }

    @Operation
    fun setReleaseWithVarHandle() = updateNode { nextNode ->
        nodeHandle.setRelease(this, nextNode)
    }

    @Operation
    fun setOpaqueWithVarHandle() = updateNode { nextNode ->
        nodeHandle.setOpaque(this, nextNode)
    }

    @Operation
    fun getAndSetWithVarHandle()= updateNode { nextNode ->
        nodeHandle.getAndSet(this, nextNode)
    }

    @Operation
    fun compareAndSetWithVarHandle() = updateNode { nextNode ->
        nodeHandle.compareAndSet(this, node, nextNode)
    }

    @Operation
    fun compareAndExchangeAcquireWithVarHandle() = updateNode { nextNode ->
        nodeHandle.compareAndExchangeAcquire(this, node, nextNode)
    }

    @Operation
    fun compareAndExchangeReleaseWithVarHandle() = updateNode { nextNode ->
        nodeHandle.compareAndExchangeRelease(this, node, nextNode)
    }

    @Operation
    fun compareAndExchangeWithVarHandle() = updateNode { nextNode ->
        nodeHandle.compareAndExchange(this, node, nextNode)
    }

    @Operation
    fun weakCompareAndSetWithVarHandle() = updateNode { nextNode ->
        nodeHandle.weakCompareAndSet(this, node, nextNode)
    }

    @Operation
    fun weakCompareAndSetAcquireWithVarHandle() = updateNode { nextNode ->
        nodeHandle.weakCompareAndSetAcquire(this, node, nextNode)
    }

    @Operation
    fun weakCompareAndSetPlainWithVarHandle() = updateNode { nextNode ->
        nodeHandle.weakCompareAndSetPlain(this, node, nextNode)
    }

    @Operation
    fun weakCompareAndSetReleaseWithVarHandle() = updateNode { nextNode ->
        nodeHandle.weakCompareAndSetRelease(this, node, nextNode)
    }

    @Test
    fun testSetWithVarHandle() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::setWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    @Test
    fun testSetVolatileWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::setVolatileWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    @Test
    fun testSetReleaseWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::setReleaseWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    @Test
    fun testSetOpaqueWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::setOpaqueWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    @Test
    fun testGetAndSetWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::getAndSetWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)


    @Test
    fun testCompareAndSetWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::compareAndSetWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    @Test
    fun testCompareAndExchangeWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::compareAndExchangeWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    @Test
    fun testWeakCompareAndSetWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::weakCompareAndSetWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)


    @Test
    fun testWeakCompareAndSetAcquireWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::weakCompareAndSetAcquireWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    @Test
    fun testWeakCompareAndSetReleaseWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::weakCompareAndSetReleaseWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    @Test
    fun testWeakCompareAndSetPlainWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::weakCompareAndSetPlainWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    @Test
    fun testCompareAndExchangeAcquireWithVarHandleWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::compareAndExchangeAcquireWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    @Test
    fun testCompareAndExchangeReleaseWithVarHandleWithVarHandleWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::compareAndExchangeReleaseWithVarHandle) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    private inline fun updateNode(updateAction: (Node) -> Unit) {
        val nextNode = Node(1)
        nextNode.value = 1
        updateAction(nextNode)
        // If we have a bug and treat nextNode as a local object, we have to insert here some switch points
        // to catch a moment when `node.value` is just updated to 1
        flag = true

        nextNode.value = 0
    }
}