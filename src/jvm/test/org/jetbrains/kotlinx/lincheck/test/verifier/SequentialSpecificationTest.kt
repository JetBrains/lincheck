/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.test.verifier

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.test.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.util.concurrent.atomic.*

class SequentialSpecificationTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private val c = AtomicInteger()

    @Operation
    fun set(value: Int) = c.set(value)

    @Operation
    fun get() = c.get() + 1

    override fun LincheckOptionsImpl.customize() {
        sequentialImplementation = CorrectCounter::class.java
    }
}

class CorrectCounter: VerifierState() {
    private var c = 0
    fun set(value: Int) { c = value }
    fun get(): Int = c
    override fun extractState() = c
}
