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

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.CrashFree
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.nvm.NVMCache
import org.jetbrains.kotlinx.lincheck.nvm.Persistent
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

private const val THREADS_NUMBER = 2


@StressCTest(
    sequentialSpecification = SequentialReadWriteObject::class,
    threads = THREADS_NUMBER,
    addCrashes = true
)
class ReadWriteObjectTest {
    private val operationCounter = IntArray(THREADS_NUMBER + 2) { 0 }
    private val rwo = NRLReadWriteObject<Record>(THREADS_NUMBER + 2)

    @CrashFree
    private data class Record(val p: Int, val counter: Int, val value: Int)

    @Operation
    fun read() = rwo.read()?.value

    @Operation
    fun write(@Param(gen = ThreadIdGen::class) threadId: Int, value: Int) =
        rwo.write(threadId, Record(threadId, operationCounter[threadId]++, value))

    @Test
    fun test() = LinChecker.check(this.javaClass)
}

private val nullObject = Any()

open class SequentialReadWriteObject : VerifierState() {
    private var value: Int? = null

    fun read() = value
    fun write(newValue: Int) {
        value = newValue
    }

    fun write(ignore: Int, newValue: Int) = write(newValue)
    override fun extractState() = value ?: nullObject
}

/**
 * Values must be unique.
 * Use (value, op) with unique op to emulate this.
 * @see  <a href="https://www.cs.bgu.ac.il/~hendlerd/papers/NRL.pdf">Nesting-Safe Recoverable Linearizability</a>
 */
class NRLReadWriteObject<T>(threadsCount: Int) : VerifierState() {
    @Volatile
    private var R: T? = null

    // (state, value) for every thread
    private val S = MutableList<Persistent<Pair<Int, T?>>>(threadsCount) { Persistent(0 to null) }

    public override fun extractState() = R ?: nullObject

    @Recoverable
    fun read() = R

    @Recoverable(recoverMethod = "writeRecover")
    fun write(p: Int, value: T) {
        writeImpl(p, value)
    }

    fun writeImpl(p: Int, value: T) {
        val tmp = R
        S[p].write(p, 1 to tmp)
        NVMCache.flush(p)
        R = value
        S[p].write(p, 0 to value)
        NVMCache.flush(p)
    }

    fun writeRecover(p: Int, value: T) {
        val (flag, current) = S[p].read(p)!!
        if (flag == 0 && current != value) return writeImpl(p, value)
        else if (flag == 1 && current == R) return writeImpl(p, value)
        S[p].write(p, 0 to value)
        NVMCache.flush(p)
    }
}
