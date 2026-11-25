/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.strategy.eventstructure

import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Checks that an out-of-bounds array access of the tested code throws the exception of the access itself:
 * the tested code can catch it and continue, and otherwise it is the result of the tested code.
 */
class ArrayIndexOutOfBoundsTest {

    private val memoryModels = listOf(MemoryModel.SequentialConsistency, MemoryModel.RelaxedJava)

    @Test
    fun testCaughtIntArrayRead() = checkCaughtException {
        val array = IntArray(2)
        val writer = thread { array[1] = 1 }
        val exception = try {
            array[0] = array[array.size]
            null
        } catch (e: ArrayIndexOutOfBoundsException) {
            e
        }
        val value = array[1]
        writer.join()
        exception to value
    }

    @Test
    fun testCaughtIntArrayWrite() = checkCaughtException {
        val array = IntArray(2)
        val writer = thread { array[1] = 1 }
        val exception = try {
            array[-1] = 1
            null
        } catch (e: ArrayIndexOutOfBoundsException) {
            e
        }
        val value = array[1]
        writer.join()
        exception to value
    }

    @Test
    fun testCaughtObjectArrayRead() = checkCaughtException {
        val array = arrayOfNulls<Any>(2)
        val writer = thread { array[1] = array }
        val exception = try {
            array[0] = array[-1]
            null
        } catch (e: ArrayIndexOutOfBoundsException) {
            e
        }
        val value = if (array[1] == null) 0 else 1
        writer.join()
        exception to value
    }

    @Test
    fun testCaughtObjectArrayWrite() = checkCaughtException {
        val array = arrayOfNulls<Any>(2)
        val writer = thread { array[1] = array }
        val exception = try {
            array[array.size] = array
            null
        } catch (e: ArrayIndexOutOfBoundsException) {
            e
        }
        val value = if (array[1] == null) 0 else 1
        writer.join()
        exception to value
    }

    @Test
    fun testUncaughtRead() = checkUncaughtException {
        val array = IntArray(2)
        array[array.size]
    }

    @Test
    fun testUncaughtWrite() = checkUncaughtException {
        val array = arrayOfNulls<Any>(2)
        array[-1] = array
        0
    }

    /**
     * Runs the [block], which catches the exception of an out-of-bounds access
     * and then reads an array element that another thread writes.
     */
    private fun checkCaughtException(block: () -> Pair<Throwable?, Int>) {
        val verifier = OutcomeVerifier<Pair<Throwable?, Int>> { outcomes ->
            outcomes.forEach { (exception, _) -> assertThrownByArrayAccess(exception) }
            assertEquals(setOf(0, 1), outcomes.map { (_, value) -> value }.toSet())
        }
        for (memoryModel in memoryModels) {
            litmusTest(verifier, memoryModel, requireExhaustion = true, block = block)
        }
    }

    private fun checkUncaughtException(block: () -> Int) {
        for (memoryModel in memoryModels) {
            val exception = assertThrows(ArrayIndexOutOfBoundsException::class.java) {
                litmusTest(assertSame(emptySet<Int>()), memoryModel, block = block)
            }
            assertThrownByArrayAccess(exception)
        }
    }

    private fun assertThrownByArrayAccess(exception: Throwable?) {
        assertTrue("Expected an ArrayIndexOutOfBoundsException, got $exception", exception is ArrayIndexOutOfBoundsException)
        val thrownAt = exception!!.stackTrace.firstOrNull()
        assertNotNull("The exception has no stack trace", thrownAt)
        assertTrue(
            "The exception was thrown at $thrownAt, not at the array access of the tested code",
            thrownAt!!.className.startsWith(javaClass.name)
        )
    }
}
