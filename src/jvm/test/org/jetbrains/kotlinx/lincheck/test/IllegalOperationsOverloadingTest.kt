/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks that if test class contains many operations with same name and parameters count than execution will be thrown
 */
class IllegalOperationsOverloadingTest {

    @Test
    fun `should throw exception`() {
        val exception = Assert.assertThrows(IllegalArgumentException::class.java) {
            ModelCheckingOptions().check(this::class.java)
        }

        val expectedMessage = "You can't have two methods with the same parameters count and the same name, " +
                "but the following methods have: operationA(java.lang.Integer) operationA(java.lang.Double). " +
                "Please rename it or use a wrapper class."

        assertEquals(expectedMessage, exception.message)
    }

    @Operation
    @Suppress("UNUSED")
    fun operationA(intParam: Int?) = Unit

    @Operation
    @Suppress("UNUSED")
    fun operationA(doubleParam: Double?) = Unit
}