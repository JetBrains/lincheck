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

package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.junit.*
import java.util.concurrent.atomic.*

class Striped64SupportTest {
    val counter = LongAdder() // LongAdder uses Striped64.getProbe() under the hood

    @Operation
    fun inc() = counter.increment()

    @Operation
    fun dec() = counter.decrement()

    @Operation
    fun sum() = counter.sum()

    @Test
    fun test() {
        val options = LincheckOptions {
            this as LincheckOptionsImpl
            mode = LincheckMode.ModelChecking
            testingTimeInSeconds = 10
            minimizeFailedScenario = false
        }
        val failure = options.checkImpl(this::class.java)
        assert(failure is IncorrectResultsFailure) {
            "This test should fail with IncorrectResultsFailure, but another error has been detected:\n$failure"
        }
    }
}