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
package org.jetbrains.kotlinx.lincheck.test.runner

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest

class DeadlockOnSynchronizedTest : AbstractLincheckTest(DeadlockedWithDumpFailedIteration::class) {
    private var counter = 0
    private var lock1 = Any()
    private var lock2 = Any()

    @Operation
    fun inc12(): Int {
        synchronized(lock1) {
            synchronized(lock2) {
                return counter++
            }
        }
    }

    @Operation
    fun inc21(): Int {
        synchronized(lock2) {
            synchronized(lock1) {
                return counter++
            }
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        minimizeFailedScenario(false)
    }

    override fun extractState(): Any = counter
}
