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

import org.jetbrains.kotlinx.lincheck.CrashResult
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.ValueResult
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Sync
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.scenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.test.verifier.nrl.AbstractNVMLincheckFailingTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.durable.BufferedDurableLinearizabilityVerifier
import org.junit.Assert
import org.junit.Test

private const val THREADS = 3

@Param(name = "key", gen = IntGen::class, conf = "0:3")
internal class CASTest : AbstractNVMLincheckFailingTest(Recover.BUFFERED_DURABLE, THREADS, SequentialCAS::class) {
    private val cas = DurableCAS()

    @Operation
    fun read() = cas.read()

    @Operation
    fun cas(@Param(name = "key") old: Int, @Param(name = "key") new: Int) = cas.cas(old, new)

    @Operation
    @Sync
    fun sync() = cas.sync()

    override fun <O : Options<O, *>> O.customize() {
        iterations(0)
        addCustomScenario {
            initial { actor(::cas, 0, 2) }
            parallel {
                thread { actor(::cas, 1, 0); actor(::cas, 0, 1); actor(::sync) }
                thread { actor(::cas, 2, 1); actor(::sync); actor(::read) }
            }
            post { actor(::read) }
        }
    }

    override fun StressOptions.customize() {
        invocationsPerIteration(1e7.toInt())
    }

    override fun ModelCheckingOptions.customize() {
        invocationsPerIteration(1e5.toInt())
    }

    @Test
    fun testVerifier1() {
        val verifier = BufferedDurableLinearizabilityVerifier(SequentialCAS::class.java)
        val scenario = scenario {
            initial { actor(::cas, 0, 1) }
            parallel {
                thread { actor(::read); actor(::read) }
                thread { actor(::cas, 1, 3) }
            }
            post { actor(::read); actor(::cas, 1, 2); actor(::read) }
        }
        val executionResult = ExecutionResult(
            listOf(ValueResult(true)),
            listOf(
                listOf(result(ValueResult(1), 0, 0), result(ValueResult(1), 1, 1)),
                listOf(result(CrashResult().apply { crashedActors = intArrayOf(-1, 0) }, 0, 0))
            ),
            listOf(ValueResult(1), ValueResult(true), ValueResult(2))
        )
        Assert.assertTrue(verifier.verifyResults(scenario, executionResult))
    }
}

internal class SequentialCAS : VerifierState() {
    private var data = 0
    override fun extractState() = data
    fun read() = data
    fun sync() {}
    fun cas(old: Int, new: Int) = (data == old).also {
        if (it) data = new
    }
}

private const val DIRTY = 1 shl 20

/**
 * @see  <a href="http://justinlevandoski.org/papers/mwcas.pdf">Easy Lock-Free Indexing in Non-Volatile Memory</a>
 */
internal open class DurableCAS {
    protected val word = nonVolatile(0)

    open fun read(): Int {
        val data = word.value
        if ((data and DIRTY) != 0) {
            word.flush()
            /*
            ABA problem here.
            We mark value as flushed meaning the old cas.
            The other thread sees the value flushed the new cas.
             */
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
internal abstract class CASFailingTest : AbstractNVMLincheckFailingTest(Recover.BUFFERED_DURABLE, THREADS, SequentialCAS::class) {
    protected abstract val cas: DurableCAS

    @Operation
    fun read() = cas.read()

    @Operation
    fun cas(@Param(name = "key") old: Int, @Param(name = "key") new: Int) = cas.cas(old, new)

    @Operation
    @Sync
    fun sync() = cas.sync()
}

internal class CASFailingTest1 : CASFailingTest() {
    override val cas = DurableFailingCAS1()
}


internal open class DurableFailingCAS1 : DurableCAS() {
    override fun cas(old: Int, new: Int): Boolean {
        read()
        // read word value may be dirty => word.value != old
        return word.compareAndSet(old, new or DIRTY)
    }
}
