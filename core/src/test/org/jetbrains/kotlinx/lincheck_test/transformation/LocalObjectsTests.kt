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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.util.isInTraceDebuggerMode
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import sun.misc.Unsafe
import java.util.concurrent.atomic.*
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

class LocalObjectEliminationTest {
    @Before
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

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
        // check that closure object and captured `x: IntRef` object
        // are correctly classified as local objects;
        // note that these classes itself are not instrumented,
        // but the creation of their instances still should be tracked
        var x = 0
        val closure = {
            a.value += 1
            x += 1
        }
        repeat(20) {
            closure()
        }
        return (a.any as A).array.sum()
    }

    @Test(timeout = 100_000)
    fun test() {
        @Suppress("DEPRECATION")
        LinChecker.check(this::class.java)
    }

    private data class A(var value: Int, var any: Any, val array: IntArray)
}

class LocalObjectEscapeConstructorTest {

    private class A(var b: B)

    private class B(var i: Int)

    private class LocalEscapedException : Exception()

    @Volatile
    private var a: A? = null

    @Operation
    fun escapeLocal() {
        val localB = B(0)
        val localA = A(localB)
        /* if we uncomment this line, then there is an explicit write
         * of `localB` into `localA` field that the model checker will detect;
         * however, the model checker still should detect object escaping even without explicit write,
         * because `localB` is assigned to `localA` field in the constructor
         */
        // localA.b = localB
        a = localA
        /* the model checker should not treat `localB` as a local object
         * and thus should insert switch points before accesses to this object's fields,
         * because the `localB` object escapes its thread as it is referenced by the escaped object `localA`
         */
        if (localB.i != 0) throw LocalEscapedException()
    }

    @Operation
    fun changeState() {
        val b = a?.b ?: return
        b.i = 1
    }

    @Test
    fun test() {
        val failure = ModelCheckingOptions()
            .iterations(0) // we will run only custom scenarios
            .addCustomScenario {
                parallel {
                    thread { actor(::escapeLocal) }
                    thread { actor(::changeState) }
                }
            }
            .checkImpl(this::class.java)
        // the test should fail
        check(failure is IncorrectResultsFailure)
        // `escapeLocal` function should throw `LocalEscapedException`
        val result = failure.results.parallelResults[0][0]
        check(result is ExceptionResult)
        check(result.throwable is LocalEscapedException)
    }

}

/**
 * This test checks that despite some object isn't explicitly assigned to some shared value,
 * is won't be treated like a local object if we wrote it into some shared object using [AtomicReference].
 *
 * If the object is incorrectly classified as a local object,
 * this test would hang due to infinite spin-loop on a local object operation with
 * no chances to detect a cycle and switch.
 */
class AtomicReferenceLocalObjectsTest {

    val atomicReference: AtomicReference<Node> = AtomicReference(Node(0))

    val node: Node get() = atomicReference.get()

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
    fun setWithAtomicReference() = updateNode { nextNode ->
        atomicReference.set(nextNode)
    }

    @Operation
    fun lazySetWithAtomicReference()= updateNode { nextNode ->
        atomicReference.lazySet(nextNode)
    }

    @Operation
    fun getAndSetWithAtomicReference() = updateNode { nextNode ->
        atomicReference.getAndSet(nextNode)
    }

    @Operation
    fun compareAndSetWithAtomicReference() = updateNode { nextNode ->
        atomicReference.compareAndSet(node, nextNode)
    }

    @Operation
    fun weakCompareAndSetWithAtomicReference() = updateNode { nextNode ->
        @Suppress("DEPRECATION")
        atomicReference.weakCompareAndSet(node, nextNode)
    }

    @Test
    fun testAtomicReferenceSet() = testWithUpdate(::setWithAtomicReference)

    @Test
    fun testAtomicReferenceLazySet() = testWithUpdate(::lazySetWithAtomicReference)

    @Test
    fun testAtomicReferenceGetAndSet() = testWithUpdate(::getAndSetWithAtomicReference)

    @Test
    fun testAtomicAtomicReferenceCompareAndSet() = testWithUpdate(::compareAndSetWithAtomicReference)

    @Test
    fun testAtomicReferenceWeakCompareAndSet() = testWithUpdate(::weakCompareAndSetWithAtomicReference)

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
 * is won't be treated like a local object if we wrote it into some shared object using [AtomicReferenceArray].
 *
 * If the object is incorrectly classified as a local object,
 * this test would hang due to infinite spin-loop on a local object operation with
 * no chances to detect a cycle and switch.
 */
class AtomicArrayLocalObjectsTest {

    val atomicArray: AtomicReferenceArray<Node> = AtomicReferenceArray(arrayOf(Node(0)))

    val node: Node get() = atomicArray[0]

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
    fun setWithAtomicArray() = updateNode { nextNode ->
        atomicArray.set(0, nextNode)
    }

    @Operation
    fun lazySetWithAtomicArray()= updateNode { nextNode ->
        atomicArray.lazySet(0, nextNode)
    }

    @Operation
    fun getAndSetWithAtomicArray() = updateNode { nextNode ->
        atomicArray.getAndSet(0, nextNode)
    }

    @Operation
    fun compareAndSetWithAtomicArray() = updateNode { nextNode ->
        atomicArray.compareAndSet(0, node, nextNode)
    }

    @Operation
    fun weakCompareAndSetWithAtomicArray() = updateNode { nextNode ->
        @Suppress("DEPRECATION")
        atomicArray.weakCompareAndSet(0, node, nextNode)
    }

    @Test
    fun testAtomicArraySet() = testWithUpdate(::setWithAtomicArray)

    @Test
    fun testAtomicArrayLazySet() = testWithUpdate(::lazySetWithAtomicArray)

    @Test
    fun testAtomicArrayGetAndSet() = testWithUpdate(::getAndSetWithAtomicArray)

    @Test
    fun testAtomicAtomicArrayCompareAndSet() = testWithUpdate(::compareAndSetWithAtomicArray)

    @Test
    fun testAtomicArrayWeakCompareAndSet() = testWithUpdate(::weakCompareAndSetWithAtomicArray)

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
 * is won't be treated like a local object if we wrote it into some shared object using [AtomicReferenceFieldUpdater].
 *
 * If the object is incorrectly classified as a local object,
 * this test would hang due to infinite spin-loop on a local object operation with
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
 * If the object is incorrectly classified as a local object,
 * this test would hang due to infinite spin-loop on a local object operation with
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
        @Suppress("DEPRECATION")
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


data class Node(
    @Volatile
    var value: Int
)
