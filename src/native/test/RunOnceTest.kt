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

import org.jetbrains.kotlinx.lincheck.LincheckStressConfiguration
import kotlinx.atomicfu.locks.*
import kotlin.test.Test

class RunOnceTest {
    private val state: A = A()

    fun a() {
        state.a()
    }

    fun b() {
        state.b()
    }

    @Test
    fun test() {
        LincheckStressConfiguration<RunOnceTest>().apply {
            threads(3)
            iterations(10)
            invocationsPerIteration(10)
            requireStateEquivalenceImplCheck(false)

            initialState { RunOnceTest() }
            operation(RunOnceTest::a, useOnce = true)
            operation(RunOnceTest::b, useOnce = true)
        }.runTest()
    }

    internal inner class A : SynchronizedObject() {
        private var a = false
        private var b = false

        fun a() = synchronized(this) {
            if (a) throw AssertionError()
            a = true
        }

        fun b() = synchronized(this) {
            if (b) throw AssertionError()
            b = true
        }
    }
}