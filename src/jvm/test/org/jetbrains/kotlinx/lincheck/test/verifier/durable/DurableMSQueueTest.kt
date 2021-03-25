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
package org.jetbrains.kotlinx.lincheck.test.verifier.durable

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.DurableRecoverPerThread
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.HBClock
import org.jetbrains.kotlinx.lincheck.execution.ResultWithClock
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.NonVolatileRef
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.test.verifier.linearizability.SequentialQueue
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier
import org.junit.Assert
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import java.lang.reflect.Method

private const val THREADS_NUMBER = 2

internal interface RecoverableQueue<T> {
    fun push(value: T)
    fun pop(p: Int): T?
    fun recover()
}

/**
 * @see  <a href="http://www.cs.technion.ac.il/~erez/Papers/nvm-queue-full.pdf">A Persistent Lock-Free Queue for Non-Volatile Memory</a>
 */
@StressCTest(
    sequentialSpecification = SequentialQueue::class,
    threads = THREADS_NUMBER,
    recover = Recover.DURABLE,
    minimizeFailedScenario = false
)
class DurableMSQueueTest {
    private val q = DurableMSQueue<Int>()

    @Operation
    fun push(value: Int) = q.push(value)

    @Operation
    fun pop(@Param(gen = ThreadIdGen::class) threadId: Int) = q.pop(threadId)

    @DurableRecoverPerThread
    fun recover() = q.recover()

    @Test
    fun test() = LinChecker.check(this::class.java)
}

private const val DEFAULT_DELETER = -1

internal class QueueNode<T>(val next: NonVolatileRef<QueueNode<T>?> = nonVolatile(null), val v: T) {
    val deleter = nonVolatile(DEFAULT_DELETER)
}

internal class DurableMSQueue<T> : RecoverableQueue<T> {
    private val head: NonVolatileRef<QueueNode<T?>>
    private val tail: NonVolatileRef<QueueNode<T?>>

    init {
        val dummy = QueueNode<T?>(v = null)
        head = nonVolatile(dummy)
        tail = nonVolatile(dummy)
    }

    override fun push(value: T) {
        val newNode = QueueNode<T?>(v = value)
        while (true) {
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }

    override fun pop(p: Int): T? {
        while (true) {
            val first: QueueNode<T?> = head.value
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue: T = nextNode.v!!
                if (nextNode.deleter.compareAndSet(DEFAULT_DELETER, p)) {
                    nextNode.deleter.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        nextNode.deleter.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }

    override fun recover() {
        while (true) {
            val h = head.value
            val next = h.next.value ?: break
            if (next.deleter.value == DEFAULT_DELETER) break
            head.compareAndSet(h, next)
        }
        while (true) {
            val t = tail.value
            val next = t.next.value ?: break
            t.next.flush()
            tail.compareAndSet(t, next)
        }
        check(reachableFrom(head.value, tail.value))
    }

    private fun reachableFrom(start: QueueNode<T?>, node: QueueNode<T?>): Boolean {
        var c = start
        while (true) {
            if (c === node) return true
            c = c.next.value ?: return false
        }
    }
}

private val PUSH = DurableMSQueueTest::class.java.getMethod("push", Int::class.javaPrimitiveType)
private val POP = DurableMSQueueTest::class.java.getMethod("pop", Int::class.javaPrimitiveType)
private fun actor(method: Method, vararg a: Any?) = Actor(method, a.toMutableList())

class ManualDurableMSQueueTest {
    @Test
    fun test() {
        val verifier = LinearizabilityVerifier(SequentialQueue::class.java)
        val scenario = ExecutionScenario(
            listOf(actor(PUSH, 2), actor(PUSH, 6), actor(POP, 0), actor(PUSH, -6), actor(PUSH, -8)),
            listOf(
                listOf(actor(POP, 1), actor(POP, 1), actor(PUSH, -8), actor(POP, 1), actor(PUSH, 5)),
                listOf(actor(PUSH, 1), actor(PUSH, 4), actor(POP, 2), actor(POP, 2), actor(PUSH, -4))
            ),
            listOf(actor(PUSH, -8), actor(PUSH, -2), actor(POP, 3), actor(PUSH, -8), actor(POP, 3))
        )
        val clocks = listOf(
            listOf(0 to 2, 1 to 5, 2 to 5, 3 to 5, 4 to 5),
            listOf(0 to 0, 0 to 1, 0 to 2, 1 to 3, 1 to 4)
        ).map { threadClocks -> threadClocks.map { HBClock(it.toList().toIntArray()) } }

        val results = ExecutionResult(
            listOf(VoidResult, VoidResult, ValueResult(2), VoidResult, VoidResult),
            listOf(
                listOf(CrashResult, ValueResult(-8), VoidResult, ValueResult(1), VoidResult),
                listOf(VoidResult, VoidResult, CrashResult, ValueResult(-6), VoidResult)
            ).zip(clocks).map { (res, clock) -> res.zip(clock).map { (r, c) -> ResultWithClock(r, c) } },
            listOf(VoidResult, VoidResult, ValueResult(4), VoidResult, ValueResult(-4))
        )
        Assert.assertTrue(verifier.verifyResults(scenario, results))
    }
}


@StressCTest(
    sequentialSpecification = SequentialQueue::class,
    threads = THREADS_NUMBER,
    recover = Recover.DURABLE,
    minimizeFailedScenario = false
)
class DurableMSQueueNoRecoveryFailingTest {
    private val q = DurableMSQueue<Int>()

    @Operation
    fun push(value: Int) = q.push(value)

    @Operation
    fun pop(@Param(gen = ThreadIdGen::class) threadId: Int) = q.pop(threadId)

    /** This test fails as no recovery is provided. */
    @Test(expected = Throwable::class)
    fun testFails() = LinChecker.check(this::class.java)
}

@StressCTest(
    sequentialSpecification = SequentialQueue::class,
    threads = THREADS_NUMBER,
    recover = Recover.DURABLE,
    minimizeFailedScenario = false
)
internal abstract class DurableMSQueueFailingTest {
    internal abstract val q: RecoverableQueue<Int>

    @Operation
    fun push(value: Int) = q.push(value)

    @Operation
    fun pop(@Param(gen = ThreadIdGen::class) threadId: Int) = q.pop(threadId)

    @DurableRecoverPerThread
    fun recover() = q.recover()

    @Test
    fun test() {
        try {
            LinChecker.check(this::class.java)
            fail("Expected exception has not occurred.")
        } catch (e: LincheckAssertionError) {
        } catch (e: IllegalStateException) {
        }
    }
}

internal class DurableMSQueueFailingTest1 : DurableMSQueueFailingTest() {
    override val q = DurableMSFailingQueue1<Int>()
}

internal class DurableMSQueueFailingTest2 : DurableMSQueueFailingTest() {
    override val q = DurableMSFailingQueue2<Int>()
}

@Ignore("Can't find an error. To be fixed")
internal class DurableMSQueueFailingTest3 : DurableMSQueueFailingTest() {
    override val q = DurableMSFailingQueue3<Int>()
}

internal class DurableMSQueueFailingTest4 : DurableMSQueueFailingTest() {
    override val q = DurableMSFailingQueue4<Int>()
}

internal class DurableMSQueueFailingTest5 : DurableMSQueueFailingTest() {
    override val q = DurableMSFailingQueue5<Int>()
}

internal class DurableMSQueueFailingTest6 : DurableMSQueueFailingTest() {
    override val q = DurableMSFailingQueue6<Int>()
}

internal class DurableMSFailingQueue1<T> : RecoverableQueue<T> {
    private val head: NonVolatileRef<QueueNode<T?>>
    private val tail: NonVolatileRef<QueueNode<T?>>

    init {
        val dummy = QueueNode<T?>(v = null)
        head = nonVolatile(dummy)
        tail = nonVolatile(dummy)
    }

    override fun push(value: T) {
        val newNode = QueueNode<T?>(v = value)
        while (true) {
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    // here should be last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }

    override fun pop(p: Int): T? {
        while (true) {
            val first: QueueNode<T?> = head.value
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue: T = nextNode.v!!
                if (nextNode.deleter.compareAndSet(DEFAULT_DELETER, p)) {
                    nextNode.deleter.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        nextNode.deleter.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }

    override fun recover() {
        while (true) {
            val h = head.value
            val next = h.next.value ?: break
            if (next.deleter.value == DEFAULT_DELETER) break
            next.deleter.flush()
            head.compareAndSet(h, next)
        }
        while (true) {
            val t = tail.value
            val next = t.next.value ?: break
            t.next.flush()
            tail.compareAndSet(t, next)
        }
    }
}


internal class DurableMSFailingQueue2<T> : RecoverableQueue<T> {
    private val head: NonVolatileRef<QueueNode<T?>>
    private val tail: NonVolatileRef<QueueNode<T?>>

    init {
        val dummy = QueueNode<T?>(v = null)
        head = nonVolatile(dummy)
        tail = nonVolatile(dummy)
    }

    override fun push(value: T) {
        val newNode = QueueNode<T?>(v = value)
        while (true) {
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                // here should be last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }

    override fun pop(p: Int): T? {
        while (true) {
            val first: QueueNode<T?> = head.value
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue: T = nextNode.v!!
                if (nextNode.deleter.compareAndSet(DEFAULT_DELETER, p)) {
                    nextNode.deleter.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        nextNode.deleter.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }

    override fun recover() {
        while (true) {
            val h = head.value
            val next = h.next.value ?: break
            if (next.deleter.value == DEFAULT_DELETER) break
            next.deleter.flush()
            head.compareAndSet(h, next)
        }
        while (true) {
            val t = tail.value
            val next = t.next.value ?: break
            t.next.flush()
            tail.compareAndSet(t, next)
        }
    }
}


internal class DurableMSFailingQueue3<T> : RecoverableQueue<T> {
    private val head: NonVolatileRef<QueueNode<T?>>
    private val tail: NonVolatileRef<QueueNode<T?>>

    init {
        val dummy = QueueNode<T?>(v = null)
        head = nonVolatile(dummy)
        tail = nonVolatile(dummy)
    }

    override fun push(value: T) {
        val newNode = QueueNode<T?>(v = value)
        while (true) {
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }

    override fun pop(p: Int): T? {
        while (true) {
            val first: QueueNode<T?> = head.value
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    return null
                }
                // here should be last.next.flush()
                // could be reproduced if push sleeps after successful CAS before flush
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue: T = nextNode.v!!
                if (nextNode.deleter.compareAndSet(DEFAULT_DELETER, p)) {
                    nextNode.deleter.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        nextNode.deleter.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }

    override fun recover() {
        while (true) {
            val h = head.value
            val next = h.next.value ?: break
            if (next.deleter.value == DEFAULT_DELETER) break
            next.deleter.flush()
            head.compareAndSet(h, next)
        }
        while (true) {
            val t = tail.value
            val next = t.next.value ?: break
            t.next.flush()
            tail.compareAndSet(t, next)
        }
    }
}


internal class DurableMSFailingQueue4<T> : RecoverableQueue<T> {
    private val head: NonVolatileRef<QueueNode<T?>>
    private val tail: NonVolatileRef<QueueNode<T?>>

    init {
        val dummy = QueueNode<T?>(v = null)
        head = nonVolatile(dummy)
        tail = nonVolatile(dummy)
    }

    override fun push(value: T) {
        val newNode = QueueNode<T?>(v = value)
        while (true) {
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }

    override fun pop(p: Int): T? {
        while (true) {
            val first: QueueNode<T?> = head.value
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue: T = nextNode.v!!
                if (nextNode.deleter.compareAndSet(DEFAULT_DELETER, p)) {
                    // here should be nextNode.deleter.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        nextNode.deleter.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }

    override fun recover() {
        while (true) {
            val h = head.value
            val next = h.next.value ?: break
            if (next.deleter.value == DEFAULT_DELETER) break
            next.deleter.flush()
            head.compareAndSet(h, next)
        }
        while (true) {
            val t = tail.value
            val next = t.next.value ?: break
            t.next.flush()
            tail.compareAndSet(t, next)
        }
    }
}


internal class DurableMSFailingQueue5<T> : RecoverableQueue<T> {
    private val head: NonVolatileRef<QueueNode<T?>>
    private val tail: NonVolatileRef<QueueNode<T?>>

    init {
        val dummy = QueueNode<T?>(v = null)
        head = nonVolatile(dummy)
        tail = nonVolatile(dummy)
    }

    override fun push(value: T) {
        val newNode = QueueNode<T?>(v = value)
        while (true) {
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }

    override fun pop(p: Int): T? {
        while (true) {
            val first: QueueNode<T?> = head.value
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue: T = nextNode.v!!
                if (nextNode.deleter.compareAndSet(DEFAULT_DELETER, p)) {
                    nextNode.deleter.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        // here should be nextNode.deleter.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }

    override fun recover() {
        while (true) {
            val h = head.value
            val next = h.next.value ?: break
            if (next.deleter.value == DEFAULT_DELETER) break
            next.deleter.flush()
            head.compareAndSet(h, next)
        }
        while (true) {
            val t = tail.value
            val next = t.next.value ?: break
            t.next.flush()
            tail.compareAndSet(t, next)
        }
    }
}

internal class DurableMSFailingQueue6<T> : RecoverableQueue<T> {
    private val head: NonVolatileRef<QueueNode<T?>>
    private val tail: NonVolatileRef<QueueNode<T?>>

    init {
        val dummy = QueueNode<T?>(v = null)
        head = nonVolatile(dummy)
        tail = nonVolatile(dummy)
    }

    override fun push(value: T) {
        val newNode = QueueNode<T?>(v = value)
        while (true) {
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }

    override fun pop(p: Int): T? {
        while (true) {
            val first: QueueNode<T?> = head.value
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue: T = nextNode.v!!
                if (nextNode.deleter.compareAndSet(DEFAULT_DELETER, p)) {
                    nextNode.deleter.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        nextNode.deleter.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }

    override fun recover() {
        while (true) {
            val h = head.value
            val next = h.next.value ?: break
            if (next.deleter.value == DEFAULT_DELETER) break
            next.deleter.flush()
            head.compareAndSet(h, next)
        }
//        here should be
//        while (true) {
//            val t = tail.value
//            val next = t.next.value ?: break
//            t.next.flush()
//            tail.compareAndSet(t, next)
//        }
    }
}
