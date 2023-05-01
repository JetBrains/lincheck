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

package org.jetbrains.kotlinx.lincheck.test.dsl

import org.jetbrains.kotlinx.lincheck.dsl.ScenarioBuilder
import org.jetbrains.kotlinx.lincheck.dsl.ScenarioBuilder.Companion.actor
import org.jetbrains.kotlinx.lincheck.dsl.ScenarioBuilder.Companion.thread
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomScenarioJavaDSLTest {

    @Test
    fun `should throw exception if method arguments count doesn't match supplied arguments count`() {
        val exception = Assert.assertThrows(IllegalArgumentException::class.java) {
            ScenarioBuilder(this::class.java)
                .parallel(
                    thread(
                        actor("incOperation")
                    )
                )
                .build()
        }
        assertEquals("Method with name incOperation and parameterCount 0 not found", exception.message)
    }


    @Test
    fun `should throw exception if method with supplied name not found`() {
        val exception = Assert.assertThrows(IllegalArgumentException::class.java) {
            ScenarioBuilder(this::class.java)
                .parallel(
                    thread(
                        actor("unknownOperation")
                    )
                )
                .build()
        }
        assertEquals("Method with name unknownOperation and parameterCount 0 not found", exception.message)
    }

    @Test
    fun `should throw exception on initial part redeclaration`() {
        val exception = Assert.assertThrows(IllegalStateException::class.java) {
            ScenarioBuilder(this::class.java)
                .initial(actor("incOperation", 1))
                .initial(actor("incOperation", 2))
                .build()
        }
        assertEquals("Redeclaration of the initial part is prohibited.", exception.message)
    }

    @Test
    fun `should throw exception on parallel part redeclaration`() {
        val exception = Assert.assertThrows(IllegalStateException::class.java) {
            ScenarioBuilder(this::class.java)
                .parallel(thread(actor("incOperation", 1)))
                .parallel(thread(actor("incOperation", 1)))
                .build()
        }
        assertEquals("Redeclaration of the parallel part is prohibited.", exception.message)
    }

    @Test
    fun `should throw exception on post part redeclaration`() {
        val exception = Assert.assertThrows(IllegalStateException::class.java) {
            ScenarioBuilder(this::class.java)
                .post(actor("incOperation", 1))
                .post(actor("incOperation", 2))
                .build()
        }
        assertEquals("Redeclaration of the post part is prohibited.", exception.message)
    }

    @Suppress("UNUSED")
    fun incOperation(value: Int) = Unit

    @Suppress("UNUSED")
    fun getOperation(value: Int) = Unit

}