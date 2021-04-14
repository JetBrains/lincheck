import org.jetbrains.kotlinx.lincheck.*
import kotlin.test.*

/*
* #%L
* Lincheck
* %%
* Copyright (C) 2015 - 2018 Devexperts, LLC
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

class ExceptionAsResultTest {
    fun npeIsOk() {
        (null as String?)!![0]
    }

    fun subclassExceptionIsOk() {
        if ((0..1).random() == 1) throw IndexOutOfBoundsException(MESSAGE) else throw IllegalStateException(MESSAGE)
    }

    @Test
    fun test() {
        try {
            LincheckStressConfiguration<ExceptionAsResultTest>().apply {
                iterations(1)
                requireStateEquivalenceImplCheck(false)

                initialState { ExceptionAsResultTest() }

                operation(ExceptionAsResultTest::npeIsOk, "npeIsOk", listOf(NullPointerException::class))
                operation(ExceptionAsResultTest::subclassExceptionIsOk, "subclassExceptionIsOk", listOf(Throwable::class))
            }.runTest()
            fail("Should fail with AssertionError")
        } catch (e: AssertionError) {
            val m = e.message
            assertTrue(m!!.contains("IllegalStateException") || m.contains("IndexOutOfBoundsException"))
            assertFalse(m.contains(MESSAGE))
        }
    }

    companion object {
        private const val MESSAGE = "iujdhfgilurtybfu"
    }
}