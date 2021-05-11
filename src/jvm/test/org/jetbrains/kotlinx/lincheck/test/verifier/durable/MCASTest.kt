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

import org.jetbrains.kotlinx.lincheck.annotations.DurableRecoverAll
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.NonVolatileRef
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.ParameterGenerator
import org.jetbrains.kotlinx.lincheck.strategy.DeadlockWithDumpFailure
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.test.verifier.nlr.AbstractNVMLincheckFailingTest
import org.jetbrains.kotlinx.lincheck.test.verifier.nlr.AbstractNVMLincheckTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import kotlin.random.Random
import kotlin.reflect.KClass

private const val THREADS_NUMBER = 3
private const val N = 3

internal interface MCAS {
    operator fun get(index: Int): Int
    fun compareAndSet(old: List<Int>, new: List<Int>): Boolean
    fun recover()
}

internal class ListGen(config: String) : ParameterGenerator<List<Int>> {
    private val used = hashSetOf(List(N) { 0 })
    override fun generate() =
        if (Random.nextBoolean()) List(N) { Random.nextInt(2) }.also { used.add(it) }
        else used.random()
}

@Param(name = "list", gen = ListGen::class)
internal class MCASTest : AbstractNVMLincheckTest(Recover.DURABLE, THREADS_NUMBER, SequentialMCAS::class) {
    private val cas = DurableMCAS()

    @Operation
    fun get(@Param(gen = IntGen::class, conf = "0:${N - 1}") index: Int) = cas[index]

    @Operation
    fun compareAndSet(@Param(name = "list") old: List<Int>, @Param(name = "list") new: List<Int>) =
        cas.compareAndSet(old, new)

    @DurableRecoverAll
    fun recover() = cas.recover()
}

internal class SequentialMCAS : MCAS, VerifierState() {
    private val data = MutableList(N) { 0 }
    override fun extractState() = data
    override fun get(index: Int) = data[index]
    override fun recover() {}
    override fun compareAndSet(old: List<Int>, new: List<Int>): Boolean {
        if (!data.indices.all { i -> data[i] == old[i] }) return false
        data.indices.forEach { i -> data[i] = new[i] }
        return true
    }
}

internal data class WordDescriptor(val old: Int, val new: Int, val parent: MCASDescriptor)
internal enum class Status {
    ACTIVE, SUCCESSFUL, FAILED, SUCCESSFUL_DIRTY, FAILED_DIRTY;

    fun isDirty() = this == SUCCESSFUL_DIRTY || this == FAILED_DIRTY
    fun clean() = when (this) {
        SUCCESSFUL_DIRTY -> SUCCESSFUL
        FAILED_DIRTY -> FAILED
        else -> this
    }
}

internal class MCASDescriptor(s: Status) {
    val status = nonVolatile(s)

    @Volatile
    var words = listOf<WordDescriptor>()
}

internal class DurableMCAS : MCAS {
    private val data: List<NonVolatileRef<WordDescriptor>>

    init {
        val mcas = MCASDescriptor(Status.SUCCESSFUL)
        data = List(N) { nonVolatile(WordDescriptor(0, 0, mcas)) }
        mcas.words = data.map { it.value }
    }

    private fun readInternal(self: MCASDescriptor?, index: Int): Pair<WordDescriptor, Int> {
        while (true) {
            val wd = data[index].value
            val parent = wd.parent
            if (parent !== self && parent.status.value == Status.ACTIVE) {
                MCAS(parent)
                continue
            } else if (parent.status.value.isDirty()) {
                parent.status.flush()
                parent.status.value = parent.status.value.clean()
                continue
            }
            return if (parent.status.value == Status.SUCCESSFUL) wd to wd.new else wd to wd.old
        }
    }

    private fun MCAS(self: MCASDescriptor): Boolean {
        var success = true
        loop@ for (index in self.words.indices) {
            val wd = self.words[index]
            retry@ while (true) {
                val (content, value) = readInternal(self, index)
                if (content === wd) break@retry
                if (value != wd.old) {
                    success = false
                    break@loop
                }
                if (self.status.value != Status.ACTIVE) break@loop
                if (data[index].compareAndSet(content, wd)) break@retry
            }
        }
        for (wd in data) {
            wd.flush()
        }
        self.status.compareAndSet(Status.ACTIVE, if (success) Status.SUCCESSFUL_DIRTY else Status.FAILED_DIRTY)
        self.status.flush()
        self.status.value = self.status.value.clean()
        return self.status.value == Status.SUCCESSFUL
    }

    override fun get(index: Int) = readInternal(null, index).second
    override fun compareAndSet(old: List<Int>, new: List<Int>): Boolean {
        val mcas = MCASDescriptor(Status.ACTIVE)
        mcas.words = old.indices.map { WordDescriptor(old[it], new[it], mcas) }
        return MCAS(mcas)
    }

    override fun recover() {
        for (d in data) {
            val parent = d.value.parent
            if (parent.status.value == Status.ACTIVE) {
                parent.status.setAndFlush(Status.FAILED)
            } else {
                parent.status.setAndFlush(parent.status.value.clean())
            }
        }
    }
}

@Param(name = "list", gen = ListGen::class)
internal class MCASNoRecoverFailingTest : AbstractNVMLincheckFailingTest(
    Recover.DURABLE,
    THREADS_NUMBER,
    SequentialMCAS::class,
    false,
    DeadlockWithDumpFailure::class
) {
    private val cas = DurableMCAS()

    @Operation
    fun get(@Param(gen = IntGen::class, conf = "0:${N - 1}") index: Int) = cas[index]

    @Operation
    fun compareAndSet(@Param(name = "list") old: List<Int>, @Param(name = "list") new: List<Int>) =
        cas.compareAndSet(old, new)

    // Without correct recovery the execution may lead to StackOverflowError, which can appear as OutOfMemoryError in MC mode.
    // This randomness causes non-determinism check fail.
    override val expectedExceptions = listOf(StackOverflowError::class, IllegalStateException::class)
}


@Param(name = "list", gen = ListGen::class)
internal abstract class MCASFailingTest(vararg expectedFailures: KClass<out LincheckFailure>) :
    AbstractNVMLincheckFailingTest(
        Recover.DURABLE,
        THREADS_NUMBER,
        SequentialMCAS::class,
        expectedFailures = expectedFailures
    ) {
    internal abstract val cas: MCAS

    @Operation
    fun get(@Param(gen = IntGen::class, conf = "0:${N - 1}") index: Int) = cas[index]

    @Operation
    fun compareAndSet(@Param(name = "list") old: List<Int>, @Param(name = "list") new: List<Int>) =
        cas.compareAndSet(old, new)

    @DurableRecoverAll
    fun recover() = cas.recover()
    override val expectedExceptions: List<KClass<out Throwable>> = listOf(StackOverflowError::class)
}

internal class MCASFailingTest1 : MCASFailingTest() {
    override val cas = DurableFailingMCAS1()
}

internal class MCASFailingTest2 : MCASFailingTest() {
    override val cas = DurableFailingMCAS2()
}

internal class MCASFailingTest3 : MCASFailingTest() {
    override val cas = DurableFailingMCAS3()
}

internal class MCASFailingTest4 : MCASFailingTest() {
    override val cas = DurableFailingMCAS4()
}

internal class MCASFailingTest5 : MCASFailingTest() {
    override val cas = DurableFailingMCAS5()
}

internal class MCASFailingTest6 : MCASFailingTest() {
    override val cas = DurableFailingMCAS6()
}

internal class MCASFailingTest7 : MCASFailingTest(DeadlockWithDumpFailure::class) {
    override val cas = DurableFailingMCAS7()

    // Without correct recovery the execution may lead to StackOverflowError, which can appear as OutOfMemoryError in MC mode.
    // This randomness causes non-determinism check fail.
    override val expectedExceptions = listOf(StackOverflowError::class, IllegalStateException::class)
}

internal class DurableFailingMCAS1 : MCAS {
    private val data: List<NonVolatileRef<WordDescriptor>>

    init {
        val mcas = MCASDescriptor(Status.SUCCESSFUL)
        data = List(N) { nonVolatile(WordDescriptor(0, 0, mcas)) }
        mcas.words = data.map { it.value }
    }

    private fun readInternal(self: MCASDescriptor?, index: Int): Pair<WordDescriptor, Int> {
        while (true) {
            val wd = data[index].value
            val parent = wd.parent
            // here should be
//            if (parent !== self && parent.status.value == Status.ACTIVE) {
//                MCAS(parent)
//                continue
//            } else
            if (parent.status.value.isDirty()) {
                parent.status.flush()
                parent.status.value = parent.status.value.clean()
                continue
            }
            return if (parent.status.value == Status.SUCCESSFUL) wd to wd.new else wd to wd.old
        }
    }

    private fun MCAS(self: MCASDescriptor): Boolean {
        var success = true
        loop@ for (index in self.words.indices) {
            val wd = self.words[index]
            retry@ while (true) {
                val (content, value) = readInternal(self, index)
                if (content === wd) break@retry
                if (value != wd.old) {
                    success = false
                    break@loop
                }
                if (self.status.value != Status.ACTIVE) break@loop
                if (data[index].compareAndSet(content, wd)) break@retry
            }
        }
        for (wd in data) {
            wd.flush()
        }
        self.status.compareAndSet(Status.ACTIVE, if (success) Status.SUCCESSFUL_DIRTY else Status.FAILED_DIRTY)
        self.status.flush()
        self.status.value = self.status.value.clean()
        return self.status.value == Status.SUCCESSFUL
    }

    override fun get(index: Int) = readInternal(null, index).second
    override fun compareAndSet(old: List<Int>, new: List<Int>): Boolean {
        val mcas = MCASDescriptor(Status.ACTIVE)
        mcas.words = old.indices.map { WordDescriptor(old[it], new[it], mcas) }
        return MCAS(mcas)
    }

    override fun recover() {
        for (d in data) {
            val parent = d.value.parent
            if (parent.status.value == Status.ACTIVE) {
                parent.status.setAndFlush(Status.FAILED)
            } else {
                parent.status.setAndFlush(parent.status.value.clean())
            }
        }
    }
}

internal class DurableFailingMCAS2 : MCAS {
    private val data: List<NonVolatileRef<WordDescriptor>>

    init {
        val mcas = MCASDescriptor(Status.SUCCESSFUL)
        data = List(N) { nonVolatile(WordDescriptor(0, 0, mcas)) }
        mcas.words = data.map { it.value }
    }

    private fun readInternal(self: MCASDescriptor?, index: Int): Pair<WordDescriptor, Int> {
        while (true) {
            val wd = data[index].value
            val parent = wd.parent
            if (parent !== self && parent.status.value == Status.ACTIVE) {
                MCAS(parent)
                continue
            } else if (parent.status.value.isDirty()) {
                // here should be parent.status.flush()
                parent.status.value = parent.status.value.clean()
                continue
            }
            return if (parent.status.value == Status.SUCCESSFUL) wd to wd.new else wd to wd.old
        }
    }

    private fun MCAS(self: MCASDescriptor): Boolean {
        var success = true
        loop@ for (index in self.words.indices) {
            val wd = self.words[index]
            retry@ while (true) {
                val (content, value) = readInternal(self, index)
                if (content === wd) break@retry
                if (value != wd.old) {
                    success = false
                    break@loop
                }
                if (self.status.value != Status.ACTIVE) break@loop
                if (data[index].compareAndSet(content, wd)) break@retry
            }
        }
        for (wd in data) {
            wd.flush()
        }
        self.status.compareAndSet(Status.ACTIVE, if (success) Status.SUCCESSFUL_DIRTY else Status.FAILED_DIRTY)
        self.status.flush()
        self.status.value = self.status.value.clean()
        return self.status.value == Status.SUCCESSFUL
    }

    override fun get(index: Int) = readInternal(null, index).second
    override fun compareAndSet(old: List<Int>, new: List<Int>): Boolean {
        val mcas = MCASDescriptor(Status.ACTIVE)
        mcas.words = old.indices.map { WordDescriptor(old[it], new[it], mcas) }
        return MCAS(mcas)
    }

    override fun recover() {
        for (d in data) {
            val parent = d.value.parent
            if (parent.status.value == Status.ACTIVE) {
                parent.status.setAndFlush(Status.FAILED)
            } else {
                parent.status.setAndFlush(parent.status.value.clean())
            }
        }
    }
}

internal class DurableFailingMCAS3 : MCAS {
    private val data: List<NonVolatileRef<WordDescriptor>>

    init {
        val mcas = MCASDescriptor(Status.SUCCESSFUL)
        data = List(N) { nonVolatile(WordDescriptor(0, 0, mcas)) }
        mcas.words = data.map { it.value }
    }

    private fun readInternal(self: MCASDescriptor?, index: Int): Pair<WordDescriptor, Int> {
        while (true) {
            val wd = data[index].value
            val parent = wd.parent
            if (parent !== self && parent.status.value == Status.ACTIVE) {
                MCAS(parent)
                continue
            } else if (parent.status.value.isDirty()) {
                parent.status.flush()
                parent.status.value = parent.status.value.clean()
                continue
            }
            // here should be return if (parent.status.value == Status.SUCCESSFUL) wd to wd.new else wd to wd.old
            return wd to wd.new
        }
    }

    private fun MCAS(self: MCASDescriptor): Boolean {
        var success = true
        loop@ for (index in self.words.indices) {
            val wd = self.words[index]
            retry@ while (true) {
                val (content, value) = readInternal(self, index)
                if (content === wd) break@retry
                if (value != wd.old) {
                    success = false
                    break@loop
                }
                if (self.status.value != Status.ACTIVE) break@loop
                if (data[index].compareAndSet(content, wd)) break@retry
            }
        }
        for (wd in data) {
            wd.flush()
        }
        self.status.compareAndSet(Status.ACTIVE, if (success) Status.SUCCESSFUL_DIRTY else Status.FAILED_DIRTY)
        self.status.flush()
        self.status.value = self.status.value.clean()
        return self.status.value == Status.SUCCESSFUL
    }

    override fun get(index: Int) = readInternal(null, index).second
    override fun compareAndSet(old: List<Int>, new: List<Int>): Boolean {
        val mcas = MCASDescriptor(Status.ACTIVE)
        mcas.words = old.indices.map { WordDescriptor(old[it], new[it], mcas) }
        return MCAS(mcas)
    }

    override fun recover() {
        for (d in data) {
            val parent = d.value.parent
            if (parent.status.value == Status.ACTIVE) {
                parent.status.setAndFlush(Status.FAILED)
            } else {
                parent.status.setAndFlush(parent.status.value.clean())
            }
        }
    }
}

internal class DurableFailingMCAS4 : MCAS {
    private val data: List<NonVolatileRef<WordDescriptor>>

    init {
        val mcas = MCASDescriptor(Status.SUCCESSFUL)
        data = List(N) { nonVolatile(WordDescriptor(0, 0, mcas)) }
        mcas.words = data.map { it.value }
    }

    private fun readInternal(self: MCASDescriptor?, index: Int): Pair<WordDescriptor, Int> {
        while (true) {
            val wd = data[index].value
            val parent = wd.parent
            if (parent !== self && parent.status.value == Status.ACTIVE) {
                MCAS(parent)
                continue
            } else if (parent.status.value.isDirty()) {
                // here should be parent.status.flush()
                parent.status.value = parent.status.value.clean()
                continue
            }
            return if (parent.status.value == Status.SUCCESSFUL) wd to wd.new else wd to wd.old
        }
    }

    private fun MCAS(self: MCASDescriptor): Boolean {
        var success = true
        loop@ for (index in self.words.indices) {
            val wd = self.words[index]
            retry@ while (true) {
                val (content, value) = readInternal(self, index)
                if (content === wd) break@retry
                if (value != wd.old) {
                    success = false
                    break@loop
                }
                if (self.status.value != Status.ACTIVE) break@loop
                if (data[index].compareAndSet(content, wd)) break@retry
            }
        }
        for (wd in data) {
            // here should be wd.flush()
        }
        self.status.compareAndSet(Status.ACTIVE, if (success) Status.SUCCESSFUL_DIRTY else Status.FAILED_DIRTY)
        self.status.flush()
        self.status.value = self.status.value.clean()
        return self.status.value == Status.SUCCESSFUL
    }

    override fun get(index: Int) = readInternal(null, index).second
    override fun compareAndSet(old: List<Int>, new: List<Int>): Boolean {
        val mcas = MCASDescriptor(Status.ACTIVE)
        mcas.words = old.indices.map { WordDescriptor(old[it], new[it], mcas) }
        return MCAS(mcas)
    }

    override fun recover() {
        for (d in data) {
            val parent = d.value.parent
            if (parent.status.value == Status.ACTIVE) {
                parent.status.setAndFlush(Status.FAILED)
            } else {
                parent.status.setAndFlush(parent.status.value.clean())
            }
        }
    }
}


internal class DurableFailingMCAS5 : MCAS {
    private val data: List<NonVolatileRef<WordDescriptor>>

    init {
        val mcas = MCASDescriptor(Status.SUCCESSFUL)
        data = List(N) { nonVolatile(WordDescriptor(0, 0, mcas)) }
        mcas.words = data.map { it.value }
    }

    private fun readInternal(self: MCASDescriptor?, index: Int): Pair<WordDescriptor, Int> {
        while (true) {
            val wd = data[index].value
            val parent = wd.parent
            if (parent !== self && parent.status.value == Status.ACTIVE) {
                MCAS(parent)
                continue
            } else if (parent.status.value.isDirty()) {
                parent.status.flush()
                parent.status.value = parent.status.value.clean()
                continue
            }
            return if (parent.status.value == Status.SUCCESSFUL) wd to wd.new else wd to wd.old
        }
    }

    private fun MCAS(self: MCASDescriptor): Boolean {
        var success = true
        loop@ for (index in self.words.indices) {
            val wd = self.words[index]
            retry@ while (true) {
                val (content, value) = readInternal(self, index)
                if (content === wd) break@retry
                if (value != wd.old) {
                    success = false
                    break@loop
                }
                if (self.status.value != Status.ACTIVE) break@loop
                if (data[index].compareAndSet(content, wd)) break@retry
            }
        }
        for (wd in data) {
            wd.flush()
        }
        self.status.compareAndSet(Status.ACTIVE, if (success) Status.SUCCESSFUL_DIRTY else Status.FAILED_DIRTY)
        // here should be self.status.flush()
        self.status.value = self.status.value.clean()
        return self.status.value == Status.SUCCESSFUL
    }

    override fun get(index: Int) = readInternal(null, index).second
    override fun compareAndSet(old: List<Int>, new: List<Int>): Boolean {
        val mcas = MCASDescriptor(Status.ACTIVE)
        mcas.words = old.indices.map { WordDescriptor(old[it], new[it], mcas) }
        return MCAS(mcas)
    }

    override fun recover() {
        for (d in data) {
            val parent = d.value.parent
            if (parent.status.value == Status.ACTIVE) {
                parent.status.setAndFlush(Status.FAILED)
            } else {
                parent.status.setAndFlush(parent.status.value.clean())
            }
        }
    }
}

internal class DurableFailingMCAS6 : MCAS {
    private val data: List<NonVolatileRef<WordDescriptor>>

    init {
        val mcas = MCASDescriptor(Status.SUCCESSFUL)
        data = List(N) { nonVolatile(WordDescriptor(0, 0, mcas)) }
        mcas.words = data.map { it.value }
    }

    private fun readInternal(self: MCASDescriptor?, index: Int): Pair<WordDescriptor, Int> {
        while (true) {
            val wd = data[index].value
            val parent = wd.parent
            if (parent !== self && parent.status.value == Status.ACTIVE) {
                MCAS(parent)
                continue
            } else if (parent.status.value.isDirty()) {
                // here should be parent.status.flush()
                parent.status.value = parent.status.value.clean()
                continue
            }
            return if (parent.status.value == Status.SUCCESSFUL) wd to wd.new else wd to wd.old
        }
    }

    private fun MCAS(self: MCASDescriptor): Boolean {
        var success = true
        loop@ for (index in self.words.indices) {
            val wd = self.words[index]
            retry@ while (true) {
                val (content, value) = readInternal(self, index)
                if (content === wd) break@retry
                if (value != wd.old) {
                    success = false
                    break@loop
                }
                if (self.status.value != Status.ACTIVE) break@loop
                if (data[index].compareAndSet(content, wd)) break@retry
            }
        }
        for (wd in data) {
            wd.flush()
        }
        self.status.compareAndSet(Status.ACTIVE, if (success) Status.SUCCESSFUL_DIRTY else Status.FAILED_DIRTY)
        self.status.flush()
        self.status.value = self.status.value.clean()
        return success // here should be self.status.value == Status.SUCCESSFUL
    }

    override fun get(index: Int) = readInternal(null, index).second
    override fun compareAndSet(old: List<Int>, new: List<Int>): Boolean {
        val mcas = MCASDescriptor(Status.ACTIVE)
        mcas.words = old.indices.map { WordDescriptor(old[it], new[it], mcas) }
        return MCAS(mcas)
    }

    override fun recover() {
        for (d in data) {
            val parent = d.value.parent
            if (parent.status.value == Status.ACTIVE) {
                parent.status.setAndFlush(Status.FAILED)
            } else {
                parent.status.setAndFlush(parent.status.value.clean())
            }
        }
    }
}


internal class DurableFailingMCAS7 : MCAS {
    private val data: List<NonVolatileRef<WordDescriptor>>

    init {
        val mcas = MCASDescriptor(Status.SUCCESSFUL)
        data = List(N) { nonVolatile(WordDescriptor(0, 0, mcas)) }
        mcas.words = data.map { it.value }
    }

    private fun readInternal(self: MCASDescriptor?, index: Int): Pair<WordDescriptor, Int> {
        while (true) {
            val wd = data[index].value
            val parent = wd.parent
            if (parent !== self && parent.status.value == Status.ACTIVE) {
                MCAS(parent)
                continue
            } else if (parent.status.value.isDirty()) {
                parent.status.flush()
                parent.status.value = parent.status.value.clean()
                continue
            }
            return if (parent.status.value == Status.SUCCESSFUL) wd to wd.new else wd to wd.old
        }
    }

    private fun MCAS(self: MCASDescriptor): Boolean {
        var success = true
        loop@ for (index in self.words.indices) {
            val wd = self.words[index]
            retry@ while (true) {
                val (content, value) = readInternal(self, index)
                if (content === wd) break@retry
                if (value != wd.old) {
                    success = false
                    break@loop
                }
                if (self.status.value != Status.ACTIVE) break@loop
                if (data[index].compareAndSet(content, wd)) break@retry
            }
        }
        for (wd in data) {
            wd.flush()
        }
        self.status.compareAndSet(Status.ACTIVE, if (success) Status.SUCCESSFUL_DIRTY else Status.FAILED_DIRTY)
        self.status.flush()
        self.status.value = self.status.value.clean()
        return self.status.value == Status.SUCCESSFUL
    }

    override fun get(index: Int) = readInternal(null, index).second
    override fun compareAndSet(old: List<Int>, new: List<Int>): Boolean {
        val mcas = MCASDescriptor(Status.ACTIVE)
        mcas.words = old.indices.map { WordDescriptor(old[it], new[it], mcas) }
        return MCAS(mcas)
    }

    override fun recover() {
        for (d in data) {
            val parent = d.value.parent
            if (parent.status.value == Status.ACTIVE) {
                // here should be parent.status.setAndFlush(Status.FAILED)
            } else {
                parent.status.setAndFlush(parent.status.value.clean())
            }
        }
    }
}
