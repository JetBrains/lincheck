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

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.util.InternalLincheckExceptionEmulator.throwException
import org.junit.Assert.assertEquals
import org.junit.Test

@Suppress("UNUSED")
class InternalLincheckBugTest {

    private var canEnterForbiddenSection = false

    @Operation
    fun operation1() {
        canEnterForbiddenSection = true
        canEnterForbiddenSection = false
    }

    @Operation
    fun operation2() {
        if (canEnterForbiddenSection) throwException()
    }

    @Test
    fun `should add stackTrace to output`() {
        // We will remove line with TestThreadExecution call as it's number may vary
        val testRunnableLineRegex = "(\\W*)at org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution(\\d+)\\.run\\(Unknown Source\\)".toRegex()
        val expectedOutput = """
            It seems you've found a bug in Lincheck.
            Please report it to JetBrains.

            Exception stacktrace: 
            java.lang.IllegalStateException: Internal bug
            	at org.jetbrains.kotlinx.lincheck.util.InternalLincheckExceptionEmulator.throwException(InternalLincheckExceptionEmulator.kt:29)
            	at org.jetbrains.kotlinx.lincheck_test.representation.InternalLincheckBugTest.operation2(InternalLincheckBugTest.kt:43)
            	at org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution207.run(Unknown Source)
            	at org.jetbrains.kotlinx.lincheck.runner.FixedActiveThreadsExecutor.testThreadRunnable${DOLLAR_CHAR}lambda-4(FixedActiveThreadsExecutor.kt:161)
            	at java.base/java.lang.Thread.run(Thread.java:829)
            
        """.trimIndent().replace(testRunnableLineRegex, "")

        val failure = ModelCheckingOptions()
            .actorsPerThread(2)
            .checkImpl(this::class.java) ?: error("Test should fail")

        val actualOutput = failure.toString().trimIndent().replace(testRunnableLineRegex, "")

        assertEquals(expectedOutput, actualOutput)
    }
}

private const val DOLLAR_CHAR = '$'