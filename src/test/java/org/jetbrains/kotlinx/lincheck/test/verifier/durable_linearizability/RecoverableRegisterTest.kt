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
package org.jetbrains.kotlinx.lincheck.test.verifier.durable_linearizability

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.concurrent.atomic.*

private val nThreads = 2

@Param(name = "key", gen = IntGen::class, conf = "0:1")
class RecoverableRegisterTest : VerifierState() {
    private val register = AtomicReference<Record>(Record(0, -1, -1))
    private val r = Array(nThreads) { Array(nThreads) { AtomicReference<Pair<Int, Int>>(Pair(-1, -1)) } }

    @Operation
    fun cas(@Param(name = "key") key: Int, @Param(name = "threadId") threadId: Int, @Param(name = "operationId") operationId: Int): Boolean {
        // recover
        val oldValue = key
        val newValue = 1 - key
        if (register.get() == Record(newValue, threadId, operationId))
            return true
        val p = Pair(newValue, operationId)
        for (a in r[threadId])
            if (a.get() == p)
                return true
        // cas logic
        val record = register.get()
        if (record.value != oldValue)
            return false
        if (record.threadId != -1)
            r[record.threadId][threadId].set(Pair(record.value, record.operationId))
        return register.compareAndSet(record, Record(newValue, threadId, operationId))
    }

    @Operation
    fun get() = register.get().value

    @Test
    fun test() {
        val options = ModelCheckingOptions().recoverable(mode = ManagedCTestConfiguration.RecoverableMode.DETECTABLE_EXECUTION).iterations(50)
        LinChecker.check(this::class.java, options)
    }

    override fun extractState(): Any = Pair(register.get(), r.map { it.map { it.get() }})

    private data class Record(val value: Int, val threadId: Int, val operationId: Int)
}