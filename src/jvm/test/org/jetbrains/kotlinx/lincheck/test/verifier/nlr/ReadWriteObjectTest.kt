/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test.verifier.nlr

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.CrashFree
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Ignore
import org.junit.Test

private const val THREADS_NUMBER = 3

interface RWO<T> {
    fun read(): T?
    fun write(value: T, p: Int)
}

@CrashFree
fun nextId(threadId: Int) = operationCounter[threadId]++
private val operationCounter = IntArray(THREADS_NUMBER + 2) { 0 }

internal class SequenceParameterGenerator(configuration: String) : ParameterGenerator<Int> {
    private val counter = atomic(0)
    override fun generate() = counter.incrementAndGet()
}

@StressCTest(
    sequentialSpecification = SequentialReadWriteObject::class,
    threads = THREADS_NUMBER,
    recover = Recover.NRL,
    minimizeFailedScenario = false
)
internal class ReadWriteObjectTest {
    private val rwo = NRLReadWriteObject<Pair<Int, Int>>(THREADS_NUMBER + 2)

    @Operation
    fun read() = rwo.read()?.first

    @Operation
    fun write(
        @Param(gen = ThreadIdGen::class) threadId: Int,
        value: Int,
        @Param(gen = SequenceParameterGenerator::class) operationId: Int
    ) = rwo.write(value to operationId, threadId)

    @Test
    fun test() = LinChecker.check(this.javaClass)
}

private val nullObject = Any()

internal open class SequentialReadWriteObject : VerifierState() {
    private var value: Int? = null

    fun read() = value
    fun write(newValue: Int) {
        value = newValue
    }

    fun write(ignore: Int, newValue: Int, ignore2: Int) = write(newValue)
    override fun extractState() = value ?: nullObject
}

/**
 * Values must be unique.
 * Use (value, op) with unique op to emulate this.
 * @see  <a href="https://www.cs.bgu.ac.il/~hendlerd/papers/NRL.pdf">Nesting-Safe Recoverable Linearizability</a>
 */
class NRLReadWriteObject<T>(threadsCount: Int, initial: T? = null) : RWO<T> {
    private val register = nonVolatile(initial)

    // (state, value) for every thread
    private val state = MutableList(threadsCount) { nonVolatile(0 to null as T?) }

    @Recoverable
    override fun read(): T? = register.value

    @Recoverable(recoverMethod = "writeRecover")
    override fun write(value: T, p: Int) = writeImpl(value, p)

    private fun writeImpl(value: T, p: Int) {
        val tmp = register.value
        state[p].value = 1 to tmp
        state[p].flush()
//        register.value = value
//        register.flush()
        register.setAndFlush(value) // TODO is it possible to replace with code above?
        state[p].value = 0 to value
        state[p].flush()
    }

    fun writeRecover(value: T, p: Int) {
        val (flag, current) = state[p].value
        if (flag == 0 && current != value) return writeImpl(value, p)
        else if (flag == 1 && current === register.value) return writeImpl(value, p)
        state[p].value = 0 to value
        state[p].flush()
    }
}

@StressCTest(
    sequentialSpecification = SequentialReadWriteObject::class,
    threads = THREADS_NUMBER,
    recover = Recover.NRL,
    minimizeFailedScenario = false
)
internal abstract class ReadWriteObjectFailingTest {
    abstract fun createRWO(): RWO<Pair<Int, Int>>
    private val rwo = createRWO()

    @Operation
    fun read() = rwo.read()?.first

    @Operation
    fun write(
        @Param(gen = ThreadIdGen::class) threadId: Int,
        value: Int,
        @Param(gen = SequenceParameterGenerator::class) operationId: Int
    ) = rwo.write(value to operationId, threadId)

    @Test(expected = LincheckAssertionError::class)
    fun test() = LinChecker.check(this.javaClass)
}

internal class ReadWriteObjectFailingTest1 : ReadWriteObjectFailingTest() {
    override fun createRWO() = NRLFailingReadWriteObject1<Pair<Int, Int>>(THREADS_NUMBER + 2)
}

internal class ReadWriteObjectFailingTest2 : ReadWriteObjectFailingTest() {
    override fun createRWO() = NRLFailingReadWriteObject2<Pair<Int, Int>>(THREADS_NUMBER + 2)
}

internal class ReadWriteObjectFailingTest3 : ReadWriteObjectFailingTest() {
    override fun createRWO() = NRLFailingReadWriteObject3<Pair<Int, Int>>(THREADS_NUMBER + 2)
}

internal class ReadWriteObjectFailingTest4 : ReadWriteObjectFailingTest() {
    override fun createRWO() = NRLFailingReadWriteObject4<Pair<Int, Int>>(THREADS_NUMBER + 2)
}

internal class ReadWriteObjectFailingTest5 : ReadWriteObjectFailingTest() {
    override fun createRWO() = NRLFailingReadWriteObject5<Pair<Int, Int>>(THREADS_NUMBER + 2)
}

internal class NRLFailingReadWriteObject1<T>(threadsCount: Int) : RWO<T> {
    private val register = nonVolatile<T?>(null)

    // (state, value) for every thread
    private val state = MutableList(threadsCount) { nonVolatile(0 to null as T?) }

    @Recoverable
    override fun read(): T? = register.value

    @Recoverable(recoverMethod = "writeRecover")
    override fun write(value: T, p: Int) = writeImpl(value, p)

    private fun writeImpl(value: T, p: Int) {
        val tmp = register.value
        state[p].value = 1 to tmp
        // here should be state[p].flush()
        register.setAndFlush(value)
        state[p].value = 0 to value
        state[p].flush()
    }

    fun writeRecover(value: T, p: Int) {
        val (flag, current) = state[p].value
        if (flag == 0 && current != value) return writeImpl(value, p)
        else if (flag == 1 && current === register.value) return writeImpl(value, p)
        state[p].value = 0 to value
        state[p].flush()
    }
}

internal class NRLFailingReadWriteObject2<T>(threadsCount: Int) : RWO<T> {
    private val register = nonVolatile<T?>(null)

    // (state, value) for every thread
    private val state = MutableList(threadsCount) { nonVolatile(0 to null as T?) }

    @Recoverable
    override fun read(): T? = register.value

    @Recoverable(recoverMethod = "writeRecover")
    override fun write(value: T, p: Int) = writeImpl(value, p)

    private fun writeImpl(value: T, p: Int) {
        val tmp = register.value
        state[p].value = 1 to tmp
        state[p].flush()
        register.setAndFlush(value)
        state[p].value = 0 to value
        // here should be state[p].flush()
    }

    fun writeRecover(value: T, p: Int) {
        val (flag, current) = state[p].value
        if (flag == 0 && current != value) return writeImpl(value, p)
        else if (flag == 1 && current === register.value) return writeImpl(value, p)
        state[p].value = 0 to value
        state[p].flush()
    }
}

internal class NRLFailingReadWriteObject3<T>(threadsCount: Int) : RWO<T> {
    private val register = nonVolatile<T?>(null)

    // (state, value) for every thread
    private val state = MutableList(threadsCount) { nonVolatile(0 to null as T?) }

    @Recoverable
    override fun read(): T? = register.value

    @Recoverable(recoverMethod = "writeRecover")
    override fun write(value: T, p: Int) = writeImpl(value, p)

    private fun writeImpl(value: T, p: Int) {
        val tmp = register.value
        state[p].value = 1 to tmp
        state[p].flush()
        register.setAndFlush(value)
        state[p].value = 0 to value
        state[p].flush()
    }

    fun writeRecover(value: T, p: Int) {
        val (flag, current) = state[p].value
        if (flag == 0 && current != value) return writeImpl(value, p)
        else if (flag == 1 && current === register.value) return writeImpl(value, p)
        state[p].value = 0 to value
        // here should be state[p].flush()
    }
}

internal class NRLFailingReadWriteObject4<T>(threadsCount: Int) : RWO<T> {
    private val register = nonVolatile<T?>(null)

    // (state, value) for every thread
    private val state = MutableList(threadsCount) { nonVolatile(0 to null as T?) }

    @Recoverable
    override fun read(): T? = register.value

    @Recoverable(recoverMethod = "writeRecover")
    override fun write(value: T, p: Int) = writeImpl(value, p)

    private fun writeImpl(value: T, p: Int) {
        val tmp = register.value
        state[p].value = 1 to tmp
        state[p].flush()
        register.setAndFlush(value)
        state[p].value = 0 to value
        state[p].flush()
    }

    fun writeRecover(value: T, p: Int) {
        val (flag, current) = state[p].value
        if (flag == 0/* here should be && current != value */) return writeImpl(value, p)
        else if (flag == 1 && current === register.value) return writeImpl(value, p)
        state[p].value = 0 to value
        state[p].flush()
    }
}

internal class NRLFailingReadWriteObject5<T>(threadsCount: Int) : RWO<T> {
    private val register = nonVolatile<T?>(null)

    // (state, value) for every thread
    private val state = MutableList(threadsCount) { nonVolatile(0 to null as T?) }

    @Recoverable
    override fun read(): T? = register.value

    @Recoverable(recoverMethod = "writeRecover")
    override fun write(value: T, p: Int) = writeImpl(value, p)

    private fun writeImpl(value: T, p: Int) {
        val tmp = register.value
        state[p].value = 1 to tmp
        state[p].flush()
        register.setAndFlush(value)
        state[p].value = 0 to value
        state[p].flush()
    }

    fun writeRecover(value: T, p: Int) {
        val (flag, current) = state[p].value
        if (flag == 0 && current != value) return writeImpl(value, p)
        else if (flag == 1 /* here should be && current === register.value */) return writeImpl(value, p)
        state[p].value = 0 to value
        state[p].flush()
    }
}
