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
package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*
import java.lang.AssertionError
import kotlin.random.*

@StressCTest(iterations = 1, minimizeFailedScenario = false, requireStateEquivalenceImplCheck = false)
class OperationsInAbstractClassTest : AbstractTestClass() {
    @Operation
    fun goodOperation() = 10

    @Test(expected = AssertionError::class)
    fun test(): Unit = LinChecker.check(this::class.java)
}

open class AbstractTestClass {
    @Operation
    fun badOperation() = Random.nextInt(10)
}
