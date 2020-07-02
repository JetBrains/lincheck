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
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.DeadlockWithDumpFailure
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import java.util.concurrent.atomic.AtomicBoolean

class LiveLockTest : AbstractLincheckTest(DeadlockWithDumpFailure::class) {
    private var counter = 0
    private var lock1 = AtomicBoolean(false)
    private var lock2 = AtomicBoolean(false)

    @Operation
    fun inc12(): Int {
        return lock1.synchronized {
            lock2.synchronized {
                counter++
            }
        }
    }

    @Operation
    fun inc21(): Int {
        return lock2.synchronized {
            lock1.synchronized {
                counter++
            }
        }
    }

    override fun extractState(): Any = counter


    override fun <O : Options<O, *>> O.customize() {
        minimizeFailedScenario(false)
    }

    private fun AtomicBoolean.synchronized(block: () -> Int): Int {
        while (!this.compareAndSet(false, true));
        val result = block()
        this.set(false)
        return result
    }
}
