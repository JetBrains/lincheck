/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.junit.Test
import kotlin.collections.map
import kotlin.concurrent.thread
import java.lang.reflect.Array as ArrayReflection

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.LockSupport.*
import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.util.CancelledResult
import org.jetbrains.kotlinx.lincheck.util.SuspendedResult
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.scenario
import org.junit.rules.TestName
import kotlin.reflect.jvm.javaMethod
import org.jetbrains.lincheck.util.UnsafeHolder
import kotlin.concurrent.thread

class ArrayCopyTest {

    @Test
    fun testArrayCopy() {
        val outcomes = setOf(
            listOf(1,2,3,4),
            listOf(1,2,3,0),
            listOf(1,2,0,0),
            listOf(1,0,0,0),
            listOf(0,0,0,0),
        )
        litmusTest(assertSame(outcomes)) {
            val N = 4
            val a = IntArray(N)
            val b = IntArray(N)
            val result = IntArray(N)

            for (i in 0 until N) {
                a[i] = i + 1
            }

            val t1 = thread {
                System.arraycopy(a, 0, b, 0, N)
            }

            val t2 = thread {
                for (i in N-1 downTo  0) {
                    result[i] = b[i]
                }
            }

            t1.join()
            t2.join()

            result.toList()
        }
    }

    @Test
    fun testArrayCopyStrings() {
        val outcomes = setOf(
            listOf("1","2","3","4"),
            listOf("1","2","3","0"),
            listOf("1","2","0","0"),
            listOf("1","0","0","0"),
            listOf("0","0","0","0"),
        )
        litmusTest(assertSame(outcomes)) {
            val N = 4
            val a = Array<String>(N, { "0" })
            val b = Array<String>(N, { "0" })
            val result = Array<String>(N, { "0" })

            for (i in 0 until N) {
                a[i] = "${i + 1}"
            }

            val t1 = thread {
                System.arraycopy(a, 0, b, 0, N)
            }

            val t2 = thread {
                for (i in N-1 downTo  0) {
                    result[i] = b[i]
                }
            }

            t1.join()
            t2.join()

            result.toList()
        }
    }


    @Test
    fun testArrayCopyBoxes() {
        class Box(val x: Int) {
            override fun toString(): String {
                return "$x"
            }
            override fun hashCode(): Int {
                return x
            }
            override fun equals(other: Any?): Boolean {
                return other is Box && other.x == x
            }
        }

        val outcomes = setOf(
            listOf(Box(1),Box(2),Box(3),Box(4)),
            listOf(Box(1),Box(2),Box(3),Box(0)),
            listOf(Box(1),Box(2),Box(0),Box(0)),
            listOf(Box(1),Box(0),Box(0),Box(0)),
            listOf(Box(0),Box(0),Box(0),Box(0)),
        )

        litmusTest(assertSame(outcomes)) {
            val N = 4
            val a = Array<Box>(N, { Box(0) })
            val b = Array<Box>(N, { Box(0) })
            val result = Array<Box>(N, { Box(0) })

            for (i in 0 until N) {
                a[i] = Box(i + 1)
            }

            val t1 = thread {
                System.arraycopy(a, 0, b, 0, N)
            }

            val t2 = thread {
                for (i in N-1 downTo  0) {
                    result[i] = b[i]
                }
            }

            t1.join()
            t2.join()

            result.toList()
        }
    }


    @Test
    fun testArrayNewInstancePrimitives() {
        val outcomes = setOf(
            listOf(1,2,3,4),
            listOf(1,2,3,0),
            listOf(1,2,0,0),
            listOf(1,0,0,0),
            listOf(0,0,0,0),
        )
        litmusTest(assertSame(outcomes)) {
            val N = 4
            val arr = ArrayReflection.newInstance(Int::class.javaPrimitiveType, N) as IntArray
            val res = IntArray(N)


            val t1 = thread {
                for(i in 0 until N) {
                    arr[i] = i + 1
                }
            }

            val t2 = thread {
                for (i in N-1 downTo  0) {
                    res[i] = arr[i]
                }
            }

            t1.join()
            t2.join()

            res.toList()
        }
    }

    @Test
    fun testArrayNewInstanceStrings() {
        val outcomes = setOf(
            listOf("1" , "2" , "3" , "4"),
            listOf("1" , "2" , "3" , null),
            listOf("1" , "2" , null, null),
            listOf("1" , null, null, null),
            listOf(null, null, null, null),
        )
        litmusTest(assertSame(outcomes)) {
            val N = 4

            @Suppress("UNCHECKED_CAST")
            val arr = ArrayReflection.newInstance(String::class.java, N) as Array<String?>
            val res = Array<String?>(N, { "0" })


            val t1 = thread {
                for(i in 0 until N) {
                    arr[i] = "${i + 1}"
                }
            }

            val t2 = thread {
                for (i in N-1 downTo  0) {
                    res[i] = arr[i]
                }
            }

            t1.join()
            t2.join()

            res.toList()
        }
    }

    @Test
    fun testArrayNewInstanceBoxes() {

        class Box(val x: Int) {
            override fun toString(): String {
                return "$x"
            }
            override fun hashCode(): Int {
                return x
            }
            override fun equals(other: Any?): Boolean {
                return other is Box && other.x == x
            }
        }

        val outcomes = setOf(
            listOf(Box(1) , Box(2) , Box(3) , Box(4)),
            listOf(Box(1) , Box(2) , Box(3) , null),
            listOf(Box(1) , Box(2) , null, null),
            listOf(Box(1) , null, null, null),
            listOf(null, null, null, null),
        )
        litmusTest(assertSame(outcomes)) {
            val N = 4

            @Suppress("UNCHECKED_CAST")
            val arr = ArrayReflection.newInstance(Box::class.java, N) as Array<Box?>
            val res = Array<Box?>(N, { Box(0) })


            val t1 = thread {
                for(i in 0 until N) {
                    arr[i] = Box(i+1)
                }
            }

            val t2 = thread {
                for (i in N-1 downTo  0) {
                    res[i] = arr[i]
                }
            }

            t1.join()
            t2.join()

            res.toList()
        }
    }

    @Test
    fun testArrayNewInstanceArrays() {

        class Box(val x: Int) {
            override fun toString(): String {
                return "$x"
            }
            override fun hashCode(): Int {
                return x
            }
            override fun equals(other: Any?): Boolean {
                return other is Box && other.x == x
            }
        }

        val outcomes = setOf<List<Box?>>(
            listOf(Box(1) , Box(2) , Box(3) , Box(4)),
            listOf(Box(1) , Box(2) , Box(3) , null),
            listOf(Box(1) , Box(2) , null , null),
            listOf(Box(1) , null , null, null ),
            listOf(null, null, null, null),
        )

        litmusTest(assertSame(outcomes)) {
            val N = 4

            @Suppress("UNCHECKED_CAST")
            val arr = ArrayReflection.newInstance(Array<Box?>::class.java, N) as Array<Array<Box?>?>
            val res = Array<Array<Box?>?>(N, { arrayOf(Box(0)) })

            val t1 = thread {
                for(i in 0 until N) {
                    arr[i] = arrayOf(Box(i+1))
                }
            }

            val t2 = thread {
                for (i in N-1 downTo  0) {
                    res[i] = arr[i]
                }
            }

            t1.join()
            t2.join()

            res.map { if (it == null) null else it[0] }.toList()
        }
    }
}