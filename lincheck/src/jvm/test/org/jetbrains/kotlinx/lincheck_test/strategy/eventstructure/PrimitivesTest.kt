/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.LockSupport.*
import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.util.CancelledResult
import org.jetbrains.kotlinx.lincheck.util.SuspendedResult
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.scenario
import org.junit.Ignore
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestName
import kotlin.reflect.jvm.javaMethod
import org.jetbrains.lincheck.util.UnsafeHolder

class PrimitivesTest {

    @get:Rule
    val testName = TestName()

    class PlainPrimitiveVariable {
        private var variable: Int = 0

        fun write(value: Int) {
            variable = value
        }

        fun read(): Int {
            return variable
        }
    }

    @Test
    fun testPlainPrimitiveAccesses() {

        val write = PlainPrimitiveVariable::write
        val read = PlainPrimitiveVariable::read
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, 1)
                }
                thread {
                    actor(read)
                }
                thread {
                    actor(write, 2)
                }
            }
        }
        // TODO: when we will implement various access modes,
        //   we should probably report races on plain variables as errors (or warnings at least)
        val outcomes: Set<Int> = setOf(0, 1, 2)
        litmusTest(PlainPrimitiveVariable::class.java, testScenario, outcomes) { results ->
            getValue<Int>(results.parallelResults[1][0]!!)
        }
    }

    class PlainReferenceVariable {
        private var variable: String = ""

        fun write(value: String) {
            variable = value
        }

        fun read(): String {
            return variable
        }
    }

    @Test
    fun testPlainReferenceAccesses() {

        val write = PlainReferenceVariable::write
        val read = PlainReferenceVariable::read
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, "a")
                }
                thread {
                    actor(read)
                }
                thread {
                    actor(write, "b")
                }
            }
        }
        val outcomes: Set<String> = setOf("", "a", "b")
        litmusTest(PlainReferenceVariable::class.java, testScenario, outcomes) { results ->
            getValue<String>(results.parallelResults[1][0]!!)
        }
    }

    class PrimitiveArray {
        private val array = IntArray(8)

        fun write(index: Int, value: Int) {
            array[index] = value
        }

        fun read(index: Int): Int {
            return array[index]
        }
    }

    @Test
    fun testPrimitiveArrayAccesses() {

        val write = PrimitiveArray::write
        val read = PrimitiveArray::read
        val index = 2
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, index, 1)
                }
                thread {
                    actor(read, index)
                }
                thread {
                    actor(write, index, 2)
                }
            }
        }
        val outcomes: Set<Int> = setOf(0, 1, 2)
        litmusTest(PrimitiveArray::class.java, testScenario, outcomes) { results ->
            getValue<Int>(results.parallelResults[1][0]!!)
        }
    }

    class ReferenceArray {
        private val array = Array<String>(8) { "" }

        fun write(index: Int, value: String) {
            array[index] = value
        }

        fun read(index: Int): String {
            return array[index]
        }
    }

    @Test
    fun testReferenceArrayAccesses() {
        val write = ReferenceArray::write
        val read = ReferenceArray::read
        val index = 2
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, index, "a")
                }
                thread {
                    actor(read, index)
                }
                thread {
                    actor(write, index, "b")
                }
            }
        }
        val outcomes: Set<String> = setOf("", "a", "b")
        litmusTest(ReferenceArray::class.java, testScenario, outcomes) { results ->
            getValue<String>(results.parallelResults[1][0]!!)
        }
    }

    class AtomicVariable {
        // TODO: In the future we would likely want to switch to atomicfu primitives.
        //   However, atomicfu currently does not support various access modes that we intend to test here.
        private val variable = AtomicInteger()

        fun write(value: Int) {
            variable.set(value)
        }

        fun read(): Int {
            return variable.get()
        }

        fun compareAndSet(expected: Int, desired: Int): Boolean {
            return variable.compareAndSet(expected, desired)
        }

        fun addAndGet(delta: Int): Int {
            return variable.addAndGet(delta)
        }

        fun getAndAdd(delta: Int): Int {
            return variable.getAndAdd(delta)
        }
    }

    @Test
    fun testAtomicAccesses() {
        val read = AtomicVariable::read
        val write = AtomicVariable::write
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, 1)
                }
                thread {
                    actor(read)
                }
                thread {
                    actor(write, 2)
                }
            }
        }
        val outcomes: Set<Int> = setOf(0, 1, 2)
        litmusTest(AtomicVariable::class.java, testScenario, outcomes) { results ->
            getValue<Int>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testCompareAndSet() {
        val read = AtomicVariable::read
        val compareAndSet = AtomicVariable::compareAndSet
        val testScenario = scenario {
            parallel {
                thread {
                    actor(compareAndSet, 0, 1)
                }
                thread {
                    actor(compareAndSet, 0, 1)
                }
            }
            post {
                actor(read)
            }
        }
        val outcomes: Set<Triple<Boolean, Boolean, Int>> = setOf(
            Triple(true, false, 1),
            Triple(false, true, 1)
        )
        litmusTest(AtomicVariable::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Boolean>(results.parallelResults[0][0]!!)
            val r2 = getValue<Boolean>(results.parallelResults[1][0]!!)
            val r3 = getValue<Int>(results.postResults[0]!!)
            Triple(r1, r2, r3)
        }
    }

    @Test
    fun testGetAndAdd() {
        val read = AtomicVariable::read
        val getAndAdd = AtomicVariable::getAndAdd
        val testScenario = scenario {
            parallel {
                thread {
                    actor(getAndAdd, 1)
                }
                thread {
                    actor(getAndAdd, 1)
                }
            }
            post {
                actor(read)
            }
        }
        val outcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(0, 1, 2),
            Triple(1, 0, 2)
        )
        litmusTest(AtomicVariable::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Int>(results.parallelResults[0][0]!!)
            val r2 = getValue<Int>(results.parallelResults[1][0]!!)
            val r3 = getValue<Int>(results.postResults[0]!!)
            Triple(r1, r2, r3)
        }
    }

    @Test
    fun testAddAndGet() {
        val read = AtomicVariable::read
        val addAndGet = AtomicVariable::addAndGet
        val testScenario = scenario {
            parallel {
                thread {
                    actor(addAndGet, 1)
                }
                thread {
                    actor(addAndGet, 1)
                }
            }
            post {
                actor(read)
            }
        }
        val outcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(1, 2, 2),
            Triple(2, 1, 2)
        )
        litmusTest(AtomicVariable::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Int>(results.parallelResults[0][0]!!)
            val r2 = getValue<Int>(results.parallelResults[1][0]!!)
            val r3 = getValue<Int>(results.postResults[0]!!)
            Triple(r1, r2, r3)
        }
    }

    class GlobalAtomicVariable {

        companion object {
            // TODO: In the future we would likely want to switch to atomicfu primitives.
            //   However, atomicfu currently does not support various access modes that we intend to test here.
            private val globalVariable = AtomicInteger(0)
        }

        fun write(value: Int) {
            globalVariable.set(value)
        }

        fun read(): Int {
            return globalVariable.get()
        }

        fun compareAndSet(expected: Int, desired: Int): Boolean {
            return globalVariable.compareAndSet(expected, desired)
        }

        fun addAndGet(delta: Int): Int {
            return globalVariable.addAndGet(delta)
        }

        fun getAndAdd(delta: Int): Int {
            return globalVariable.getAndAdd(delta)
        }
    }

    @Test
    fun testGlobalAtomicAccesses() {
        val read = GlobalAtomicVariable::read
        val write = GlobalAtomicVariable::write
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, 1)
                }
                thread {
                    actor(read)
                }
                thread {
                    actor(write, 2)
                }
            }
        }
        val outcomes: Set<Int> = setOf(0, 1, 2)
        litmusTest(GlobalAtomicVariable::class.java, testScenario, outcomes) { results ->
            getValue<Int>(results.parallelResults[1][0]!!)
        }
    }

    // TODO: handle IntRef (var variables accessed from multiple threads)

    class VolatileReferenceVariable {
        @Volatile
        private var variable: String? = null

        companion object {
            private val updater =
                AtomicReferenceFieldUpdater.newUpdater(VolatileReferenceVariable::class.java, String::class.java, "variable")

            private val U = UnsafeHolder.UNSAFE

            @Suppress("DEPRECATION")
            private val offset = U.objectFieldOffset(VolatileReferenceVariable::class.java.getDeclaredField("variable"))

        }

        fun read(): String? {
            return variable
        }

        fun afuRead(): String? {
            return updater.get(this)
        }

        fun unsafeRead(): String? {
            return U.getObject(this, offset) as String?
        }

        fun write(value: String?) {
            variable = value
        }

        fun afuWrite(value: String?) {
            updater.set(this, value)
        }

        fun unsafeWrite(value: String?) {
            U.putObject(this, offset, value)
        }

        fun afuCompareAndSet(expected: String?, desired: String?): Boolean {
            return updater.compareAndSet(this, expected, desired)
        }

        fun unsafeCompareAndSet(expected: String?, desired: String?): Boolean {
            return U.compareAndSwapObject(this, offset, expected, desired)
        }

    }

    @Test
    fun testAtomicFieldUpdaterAccesses() {
        val read = VolatileReferenceVariable::afuRead
        val write = VolatileReferenceVariable::afuWrite
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, "a")
                }
                thread {
                    actor(read)
                }
                thread {
                    actor(write, "b")
                }
            }
        }
        val outcomes: Set<String?> = setOf(null, "a", "b")
        litmusTest(VolatileReferenceVariable::class.java, testScenario, outcomes) { results ->
            getValue(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testUnsafeAccesses() {
        val read = VolatileReferenceVariable::unsafeRead
        val write = VolatileReferenceVariable::unsafeWrite
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, "a")
                }
                thread {
                    actor(read)
                }
                thread {
                    actor(write, "b")
                }
            }
        }
        val outcomes: Set<String?> = setOf(null, "a", "b")
        litmusTest(VolatileReferenceVariable::class.java, testScenario, outcomes) { results ->
            getValue(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testAtomicFieldUpdaterCompareAndSet() {
        val read = VolatileReferenceVariable::afuRead
        val compareAndSet = VolatileReferenceVariable::afuCompareAndSet
        val testScenario = scenario {
            parallel {
                thread {
                    actor(compareAndSet, null, "a")
                }
                thread {
                    actor(compareAndSet, null, "a")
                }
            }
            post {
                actor(read)
            }
        }
        val outcomes: Set<Triple<Boolean, Boolean, String?>> = setOf(
            Triple(true, false, "a"),
            Triple(false, true, "a")
        )
        litmusTest(VolatileReferenceVariable::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Boolean>(results.parallelResults[0][0]!!)
            val r2 = getValue<Boolean>(results.parallelResults[1][0]!!)
            val r3 = getValue<String?>(results.postResults[0]!!)
            Triple(r1, r2, r3)
        }
    }

    @Test
    fun testUnsafeCompareAndSet() {
        val read = VolatileReferenceVariable::unsafeRead
        val compareAndSet = VolatileReferenceVariable::unsafeCompareAndSet
        val testScenario = scenario {
            parallel {
                thread {
                    actor(compareAndSet, null, "a")
                }
                thread {
                    actor(compareAndSet, null, "a")
                }
            }
            post {
                actor(read)
            }
        }
        val outcomes: Set<Triple<Boolean, Boolean, String?>> = setOf(
            Triple(true, false, "a"),
            Triple(false, true, "a")
        )
        litmusTest(VolatileReferenceVariable::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Boolean>(results.parallelResults[0][0]!!)
            val r2 = getValue<Boolean>(results.parallelResults[1][0]!!)
            val r3 = getValue<String?>(results.postResults[0]!!)
            Triple(r1, r2, r3)
        }
    }

    class UnsafeArrays {
        private var byteArray: ByteArray = ByteArray(8)
        private var shortArray: ShortArray = ShortArray(8)
        private var intArray: IntArray = IntArray(8)
        private var longArray: LongArray = LongArray(8)
        private var referenceArray: Array<String> = Array<String>(8) { "" }

        companion object {
            private val U = UnsafeHolder.UNSAFE

            private val byteArrayOffset = U.arrayBaseOffset(ByteArray::class.java)
            private val shortArrayOffset = U.arrayBaseOffset(ShortArray::class.java)
            private val intArrayOffset = U.arrayBaseOffset(IntArray::class.java)
            private val longArrayOffset = U.arrayBaseOffset(LongArray::class.java)
            private val referenceArrayOffset = U.arrayBaseOffset(Array<String>::class.java)

            private val byteIndexScale = U.arrayIndexScale(ByteArray::class.java)
            private val shortIndexScale = U.arrayIndexScale(ShortArray::class.java)
            private val intIndexScale = U.arrayIndexScale(IntArray::class.java)
            private val longIndexScale = U.arrayIndexScale(LongArray::class.java)
            private val referenceIndexScale = U.arrayIndexScale(Array<String>::class.java)

        }

        fun writeByte(index: Int, value: Byte) {
            U.putByte(byteArray, (index.toLong() * byteIndexScale) + byteArrayOffset, value)
        }

        fun writeShort(index: Int, value: Short) {
            U.putShort(shortArray, (index.toLong() * shortIndexScale) + shortArrayOffset, value)
        }

        fun writeInt(index: Int, value: Int) {
            U.putInt(intArray, (index.toLong() * intIndexScale) + intArrayOffset, value)
        }

        fun writeLong(index: Int, value: Long) {
            U.putLong(longArray, (index.toLong() * longIndexScale) + longArrayOffset, value)
        }

        fun writeReference(index: Int, value: String) {
            U.putObject(referenceArray, (index.toLong() * referenceIndexScale) + referenceArrayOffset, value)
        }

        fun readByte(index: Int): Byte {
            return U.getByte(byteArray, (index.toLong() * byteIndexScale) + byteArrayOffset)
        }

        fun readShort(index: Int): Short {
            return U.getShort(shortArray, (index.toLong() * shortIndexScale) + shortArrayOffset)
        }

        fun readInt(index: Int): Int {
            return U.getInt(intArray, (index.toLong() * intIndexScale) + intArrayOffset)
        }

        fun readLong(index: Int): Long {
            return U.getLong(longArray, (index.toLong() * longIndexScale) + longArrayOffset)
        }

        fun readReference(index: Int): String {
            return U.getObject(referenceArray, (index.toLong() * referenceIndexScale) + referenceArrayOffset) as String
        }

    }

    @Test
    fun testUnsafeByteArrayAccesses() {
        val read = UnsafeArrays::readByte
        val write = UnsafeArrays::writeByte
        val index = 2
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, index, 1.toByte())
                }
                thread {
                    actor(read, index)
                }
                thread {
                    actor(write, index, 2.toByte())
                }
            }
        }
        val outcomes: Set<Byte> = setOf(0, 1, 2)
        litmusTest(UnsafeArrays::class.java, testScenario, outcomes) { results ->
            getValue<Byte>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testUnsafeShortArrayAccesses() {
        val read = UnsafeArrays::readShort
        val write = UnsafeArrays::writeShort
        val index = 2
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, index, 1.toShort())
                }
                thread {
                    actor(read, index)
                }
                thread {
                    actor(write, index, 2.toShort())
                }
            }
        }
        val outcomes: Set<Short> = setOf(0, 1, 2)
        litmusTest(UnsafeArrays::class.java, testScenario, outcomes) { results ->
            getValue<Short>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testUnsafeIntArrayAccesses() {
        val read = UnsafeArrays::readInt
        val write = UnsafeArrays::writeInt
        val index = 2
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, index, 1)
                }
                thread {
                    actor(read, index)
                }
                thread {
                    actor(write, index, 2)
                }
            }
        }
        val outcomes: Set<Int> = setOf(0, 1, 2)
        litmusTest(UnsafeArrays::class.java, testScenario, outcomes) { results ->
            getValue<Int>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testUnsafeLongArrayAccesses() {
        val read = UnsafeArrays::readLong
        val write = UnsafeArrays::writeLong
        val index = 2
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, index, 1L)
                }
                thread {
                    actor(read, index)
                }
                thread {
                    actor(write, index, 2L)
                }
            }
        }
        val outcomes: Set<Long> = setOf(0, 1, 2)
        litmusTest(UnsafeArrays::class.java, testScenario, outcomes) { results ->
            getValue<Long>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testUnsafeReferenceArrayAccesses() {
        val read = UnsafeArrays::readReference
        val write = UnsafeArrays::writeReference
        val index = 2
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, index, "a")
                }
                thread {
                    actor(read, index)
                }
                thread {
                    actor(write, index, "b")
                }
            }
        }
        val outcomes: Set<String> = setOf("", "a", "b")
        litmusTest(UnsafeArrays::class.java, testScenario, outcomes) { results ->
            getValue<String>(results.parallelResults[1][0]!!)
        }
    }

    class SynchronizedVariable {

        private var variable: Int = 0

        @Synchronized
        fun write(value: Int) {
            variable = value
        }

        @Synchronized
        fun read(): Int {
            return variable
        }

        @Synchronized
        fun waitAndRead(): Int {
            // TODO: handle spurious wake-ups?
            (this as Object).wait()
            return variable
        }

        @Synchronized
        fun writeAndNotify(value: Int) {
            variable = value
            (this as Object).notify()
        }

        @Synchronized
        fun compareAndSet(expected: Int, desired: Int): Boolean {
            return if (variable == expected) {
                variable = desired
                true
            } else false
        }

        @Synchronized
        fun addAndGet(delta: Int): Int {
            variable += delta
            return variable
        }

        @Synchronized
        fun getAndAdd(delta: Int): Int {
            val value = variable
            variable += delta
            return value
        }

    }

    //TODO: Ignored for now need to fix monitor tracker in Managed strategy.
    @Ignore
    @Test
    fun testSynchronized() {
        val read = SynchronizedVariable::read
        val addAndGet = SynchronizedVariable::addAndGet
        val testScenario = scenario {
            parallel {
                thread {
                    actor(addAndGet, 1)
                }
                thread {
                    actor(addAndGet, 1)
                }
            }
            post {
                actor(read)
            }
        }
        val outcomes: Set<Triple<Int, Int, Int>> = setOf(
            Triple(1, 2, 2),
            Triple(2, 1, 2)
        )
        // TODO: investigate why `executionCount = 3`
        litmusTest(SynchronizedVariable::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Int>(results.parallelResults[0][0]!!)
            val r2 = getValue<Int>(results.parallelResults[1][0]!!)
            val r3 = getValue<Int>(results.postResults[0]!!)
            Triple(r1, r2, r3)
        }
    }

    //TODO: Ignored for now need to fix monitor tracker in Managed strategy.
    @Ignore
    @Test
    fun testWaitNotify() {
        val writeAndNotify = SynchronizedVariable::writeAndNotify
        val waitAndRead = SynchronizedVariable::waitAndRead
        val testScenario = scenario {
            parallel {
                thread {
                    actor(writeAndNotify, 1)
                }
                thread {
                    actor(waitAndRead)
                }
            }
        }
        val outcomes = setOf(1)
        litmusTest(SynchronizedVariable::class.java, testScenario, outcomes) { results ->
            getValue<Int>(results.parallelResults[1][0]!!)
        }
    }

    class ParkLatchedVariable {

        private var variable: Int = 0

        @Volatile
        private var parkedThread: Thread? = null

        @Volatile
        private var delivered: Boolean = false

        fun parkAndRead(): Int? {
            // TODO: handle spurious wake-ups?
            parkedThread = Thread.currentThread()
            return if (delivered) {
                park()
                variable
            } else null
        }

        fun writeAndUnpark(value: Int) {
            variable = value
            val thread = parkedThread
            if (thread != null)
                delivered = true
            unpark(thread)
        }

    }

    @Test
    fun testParking() {
        val writeAndUnpark = ParkLatchedVariable::writeAndUnpark
        val parkAndRead = ParkLatchedVariable::parkAndRead
        val testScenario = scenario {
            parallel {
                thread {
                    actor(writeAndUnpark, 1)
                }
                thread {
                    actor(parkAndRead)
                }
            }
        }
        val outcomes = setOf(null, 1)
        litmusTest(ParkLatchedVariable::class.java, testScenario, outcomes, executionCount = 3) { results ->
            getValue<Int?>(results.parallelResults[1][0]!!)
        }
    }

    class CoroutineWrapper {

        val continuation = AtomicReference<CancellableContinuation<Int>>()
        var resumedOrCancelled = AtomicBoolean(false)

        // TODO: Dubious fix was done here
        @Operation(promptCancellation = true)
        suspend fun suspend(): Int {
            return suspendCancellableCoroutine<Int> { continuation ->
                this.continuation.set(continuation)
            }
        }

        @InternalCoroutinesApi
        @Operation
        fun resume(value: Int): Boolean {
            this.continuation.get()?.let {
                if (resumedOrCancelled.compareAndSet(false, true)) {
                    val token = it.tryResume(value)
                    if (token != null)
                        it.completeResume(token)
                    return (token != null)
                }
            }
            return false
        }

        @Operation
        fun cancel(): Boolean {
            this.continuation.get()?.let {
                if (resumedOrCancelled.compareAndSet(false, true)) {
                    it.cancel(CancelledOperationException)
                    return true
                }
            }
            return false
        }
    }

    internal object CancelledOperationException : Exception()

    @Ignore
    @InternalCoroutinesApi
    @Test(timeout = TIMEOUT)
    fun testResume() {
        val suspend = CoroutineWrapper::suspend
        val resume = CoroutineWrapper::resume
        val testScenario = scenario {
            parallel {
                thread {
                    actor(suspend)
                }
                thread {
                    actor(resume, 1)
                }
            }
        }
        val outcomes = setOf(
            (SuspendedResult to false),
            (1 to true)
        )
        litmusTest(CoroutineWrapper::class.java, testScenario, outcomes, executionCount = UNKNOWN) { results ->
            val r = getValueSuspended(results.parallelResults[0][0]!!)
            val b = getValue<Boolean>(results.parallelResults[1][0]!!)
            (r to b)
        }
    }

    @Ignore
    @InternalCoroutinesApi
    @Test(timeout = TIMEOUT)
    fun testCancel() {
        val suspendActor = Actor(
            method = CoroutineWrapper::suspend.javaMethod!!,
            arguments = listOf(),
            cancelOnSuspension = false
        )
        val cancel = CoroutineWrapper::cancel
        val testScenario = scenario {
            parallel {
                thread {
                    add(suspendActor)
                }
                thread {
                    actor(cancel)
                }
            }
        }
        val outcomes = setOf(
            (SuspendedResult to false),
            (CancelledOperationException to true)
        )
        litmusTest(CoroutineWrapper::class.java, testScenario, outcomes, executionCount = UNKNOWN) { results ->
            val r = getValueSuspended(results.parallelResults[0][0]!!)
            val b = getValue<Boolean>(results.parallelResults[1][0]!!)
            (r to b)
        }
    }

    @Ignore
    @InternalCoroutinesApi
    @Test(timeout = TIMEOUT)
    fun testLincheckCancellation() {
        val suspendActor = Actor(
            method = CoroutineWrapper::suspend.javaMethod!!,
            arguments = listOf(),
            cancelOnSuspension = true
        )
        val resume = CoroutineWrapper::resume
        val testScenario = scenario {
            parallel {
                thread {
                    add(suspendActor)
                }
                thread {
                    actor(resume, 1)
                }
            }
        }
        val outcomes = setOf(
            (CancelledResult to false),
            (1 to true)
        )
        litmusTest(CoroutineWrapper::class.java, testScenario, outcomes, executionCount = UNKNOWN) { results ->
            val r = getValueSuspended(results.parallelResults[0][0]!!)
            val b = getValue<Boolean>(results.parallelResults[1][0]!!)
            (r to b)
        }
    }

    @Ignore
    @InternalCoroutinesApi
    @Test(timeout = TIMEOUT)
    fun testLincheckPromptCancellation() {
        val suspendActor = Actor(
            method = CoroutineWrapper::suspend.javaMethod!!,
            arguments = listOf(),
            cancelOnSuspension = true,
            promptCancellation = true,
        )
        val resume = CoroutineWrapper::resume
        val testScenario = scenario {
            parallel {
                thread {
                    add(suspendActor)
                }
                thread {
                    actor(resume, 1)
                }
            }
        }
        val outcomes = setOf(
            (CancelledResult to false),
            (CancelledResult to true),
            // (1 to true),
        )
        litmusTest(CoroutineWrapper::class.java, testScenario, outcomes, executionCount = UNKNOWN) { results ->
            val r = getValueSuspended(results.parallelResults[0][0]!!)
            val b = getValue<Boolean>(results.parallelResults[1][0]!!)
            (r to b)
        }
    }

    @Ignore
    @InternalCoroutinesApi
    @Test(timeout = TIMEOUT)
    fun testResumeCancel() {
        val suspendActor = Actor(
            method = CoroutineWrapper::suspend.javaMethod!!,
            arguments = listOf(),
            cancelOnSuspension = false
        )
        val resume = CoroutineWrapper::resume
        val cancel = CoroutineWrapper::cancel
        val testScenario = scenario {
            parallel {
                thread {
                    add(suspendActor)
                }
                thread {
                    actor(resume, 1)
                }
                thread {
                    actor(cancel)
                }
            }
        }
        val outcomes = setOf(
            Triple(SuspendedResult, false, false),
            Triple(1, true, false),
            Triple(CancelledOperationException, false, true)
        )
        litmusTest(CoroutineWrapper::class.java, testScenario, outcomes, executionCount = UNKNOWN) { results ->
            val r = getValueSuspended(results.parallelResults[0][0]!!)
            val b1 = getValue<Boolean>(results.parallelResults[1][0]!!)
            val b2 = getValue<Boolean>(results.parallelResults[2][0]!!)
            Triple(r, b1, b2)
        }
    }

    @Ignore
    @InternalCoroutinesApi
    @Test(timeout = TIMEOUT)
    fun test1Resume2Suspend() {
        val suspend = CoroutineWrapper::suspend
        val resume = CoroutineWrapper::resume
        val testScenario = scenario {
            parallel {
                thread {
                    actor(suspend)
                }
                thread {
                    actor(suspend)
                }
                thread {
                    actor(resume, 1)
                }
            }
        }
        val outcomes = setOf(
            Triple(SuspendedResult, SuspendedResult, false),
            Triple(SuspendedResult, 1, true),
            Triple(1, SuspendedResult, true),
        )
        litmusTest(CoroutineWrapper::class.java, testScenario, outcomes, executionCount = UNKNOWN) { results ->
            val r1 = getValueSuspended(results.parallelResults[0][0]!!)
            val r2 = getValueSuspended(results.parallelResults[1][0]!!)
            val b = getValue<Boolean>(results.parallelResults[2][0]!!)
            Triple(r1, r2, b)
        }
    }

    @Ignore
    @InternalCoroutinesApi
    @Test(timeout = TIMEOUT)
    fun test2Resume1Suspend() {
        val suspend = CoroutineWrapper::suspend
        val resume = CoroutineWrapper::resume
        val testScenario = scenario {
            parallel {
                thread {
                    actor(suspend)
                }
                thread {
                    actor(resume, 1)
                }
                thread {
                    actor(resume, 2)
                }
            }
        }
        val outcomes = setOf(
            Triple(SuspendedResult, false, false),
            Triple(1, true, false),
            Triple(2, false, true),
        )
        litmusTest(CoroutineWrapper::class.java, testScenario, outcomes, executionCount = UNKNOWN) { results ->
            val r = getValueSuspended(results.parallelResults[0][0]!!)
            val b1 = getValue<Boolean>(results.parallelResults[1][0]!!)
            val b2 = getValue<Boolean>(results.parallelResults[2][0]!!)
            Triple(r, b1, b2)
        }
    }


    @Test
    fun testObjectsObjectsIDsDoNotBreakOnBackwardRevisit() {
        // This test is specifically made to make break during backward revisit of allocated objects
        // We had a bug in the object tracker where the replay order of the events affected the ObjectIDs of the replayed objects
        // In the initial invocation, we first assigned objectID 1 to Bar(1) and 2 to Bar(2)
        // In the next invocation we do a backward revisit from x.set(1) to x.get()
        // This means that we first replay Bar(2) and the x.set(1) and go to actually run x.get() and Bar(1).
        // During replay this meant that we wrongly gave Bar(2) the objectID of 1 (instead of the original 2 value)
        class Bar(a: Int) {
            val b = a
            override fun toString(): String {
                return "BAR:($b)"
            }
        }
        class Foo {
            var x = AtomicInteger(0)
            @Volatile var y = Bar(0)
            fun one() {
                x.get() == 0
                y = Bar(1)
                val x = y
            }
            fun two() {
                y = Bar(2)
                x.set(1)
            }
        }
        val testScenario = scenario {
            parallel {
                thread {
                    actor(Foo::one)
                }
                thread {
                    actor(Foo::two)
                }
            }
        }
        val outcomes: Set<Unit> = setOf(Unit)
        litmusTest(Foo::class.java, testScenario, outcomes, UNKNOWN) { results ->
            Unit
        }
    }

    @Test
    fun testDoNotGCExternalObjects() {
        // This test tries to force the GC to collect the external Baz(0), which is left unused after each testInvocation
        // The ObjectTracker used to rely on a weak reference to the GC'd object, which would cause trouble
        class Baz(a: Int) {
            @Volatile var b = a
            override fun toString(): String {
                return "BAZ:($b)"
            }
        }
        class Foo {
            // The Baz(0) here is an external object
            // Since it is the intial value of a field of the test class
            @Volatile var y = Baz(0)
            fun one() : Int {
                val res = y.b
                return 0
            }
            fun two() {
                y.b = 0
                y.b = 1
                y.b = 2
            }
        }
        val testScenario = scenario {
            parallel {
                thread {
                    actor(Foo::one)
                }
                thread {
                    actor(Foo::two)
                }
            }
        }
        val outcomes: Set<Int> = setOf(0)
        litmusTest(Foo::class.java, testScenario, outcomes, UNKNOWN) { results ->
            val b1 = getValue<Int>(results.parallelResults[0][0]!!)
            System.gc() // Kindly suggest the GC to do its thing. Should make the test fail more consistently.
            System.gc()
            return@litmusTest b1
        }
    }

    @Test
    fun testObjectIdsStableCrazy() {
        // This test tries various nesting levels to see if we can break the replay order of the object tracker
        // Kind of a throw stuff at the wall and see what sticks approach
        class Bar(a: AtomicInteger) {
            @Volatile
            var b = a
            override fun toString(): String {
                return "BAR:($b)"
            }
        }
        class Baz(a: Bar) {
            @Volatile var b = a
            override fun toString(): String {
                return "BAZ:($b)"
            }
        }
        class Foo {
            @Volatile var y = Baz(Bar(AtomicInteger(0)))
            fun one() : Int {
                y.b = Bar(AtomicInteger(1))
                val res = y.b.b.get()
                return res
            }
            fun two() {
                y.b = Bar(AtomicInteger(2))
                y.b.b = AtomicInteger(3)
                y.b.b.set(4)
            }
        }
        val testScenario = scenario {
            parallel {
                thread {
                    actor(Foo::one)
                }
                thread {
                    actor(Foo::two)
                }
            }
        }
        val outcomes: Set<Int> = setOf(1,2,3,4)
        litmusTest(Foo::class.java, testScenario, outcomes, UNKNOWN) { results ->
            val b1 = getValue<Int>(results.parallelResults[0][0]!!)
            return@litmusTest b1
        }
    }



    @Test
    fun testStringsObjectsIDsDoNotBreakOnBackwardRevisit() {
        // Same thing as the test above, but with strings, which work a bit differently
        class Foo {
            var x = AtomicInteger(0)
            @Volatile var y = ""

            fun one() {
                x.get() == 0
                y = "a"
            }

            fun two() {
                y = "b"
                x.set(1)
            }

        }
        val testScenario = scenario {
            parallel {
                thread {
                    actor(Foo::one)
                }
                thread {
                    actor(Foo::two)
                }
            }
        }
        val outcomes: Set<Unit> = setOf(Unit)
        litmusTest(Foo::class.java, testScenario, outcomes, UNKNOWN) { results ->
            Unit
        }
    }
}