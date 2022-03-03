/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.test.nvm.durable.buffered

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.DurableRecoverAll
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Sync
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.HBClock
import org.jetbrains.kotlinx.lincheck.execution.ResultWithClock
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.NonVolatileRef
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.test.nvm.AbstractNVMLincheckFailingTest
import org.jetbrains.kotlinx.lincheck.test.nvm.AbstractNVMLincheckTest
import org.jetbrains.kotlinx.lincheck.test.verifier.linearizability.SequentialQueue
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.durable.BufferedDurableLinearizabilityVerifier
import org.junit.Assert
import org.junit.Test

private const val THREADS_NUMBER = 3

internal interface RecoverableQueue<T> {
    fun push(value: T)
    fun pop(): T?
    fun recover()
    fun sync()
}

internal fun result(result: Result, vararg clocks: Int) = ResultWithClock(result, HBClock(clocks))

/**
 * @see  <a href="http://www.cs.technion.ac.il/~erez/Papers/nvm-queue-full.pdf">A Persistent Lock-Free Queue for Non-Volatile Memory</a>
 */
internal class RelaxedQueueTest : AbstractNVMLincheckTest(Recover.BUFFERED_DURABLE, THREADS_NUMBER, SequentialQueue::class) {
    private val q = RelaxedQueue<Int>()

    @Operation
    fun push(value: Int) = q.push(value)

    @Operation
    fun pop() = q.pop()

    @Operation
    @Sync
    fun sync() = q.sync()

    @DurableRecoverAll
    fun recover() = q.recover()

    override fun StressOptions.customize() {
        iterations(80)
    }

    @Test
    fun testVerifier1() {
        val verifier = BufferedDurableLinearizabilityVerifier(SequentialQueue::class.java)
        val scenario = scenario {
            initial { actor(::push, 2); actor(::push, 6) }
            parallel { thread { actor(::pop) } }
            post { actor(::pop) }
        }
        val executionResult = ExecutionResult(
            listOf(VoidResult, VoidResult),
            listOf(
                listOf(result(CrashResult().apply { crashedActors = intArrayOf(0) }, 0))
            ),
            listOf(ValueResult(null))
        )
        Assert.assertTrue(verifier.verifyResults(scenario, executionResult))
    }

    @Test
    fun testVerifier2() {
        val verifier = BufferedDurableLinearizabilityVerifier(SequentialQueue::class.java)
        val scenario = scenario {
            parallel {
                thread { actor(::push, 1); actor(::pop) }
                thread { actor(::push, 4); actor(::pop) }
            }
        }
        val executionResult = ExecutionResult(
            listOf(),
            listOf(
                listOf(result(CrashResult().apply { crashedActors = intArrayOf(0, 2) }, 0, 0), result(ValueResult(null), 1, 2)),
                listOf(result(VoidResult, 0, 0), result(ValueResult(1), 0, 1))
            ),
            listOf()
        )
        Assert.assertTrue(verifier.verifyResults(scenario, executionResult))
    }

    @Test
    fun testVerifier3() {
        val verifier = BufferedDurableLinearizabilityVerifier(SequentialQueue::class.java)
        val scenario = scenario {
            initial { actor(::push, 2); actor(::sync) }
            parallel {
                thread { actor(::pop) }
                thread { actor(::pop) }
            }
            post { actor(::pop) }
        }
        val executionResult = ExecutionResult(
            listOf(VoidResult, VoidResult),
            listOf(
                listOf(result(CrashResult().apply { crashedActors = intArrayOf(0, 1) }, 0, 0)),
                listOf(result(ValueResult(null), 0, 0))
            ),
            listOf(ValueResult(2))
        )
        Assert.assertTrue(verifier.verifyResults(scenario, executionResult))
    }

    @Test
    fun testVerifier4() {
        val verifier = BufferedDurableLinearizabilityVerifier(SequentialQueue::class.java)
        val scenario = scenario {
            initial { actor(::push, 2) }
            parallel {
                thread { actor(::pop) }
                thread { actor(::sync) }
            }
        }
        val executionResult = ExecutionResult(
            listOf(VoidResult),
            listOf(
                listOf(result(ValueResult(null), 0, 1)),
                listOf(result(CrashResult().apply { crashedActors = intArrayOf(-1, 0) }, 0, 0))
            ),
            listOf()
        )
        Assert.assertTrue(verifier.verifyResults(scenario, executionResult))
    }

    @Test
    fun testVerifier5() {
        val verifier = BufferedDurableLinearizabilityVerifier(SequentialQueue::class.java)
        val scenario = scenario {
            initial { actor(::push, -7); actor(::sync); actor(::pop); actor(::push, 3) }
            parallel {
                thread { actor(::pop); }
                thread { actor(::pop); }
                thread { actor(::push, -4); }
            }
            post { actor(::pop) }
        }
        val executionResult = ExecutionResult(
            listOf(VoidResult, VoidResult, ValueResult(-7), VoidResult),
            listOf(
                listOf(result(CrashResult().apply { crashedActors = intArrayOf(0, 1, 0) }, 0, 0, 0)),
                listOf(result(ValueResult(-4), 0, 0, 0)),
                listOf(result(CrashResult().apply { crashedActors = intArrayOf(0, 1, 0) }, 0, 0, 0))
            ),
            listOf(ValueResult(-7))
        )
        Assert.assertTrue(verifier.verifyResults(scenario, executionResult))
    }
}

internal open class QueueNode<T>(val next: NonVolatileRef<QueueNode<T>?> = nonVolatile(null), val v: T? = null)
internal class LatestData<T>(val head: QueueNode<T>, val tail: QueueNode<T>, val version: Int)
internal class Temp<T>(version: Int) : QueueNode<T>() {
    val version = nonVolatile(version)
    val tail = nonVolatile<QueueNode<T>?>(null)
    val head = nonVolatile<QueueNode<T>?>(null)
}

internal open class RelaxedQueue<T> : RecoverableQueue<T> {
    protected val head: NonVolatileRef<QueueNode<T>>
    protected val tail: NonVolatileRef<QueueNode<T>>
    protected val state: NonVolatileRef<LatestData<T>>
    protected val version = nonVolatile(0)

    init {
        val dummy = QueueNode<T>()
        head = nonVolatile(dummy)
        tail = nonVolatile(dummy)
        state = nonVolatile(LatestData(dummy, dummy, -1))
    }

    override fun push(value: T) {
        val newNode = QueueNode(v = value)
        while (true) {
            val last = tail.value
            val nextNode = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                if (nextNode is Temp) {
                    help(nextNode)
                    continue
                }
                tail.compareAndSet(last, nextNode)
            }
        }
    }

    override fun pop(): T? {
        while (true) {
            val first = head.value
            val last = tail.value
            val nextNode = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) return null
                if (nextNode is Temp) {
                    help(nextNode)
                    return null
                }
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue = nextNode.v!!
                if (head.compareAndSet(first, nextNode)) {
                    return currentValue
                }
            }
        }
    }

    override fun sync() {
        val potential = getSnapshot()
        flushQueue(potential)
        while (true) {
            val currentState = state.value
            if (currentState.version > potential.version) {
                state.flush() // !!
                break
            }
            if (state.compareAndSet(currentState, potential)) {
                state.flush()
                break
            }
        }
    }

    protected fun flushQueue(potential: LatestData<T>) {
        var currentNode = potential.head
        while (true) {
            if (currentNode === potential.tail) break
            val nextNode = currentNode.next.value ?: error("Tail not found")
            currentNode.next.flush()
            currentNode = nextNode
        }
    }

    protected open fun getSnapshot(): LatestData<T> {
        var temp = Temp<T>(0)
        while (true) {
            val currentVersion = version.getAndIncrement()
            version.flush()
            temp.version.value = currentVersion
            val last = tail.value
            val nextNode = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                temp.tail.value = last
                if (last.next.compareAndSet(null, temp)) {
                    help(temp)
                    break
                }
            } else {
                if (nextNode is Temp) {
                    if (nextNode.version.value >= currentVersion) {
                        help(nextNode)
                        temp = nextNode
                        break
                    }
                    if (nextNode.head.compareAndSet(null, head.value)) {
                        // performed CAS myself => head is fresh

                        help(nextNode)
                        // do not decrease version
                        temp.head.value = nextNode.head.value
                        temp.tail.value = nextNode.tail.value
                        break
                    }
                    help(nextNode)
                    continue
                }
                tail.compareAndSet(last, nextNode)
            }
        }
        checkNotNull(temp.head.value)
        return LatestData(temp.head.value!!, temp.tail.value!!, temp.version.value)
    }

    protected fun help(nextNode: Temp<T>) {
        nextNode.head.compareAndSet(null, head.value)
        nextNode.tail.value!!.next.compareAndSet(nextNode, null)
    }

    override fun recover() {
        head.value = state.value.head
        tail.value = state.value.tail
        tail.value.next.value = null
        check(reachableFrom(head.value, tail.value))
    }

    protected fun reachableFrom(start: QueueNode<*>, node: QueueNode<*>): Boolean {
        var c = start
        while (true) {
            if (c === node) return true
            c = c.next.value ?: return false
        }
    }
}


internal abstract class RelaxedQueueFailingTest : AbstractNVMLincheckFailingTest(Recover.BUFFERED_DURABLE, THREADS_NUMBER, SequentialQueue::class) {
    internal abstract val q: RecoverableQueue<Int>

    @Operation
    fun push(value: Int) = q.push(value)

    @Operation
    fun pop() = q.pop()

    @Operation
    @Sync
    fun sync() = q.sync()

    @DurableRecoverAll
    fun recover() = q.recover()
}

internal class RelaxedQueueFailingTest1 : RelaxedQueueFailingTest() {
    override val q = RelaxedFailingQueue1<Int>()
    override fun StressOptions.customize() {
        invocationsPerIteration(1e6.toInt())
        threads(2)
    }

    override fun ModelCheckingOptions.customize() {
        invocationsPerIteration(1e6.toInt())
        iterations(0)
        addCustomScenario {
            iterations(0)
            initial { actor(::push, 1) }
            parallel {
                thread { actor(::push, 2); actor(::sync) }
                thread { actor(::pop); actor(::sync); actor(::pop) }
            }
            post { actor(::pop) }
        }
    }
}

internal class RelaxedQueueFailingTest2 : RelaxedQueueFailingTest() {
    override val q = RelaxedFailingQueue2<Int>()
    override fun StressOptions.customize() {
        invocationsPerIteration(1e6.toInt())
        threads(2)
    }

    override fun ModelCheckingOptions.customize() {
        invocationsPerIteration(1e6.toInt())
        iterations(0)
        addCustomScenario {
            iterations(0)
            initial { actor(::push, 1) }
            parallel {
                thread { actor(::push, 2); actor(::sync) }
                thread { actor(::pop); actor(::sync) }
            }
            post { actor(::pop) }
        }
    }
}

internal class RelaxedFailingQueue1<T> : RelaxedQueue<T>() {
    override fun getSnapshot(): LatestData<T> {
        var temp = Temp<T>(0)
        while (true) {
            val currentVersion = version.getAndIncrement()
            version.flush()
            temp.version.value = currentVersion
            val last = tail.value
            val nextNode = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                temp.tail.value = last
                if (last.next.compareAndSet(null, temp)) {
                    help(temp)
                    break
                }
            } else {
                if (nextNode is Temp) {
                    if (nextNode.version.value > currentVersion || nextNode.head.value == null) {
                        help(nextNode)
                        // version is decreased here, head is not fresh
                        temp = nextNode
                        break
                    }
                    help(nextNode)
                    continue
                }
                tail.compareAndSet(last, nextNode)
            }
        }
        checkNotNull(temp.head.value)
        return LatestData(temp.head.value!!, temp.tail.value!!, temp.version.value)
    }
}

internal class RelaxedFailingQueue2<T> : RelaxedQueue<T>() {
    override fun sync() {
        val potential = getSnapshot()
        check(reachableFrom(potential.head, potential.tail))
        flushQueue(potential)
        while (true) {
            val currentState = state.value
            if (currentState.version > potential.version) {
                // here should be state.flush()
                break
            }
            if (state.compareAndSet(currentState, potential)) {
                state.flush()
                break
            }
        }
    }
}
