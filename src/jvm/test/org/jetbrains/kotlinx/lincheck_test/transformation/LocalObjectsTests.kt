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

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import sun.misc.Unsafe
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.concurrent.Volatile
import kotlin.reflect.KFunction

/**
 * This test checks that managed strategies do not try to switch
 * thread context at reads and writes of local objects.
 * In case a strategy does not have this optimization,
 * this test fails by timeout since the number of
 * invocations is set to [Int.MAX_VALUE].
 */
@ModelCheckingCTest(
    actorsBefore = 0,
    actorsAfter = 0,
    actorsPerThread = 50,
    invocationsPerIteration = Int.MAX_VALUE,
    iterations = 50
)
class LocalObjectEliminationTest : VerifierState() {
    @Operation
    fun operation(): Int {
        val a = A(0, this, IntArray(2))
        a.any = a
        repeat(20) {
            a.value = it
        }
        a.array[1] = 54
        val b = A(a.value, a.any, a.array)
        b.value = 65
        repeat(20) {
            b.array[0] = it
        }
        a.any = b
        return (a.any as A).array.sum()
    }

    @Test(timeout = 100_000)
    fun test() {
        LinChecker.check(this::class.java)
    }

    override fun extractState(): Any = 0 // constant state

    private data class A(var value: Int, var any: Any, val array: IntArray)
}


/**
 * This test checks that despite some object isn't explicitly assigned to some shared value,
 * is won't be treated like a local object if we wrote it into some shared object using [AtomicReferenceFieldUpdater].
 *
 * If we hadn't such check, this test would hang due to infinite spin-loop on a local object operations with
 * no chances to detect a cycle and switch.
 */
class AtomicUpdaterLocalObjectsTest {

    companion object {
        val nodeUpdater: AtomicReferenceFieldUpdater<AtomicUpdaterLocalObjectsTest, Node> = AtomicReferenceFieldUpdater.newUpdater(
            AtomicUpdaterLocalObjectsTest::class.java,
            Node::class.java,
            "node"
        )
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
    fun setWithAtomicUpdater() = updateNode { nextNode ->
        nodeUpdater.set(this, nextNode)
    }

    @Operation
    fun lazySetWithAtomicUpdater()= updateNode { nextNode ->
        nodeUpdater.lazySet(this, nextNode)
    }

    @Operation
    fun getAndSetWithAtomicUpdater() = updateNode { nextNode ->
        nodeUpdater.getAndSet(this, nextNode)
    }

    @Operation
    fun compareAndSetWithAtomicUpdater() = updateNode { nextNode ->
        nodeUpdater.compareAndSet(this, node, nextNode)
    }

    @Operation
    fun weakCompareAndSetWithAtomicUpdater() = updateNode { nextNode ->
        nodeUpdater.weakCompareAndSet(this, node, nextNode)
    }

    @Test
    fun testAtomicUpdaterSet() = testWithUpdate(::setWithAtomicUpdater)
    @Test
    fun testAtomicUpdaterLazySet() = testWithUpdate(::lazySetWithAtomicUpdater)
    @Test
    fun testAtomicUpdaterGetAndSet() = testWithUpdate(::getAndSetWithAtomicUpdater)
    @Test
    fun testAtomicUpdaterWeakCompareAndSet() = testWithUpdate(::weakCompareAndSetWithAtomicUpdater)
    @Test
    fun testAtomicUpdaterCompareAndSet() = testWithUpdate(::compareAndSetWithAtomicUpdater)

    private fun testWithUpdate(operation: KFunction<*>) = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(operation) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    private inline fun updateNode(updateAction: (Node) -> Unit) {
        val nextNode = Node(1)
        nextNode.value = 1
        updateAction(nextNode)
        // If we have a bug and treat nextNode as a local object, we have to insert here some switch points
        // to catch a moment when `node.value` is updated to 1
        flag = true

        nextNode.value = 0
    }


}

/**
 * This test checks that despite some object isn't explicitly assigned to some shared value,
 * is won't be treated like a local object if we wrote it into some shared object using [Unsafe].
 *
 * If we hadn't such check, this test would hang due to infinite spin-loop on a local object operations with
 * no chances to detect a cycle and switch.
 */
class UnsafeLocalObjectsTest {

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

    companion object {
        val unsafe: Unsafe = try {
            val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            unsafeField.get(null) as Unsafe
        } catch (ex: Exception) {
            throw RuntimeException(ex)
        }
        val offset = unsafe.objectFieldOffset(UnsafeLocalObjectsTest::class.java.getDeclaredField("node"))
    }


    @Operation
    fun compareAndSetWithUnsafe() = updateNode { nextNode ->
        unsafe.compareAndSwapObject(this, offset, node, nextNode)
    }

    @Operation
    fun getAndSetWithUnsafe()  = updateNode { nextNode ->
        unsafe.getAndSetObject(this, offset, nextNode)
    }

    @Test
    fun testCompareAndSetWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::compareAndSetWithUnsafe) }
                thread { actor(::checkWithSpinCycle) }
            }
        }
        .check(this::class)

    @Test
    fun testGetAndSetWithUnsafe() = ModelCheckingOptions()
        .iterations(0) // we will run only custom scenarios
        .addCustomScenario {
            parallel {
                thread { actor(::getAndSetWithUnsafe) }
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

/**
 * This test checks that despite some object isn't explicitly assigned to some shared value,
 * is won't be treated like a local object if we wrote it into some shared object using [VarHandle].
 *
 * If we hadn't such check, this test would hang due to infinite spin-loop on a local object operations with
 * no chances to detect a cycle and switch.
 */
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



data class Node(
    @Volatile
    var value: Int
)
