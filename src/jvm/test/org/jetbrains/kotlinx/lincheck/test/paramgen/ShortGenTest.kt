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

package org.jetbrains.kotlinx.lincheck.test.paramgen

import org.jetbrains.kotlinx.lincheck.paramgen.ShortGen
import org.junit.Test
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import java.lang.IllegalArgumentException

class ShortGenTest {

    @Test
    fun testShortGenWithEmptyConfiguration() {
        val result = ShortGen("").generate()

        assertNotNull(result)
    }

    @Test
    fun testShortGenWithConfiguration() {
        val result = ShortGen("1:1").generate()

        assertEquals(1.toShort(), result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testShortGenWithoutColonInTheConfiguration() {
        ShortGen("1").generate()
    }
}
