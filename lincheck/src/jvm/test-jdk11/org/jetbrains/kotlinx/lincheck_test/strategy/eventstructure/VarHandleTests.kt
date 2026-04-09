/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import java.util.concurrent.atomic.*
import java.lang.invoke.MethodHandles
import org.jetbrains.lincheck.datastructures.scenario
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestName
import org.jetbrains.lincheck.util.UnsafeHolder

class VarHandleTests {

    @get:Rule
    val testName = TestName()

    class VolatileReferenceVariable {
        @Volatile
        private var variable: String? = null

        companion object {
            private val updater =
                AtomicReferenceFieldUpdater.newUpdater(VolatileReferenceVariable::class.java, String::class.java, "variable")

            private val handle = run {
                val lookup = MethodHandles.lookup()
                lookup.findVarHandle(VolatileReferenceVariable::class.java, "variable", String::class.java)
            }

            private val U = UnsafeHolder.UNSAFE

            @Suppress("DEPRECATION")
            private val offset = U.objectFieldOffset(VolatileReferenceVariable::class.java.getDeclaredField("variable"))

        }

        fun read(): String? {
            return variable
        }

        fun afuRead(): String? {
            return updater.get(this)
        }

        fun vhRead(): String? {
            return handle.get(this) as String?
        }

        fun unsafeRead(): String? {
            return U.getObject(this, offset) as String?
        }

        fun write(value: String?) {
            variable = value
        }

        fun afuWrite(value: String?) {
            updater.set(this, value)
        }

        fun vhWrite(value: String?) {
            handle.set(this, value)
        }

        fun unsafeWrite(value: String?) {
            U.putObject(this, offset, value)
        }

        fun vhCompareAndSet(expected: String?, desired: String?): Boolean {
            return handle.compareAndSet(this, expected, desired)
        }

    }

    @Test
    fun testVarHandleAccesses() {
        val read = VolatileReferenceVariable::vhRead
        val write = VolatileReferenceVariable::vhWrite
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, "a")
                }
                thread {
                    actor(read)
                }
                thread {
                    actor(write, "b")
                }
            }
        }
        val outcomes: Set<String?> = setOf(null, "a", "b")
        litmusTest(VolatileReferenceVariable::class.java, testScenario, outcomes) { results ->
            getValue(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testVarHandleCompareAndSet() {
        val read = VolatileReferenceVariable::vhRead
        val compareAndSet = VolatileReferenceVariable::vhCompareAndSet
        val testScenario = scenario {
            parallel {
                thread {
                    actor(compareAndSet, null, "a")
                }
                thread {
                    actor(compareAndSet, null, "a")
                }
            }
            post {
                actor(read)
            }
        }
        val outcomes: Set<Triple<Boolean, Boolean, String?>> = setOf(
            Triple(true, false, "a"),
            Triple(false, true, "a")
        )
        litmusTest(VolatileReferenceVariable::class.java, testScenario, outcomes) { results ->
            val r1 = getValue<Boolean>(results.parallelResults[0][0]!!)
            val r2 = getValue<Boolean>(results.parallelResults[1][0]!!)
            val r3 = getValue<String?>(results.postResults[0]!!)
            Triple(r1, r2, r3)
        }
    }

    private data class Quad<out A, out B, out C, out D>(
        val first: A, val second: B, val third: C, val forth: D
    )

    @Test
    fun testMixedAccesses() {
        val read = VolatileReferenceVariable::read
        val afuRead = VolatileReferenceVariable::afuRead
        val vhRead = VolatileReferenceVariable::vhRead
        val unsafeRead = VolatileReferenceVariable::unsafeRead
        val write = VolatileReferenceVariable::write
        val afuWrite = VolatileReferenceVariable::afuWrite
        val vhWrite = VolatileReferenceVariable::vhWrite
        val unsafeWrite = VolatileReferenceVariable::unsafeWrite
        // TODO: also add Unsafe accessors once they are supported
        val testScenario = scenario {
            parallel {
                thread {
                    actor(write, "a")
                }
                thread {
                    actor(afuWrite, "b")
                }
                thread {
                    actor(vhWrite, "c")
                }
                thread {
                    actor(unsafeWrite, "d")
                }
                thread {
                    actor(read)
                }
                thread {
                    actor(afuRead)
                }
                thread {
                    actor(vhRead)
                }
                thread {
                    actor(unsafeRead)
                }
            }
        }
        val values = setOf(null, "a", "b", "c", "d")
        val outcomes: Set<Quad<String?, String?, String?, String?>> =
            values.flatMap { a -> values.flatMap { b -> values.flatMap { c -> values.flatMap { d ->
                listOf(Quad(a, b, c, d))
            }}}}.toSet()
        litmusTest(VolatileReferenceVariable::class.java, testScenario, outcomes) { results ->
            val a = getValue<String?>(results.parallelResults[4][0]!!)
            val b = getValue<String?>(results.parallelResults[5][0]!!)
            val c = getValue<String?>(results.parallelResults[6][0]!!)
            val d = getValue<String?>(results.parallelResults[7][0]!!)
            Quad(a, b, c, d)
        }
    }

}
