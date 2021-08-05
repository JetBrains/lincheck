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

package org.jetbrains.kotlinx.lincheck.test.verifier.durable.buffered

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.test.verifier.nlr.AbstractNVMLincheckFailingTest
import org.jetbrains.kotlinx.lincheck.test.verifier.nlr.AbstractNVMLincheckTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState

private const val THREADS = 3

@Param(name = "key", gen = IntGen::class, conf = "0:3")
internal class CASTest : AbstractNVMLincheckTest(Recover.DURABLE, THREADS, SequentialCAS::class) {
    private val cas = DurableCAS()

    @Operation
    fun read() = cas.read()

    @Operation
    fun cas(@Param(name = "key") old: Int, @Param(name = "key") new: Int) = cas.cas(old, new).also { cas.sync() }
}

internal class SequentialCAS : VerifierState() {
    private var data = 0
    override fun extractState() = data
    fun read() = data
    fun cas(old: Int, new: Int) = (data == old).also {
        if (it) data = new
    }
}

private const val DIRTY = 1 shl 20
internal open class DurableCAS {
    protected val word = nonVolatile(0)

    open fun read(): Int {
        val data = word.value
        if ((data and DIRTY) != 0) {
            word.flush()
            word.compareAndSet(data, data and DIRTY.inv())
        }
        return data and DIRTY.inv()
    }

    open fun cas(old: Int, new: Int): Boolean {
        // retry reading until success/failure as word.value may be dirty
        while (true) {
            val data = read()
            if (data != old) return false
            if (word.compareAndSet(old, new or DIRTY)) return true
        }
    }

    open fun sync() {
        read()
    }
}

@Param(name = "key", gen = IntGen::class, conf = "0:3")
internal abstract class CASFailingTest : AbstractNVMLincheckFailingTest(Recover.DURABLE, THREADS, SequentialCAS::class) {
    protected abstract val cas: DurableCAS

    @Operation
    fun read() = cas.read()

    @Operation
    fun cas(@Param(name = "key") old: Int, @Param(name = "key") new: Int) = cas.cas(old, new).also { cas.sync() }
}

internal class CASFailingTest1 : CASFailingTest() {
    override val cas = DurableFailingCAS1()
}


/**
 * @see  <a href="http://justinlevandoski.org/papers/mwcas.pdf">Easy Lock-Free Indexing in Non-Volatile Memory</a>
 */
internal open class DurableFailingCAS1 : DurableCAS() {
    override fun cas(old: Int, new: Int): Boolean {
        read()
        // read word value may be dirty => word.value != old
        return word.compareAndSet(old, new or DIRTY)
    }
}
