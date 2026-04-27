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

import org.jetbrains.kotlinx.lincheck.execution.*
import java.util.concurrent.atomic.*
import org.jetbrains.lincheck.datastructures.scenario
import org.junit.Test
import org.junit.Ignore
import java.lang.invoke.VarHandle

class JamMemoryModelTests {

    @Test
    fun testSBOpaque() {
        class TestSB {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                x.setOpaque(1)
                return y.getOpaque()
            }
            fun thread1(): Int {
                y.setOpaque(1)
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestSB::thread0) }
                thread { actor(TestSB::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((0 to 0), (0 to 1), (1 to 1), (1 to 0))
        litmusTest(TestSB::class.java, testScenario, assertSame(expectedOutcomes)) { results ->
            val r0 = getValue<Int>(results.parallelResults[0][0]!!)
            val r1 = getValue<Int>(results.parallelResults[1][0]!!)
            r0 to r1
        }
    }

    @Test
    fun test4SB() {
        class Test4SB {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            val a = AtomicInteger(0)
            fun thread0(): Int {
                x.setOpaque(1)
                return y.getOpaque()
            }
            fun thread1(): Int {
                y.setOpaque(1)
                return z.getOpaque()
            }
            fun thread2(): Int {
                z.setOpaque(1)
                return a.getOpaque()
            }
            fun thread3(): Int {
                a.setOpaque(1)
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(Test4SB::thread0) }
                thread { actor(Test4SB::thread1) }
                thread { actor(Test4SB::thread2) }
                thread { actor(Test4SB::thread3) }
            }
        }
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(0,0,0,0))
        litmusTest(Test4SB::class.java, testScenario, assertSometimes(forbiddenOutcomes)) { results ->
            listOf(
                getValue<Int>(results.parallelResults[0][0]!!),
                getValue<Int>(results.parallelResults[1][0]!!),
                getValue<Int>(results.parallelResults[2][0]!!),
                getValue<Int>(results.parallelResults[3][0]!!)
            )
        }
    }


    @Test
    fun test6SB() {
        class Test6SB {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            val a = AtomicInteger(0)
            val b = AtomicInteger(0)
            val c = AtomicInteger(0)
            fun thread0(): Int {
                x.setOpaque(1)
                return y.getOpaque()
            }
            fun thread1(): Int {
                y.setOpaque(1)
                return z.getOpaque()
            }
            fun thread2(): Int {
                z.setOpaque(1)
                return a.getOpaque()
            }
            fun thread3(): Int {
                a.setOpaque(1)
                return b.getOpaque()
            }
            fun thread4(): Int {
                b.setOpaque(1)
                return c.getOpaque()
            }
            fun thread5(): Int {
                c.setOpaque(1)
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(Test6SB::thread0) }
                thread { actor(Test6SB::thread1) }
                thread { actor(Test6SB::thread2) }
                thread { actor(Test6SB::thread3) }
                thread { actor(Test6SB::thread4) }
                thread { actor(Test6SB::thread5) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(0,0,0,0,0,0))
        litmusTest(Test6SB::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            listOf(
                getValue<Int>(results.parallelResults[0][0]!!),
                getValue<Int>(results.parallelResults[1][0]!!),
                getValue<Int>(results.parallelResults[2][0]!!),
                getValue<Int>(results.parallelResults[3][0]!!),
                getValue<Int>(results.parallelResults[4][0]!!),
                getValue<Int>(results.parallelResults[5][0]!!)
            )
        }
    }

    @Test
    fun testArfna() {
        class TestArfna {
            val a = AtomicInteger(0)
            val b = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val t = a.getPlain()
                    b.setPlain(1)
                    if (t != 0) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    if (b.getPlain() != 0) {
                        a.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestArfna::thread0) }
                thread { actor(TestArfna::thread1) }
            }
        }
        // x=1 /\ y=1, should never happen. TODO: support getting final values?
        val forbiddentOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestArfna::class.java, testScenario, assertNever(forbiddentOutcomes)) { results ->
            val r0 = getValue<Int>(results.parallelResults[0][0]!!)
            val r1 = getValue<Int>(results.parallelResults[1][0]!!)
            r0 to r1
        }
    }

    @Test
    fun testArfnaTransformed() {
        class TestArfnaTransformed {
            val a = AtomicInteger(0)
            val b = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    b.setPlain(1)
                    val t = a.getPlain()
                    if (t != 0) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    if (b.getPlain() != 0) {
                        a.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestArfnaTransformed::thread0) }
                thread { actor(TestArfnaTransformed::thread1) }
            }
        }
        // x=1 /\ y=1, should never happen. TODO: support getting final values?
        val forbiddentOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestArfnaTransformed::class.java, testScenario, assertNever(forbiddentOutcomes)) { results ->
            val r0 = getValue<Int>(results.parallelResults[0][0]!!)
            val r1 = getValue<Int>(results.parallelResults[1][0]!!)
            r0 to r1
        }
    }

    @Test
    fun testB() {
        //NOTE: This is just load buffering, I am not sure why the name is like that.
        class TestB {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                y.setOpaque(1)
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                x.setOpaque(1)
                return r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestB::thread0) }
                thread { actor(TestB::thread1) }
            }
        }
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestB::class.java, testScenario, assertNever(forbiddenOutcomes)) { results ->
            val r0 = getValue<Int>(results.parallelResults[0][0]!!)
            val r1 = getValue<Int>(results.parallelResults[1][0]!!)
            r0 to r1
        }
    }

    @Test
    fun testBReorder() {
        class TestBReorder {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                y.setOpaque(1)
                val r0 = x.getOpaque()
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                x.setOpaque(1)
                return r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestBReorder::thread0) }
                thread { actor(TestBReorder::thread1) }
            }
        }
        val allowedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestBReorder::class.java, testScenario, assertSometimes(allowedOutcomes)) { results ->
            val r0 = getValue<Int>(results.parallelResults[0][0]!!)
            val r1 = getValue<Int>(results.parallelResults[1][0]!!)
            r0 to r1
        }
    }

    // TODO: fix model checker
    @Test
    fun testC() {
        class TestC {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val t = p.getPlain()
                    q.setPlain(1)
                    if (t != 0) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestC::thread0) }
                thread { actor(TestC::thread1) }
            }
            post { actor(TestC::post) }
        }
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestC::class.java, testScenario, assertNever(forbiddenOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    // TODO: fix model checker
    @Test
    fun testCReorder() {
        class TestCReorder {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    q.setPlain(1)
                    val t = p.getPlain()
                    if (t != 0) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCReorder::thread0) }
                thread { actor(TestCReorder::thread1) }
            }
            post { actor(TestCReorder::post) }
        }
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCReorder::class.java, testScenario, assertNever(forbiddenOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testCoRWR() {
        class TestCoRWR {
            val x = AtomicInteger(0)
            fun thread0(): Pair<Int, Int> {
                val eax = x.getOpaque()
                x.setOpaque(1)
                val ebx = x.getOpaque()
                return eax to ebx
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCoRWR::thread0) }
            }
        }
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(TestCoRWR::class.java, testScenario, assertNever(forbiddenOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.parallelResults[0][0]!!)
        }
    }


    @Test
    fun testCyc() {
        class TestCyc {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    y.setOpaque(1)
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    x.setOpaque(1)
                }
                return r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCyc::thread0) }
                thread { actor(TestCyc::thread1) }
            }
        }
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCyc::class.java, testScenario, assertNever(forbiddenOutcomes)) { results ->
            val r0 = getValue<Int>(results.parallelResults[0][0]!!)
            val r1 = getValue<Int>(results.parallelResults[1][0]!!)
            r0 to r1
        }
    }

    // TODO: accroding to the JAM19 paper, this behaviour should sometimes happen under the jvm, but it seem to be porf acyclic, so...
    @Ignore
    @Test
    fun testCycNa() {
        class TestCycNa {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getPlain()
                if (r0 != 0) {
                    y.setPlain(1)
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getPlain()
                if (r1 != 0) {
                    x.setPlain(1)
                }
                return r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCycNa::thread0) }
                thread { actor(TestCycNa::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCycNa::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val r0 = getValue<Int>(results.parallelResults[0][0]!!)
            val r1 = getValue<Int>(results.parallelResults[1][0]!!)
            r0 to r1
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testFig1() {
        class TestFig1 {
            val a = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Pair<Int, Int> {
                a.setPlain(1)
                val r0 = x.getOpaque()
                val r1 = a.getPlain()
                y.setOpaque(1)
                return r0 to r1
            }
            fun thread1(): Int {
                val r2 = y.getOpaque()
                x.setOpaque(1)
                return r2
            }
            fun post(): Triple<Int, Int, Int> {
                return Triple(a.get(), x.get(), y.get())
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestFig1::thread0) }
                thread { actor(TestFig1::thread1) }
            }
            post { actor(TestFig1::post) }
        }
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1,1,1))
        litmusTest(TestFig1::class.java, testScenario, assertSame(expectedOutcomes, UNKNOWN)) { results ->
            getValue<Triple<Int, Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testIriwInternal() {
        class TestIriwInternal {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Pair<Int, Int> {
                x.setOpaque(1)
                val eax = x.getOpaque()
                val ebx = y.getOpaque()
                return eax to ebx
            }
            fun thread1(): Pair<Int, Int> {
                y.setOpaque(1)
                val eax = y.getOpaque()
                val ebx = x.getOpaque()
                return eax to ebx
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestIriwInternal::thread0) }
                thread { actor(TestIriwInternal::thread1) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(TestIriwInternal::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val t0 = getValue<Pair<Int, Int>>(results.parallelResults[0][0]!!)
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            listOf(t0.first, t0.second, t1.first, t1.second)
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testIRIW() {
        class TestIRIW {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Pair<Int, Int> {
                val eax = y.getOpaque()
                val ebx = x.getOpaque()
                return eax to ebx
            }
            fun thread1() {
                x.setOpaque(1)
            }
            fun thread2(): Pair<Int, Int> {
                val eax = x.getOpaque()
                val ebx = y.getOpaque()
                return eax to ebx
            }
            fun thread3() {
                y.setOpaque(1)
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestIRIW::thread0) }
                thread { actor(TestIRIW::thread1) }
                thread { actor(TestIRIW::thread2) }
                thread { actor(TestIRIW::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(TestIRIW::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val t0 = getValue<Pair<Int, Int>>(results.parallelResults[0][0]!!)
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            listOf(t0.first, t0.second, t2.first, t2.second)
        }
    }

    @Test
    fun testMpRelaxed() {
        class TestMpRelaxed {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setPlain(1)
                y.setOpaque(1)
            }
            fun thread1(): Pair<Int, Int> {
                val r0 = y.getOpaque()
                var r1 = -1
                if (r0 != 0) {
                    r1 = x.getPlain()
                }
                return r0 to r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestMpRelaxed::thread0) }
                thread { actor(TestMpRelaxed::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(TestMpRelaxed::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
        }
    }


    @Test
    fun testPodrw001() {
        // NOTE: this is just Store Buffering with 3 reads
        class TestPodrw001 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            fun thread0(): Int {
                z.setOpaque(1)
                return x.getOpaque()
            }
            fun thread1(): Int {
                x.setOpaque(1)
                return y.getOpaque()
            }
            fun thread2(): Int {
                y.setOpaque(1)
                return z.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestPodrw001::thread0) }
                thread { actor(TestPodrw001::thread1) }
                thread { actor(TestPodrw001::thread2) }
            }
        }
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0,0,0))
        litmusTest(TestPodrw001::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            Triple(
                getValue<Int>(results.parallelResults[0][0]!!),
                getValue<Int>(results.parallelResults[1][0]!!),
                getValue<Int>(results.parallelResults[2][0]!!)
            )
        }
    }

    @Test
    fun testWRR() {
        class TestWRR {
            val x = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
            }
            fun thread1(): Pair<Int, Int> {
                val x2 = x.getOpaque()
                val x3 = x.getOpaque()
                return x2 to x3
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestWRR::thread0) }
                thread { actor(TestWRR::thread1) }
            }
        }
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(TestWRR::class.java, testScenario, assertNever(forbiddenOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testX001() {
        class TestX001 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                x.setOpaque(1)
                return y.getOpaque()
            }
            fun thread1(): Pair<Int, Int> {
                y.setOpaque(1)
                val eax = y.getOpaque()
                val ebx = x.getOpaque()
                return eax to ebx
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestX001::thread0) }
                thread { actor(TestX001::thread1) }
            }
        }
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0,1,0))
        litmusTest(TestX001::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val r0 = getValue<Int>(results.parallelResults[0][0]!!)
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            Triple(r0, t1.first, t1.second)
        }
    }


    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testX003() {
        class TestX003 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
                y.setOpaque(1)
            }
            fun thread1(): Pair<Int, Int> {
                y.setOpaque(2)
                val eax = y.getOpaque()
                val ebx = x.getOpaque()
                return eax to ebx
            }
            fun post() : Int {
                return y.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestX003::thread0) }
                thread { actor(TestX003::thread1) }
            }
            post { actor(TestX003::post) }
        }
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(2,2,0))
        litmusTest(TestX003::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val regs = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            val y = getValue<Int>(results.postResults[0]!!)
            Triple(y, regs.first, regs.second)
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testX006() {
        class TestX006 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
                y.setOpaque(1)
            }
            fun thread1(): Int {
                y.setOpaque(2)
                val r0 = x.getOpaque()
                return r0
            }
            fun post() : Int {
                return y.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestX006::thread0) }
                thread { actor(TestX006::thread1) }
            }
            post { actor(TestX006::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((2 to 0))
        litmusTest(TestX006::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val r0 = getValue<Int>(results.parallelResults[1][0]!!)
            val y = getValue<Int>(results.postResults[0]!!)
            y to r0
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testX86_2plus2W() {
        class TestX86_2plus2W {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(2)
                y.setOpaque(1)
            }
            fun thread1() {
                y.setOpaque(2)
                x.setOpaque(1)
            }
            fun post() : Pair<Int, Int> {
                return x.get() to y.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestX86_2plus2W::thread0) }
                thread { actor(TestX86_2plus2W::thread1) }
            }
            post { actor(TestX86_2plus2W::post) }
        }
        val expectedOutcomes: Set<Pair<Int,Int>> = setOf((2 to 2))
        litmusTest(TestX86_2plus2W::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            getValue<Pair<Int,Int>>(results.postResults[0]!!)
        }
    }


    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testA1() {
        class TestA1 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = y.getOpaque()
                x.setRelease(1)
                return r0
            }
            fun thread1(): Int {
                val r1 = x.getAcquire()
                if (r1 != 0) {
                    y.setPlain(1)
                }
                return r1
            }
            fun post() : Pair<Int, Int> {
                return x.get() to y.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestA1::thread0) }
                thread { actor(TestA1::thread1) }
            }
            post { actor(TestA1::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestA1::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testA1Reorder() {
        class TestA1Reorder {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                x.setRelease(1)
                val r0 = y.getOpaque()
                return r0
            }
            fun thread1(): Int {
                val r1 = x.getAcquire()
                if (r1 != 0) {
                    y.setPlain(1)
                }
                return r1
            }
            fun post() : Pair<Int, Int> {
                return x.get() to y.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestA1Reorder::thread0) }
                thread { actor(TestA1Reorder::thread1) }
            }
            post { actor(TestA1Reorder::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestA1Reorder::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testA3() {
        class TestA3 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                y.setPlain(1)
                x.setRelease(1)
            }
            fun thread1(): Int {
                val r1 = x.getAcquire()
                var r2 = 0
                if (r1 != 0) {
                    r2 = y.getOpaque()
                }
                return r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestA3::thread0) }
                thread { actor(TestA3::thread1) }
            }
        }
        val expectedOutcomes: Set<Int> = setOf(1)
        litmusTest(TestA3::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            getValue<Int>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testA3Reorder() {
        class TestA3Reorder {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                y.setPlain(1)
                x.setRelease(1)
            }
            fun thread1(): Int {
                val r2 = y.getOpaque()
                val r1 = x.getAcquire()
                return r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestA3Reorder::thread0) }
                thread { actor(TestA3Reorder::thread1) }
            }
        }
        val expectedOutcomes: Set<Int> = setOf(1)
        litmusTest(TestA3Reorder::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            getValue<Int>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testIRIWPoaasLL() {
        class TestIRIWPoaasLL {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setRelease(1)
            }
            fun thread1() {
                y.setRelease(1)
            }
            fun thread2(): Pair<Int, Int> {
                val r1 = x.getAcquire()
                val r2 = y.getAcquire()
                return r1 to r2
            }
            fun thread3(): Pair<Int, Int> {
                val r3 = y.getAcquire()
                val r4 = x.getAcquire()
                return r3 to r4
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestIRIWPoaasLL::thread0) }
                thread { actor(TestIRIWPoaasLL::thread1) }
                thread { actor(TestIRIWPoaasLL::thread2) }
                thread { actor(TestIRIWPoaasLL::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(TestIRIWPoaasLL::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    @Test
    fun testIRIWPoapsLL() {
        class TestIRIWPoapsLL {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setRelease(1)
            }
            fun thread1() {
                y.setRelease(1)
            }
            fun thread2(): Pair<Int, Int> {
                val r1 = x.getAcquire()
                val r2 = y.getOpaque()
                return r1 to r2
            }
            fun thread3(): Pair<Int, Int> {
                val r3 = y.getAcquire()
                val r4 = x.getOpaque()
                return r3 to r4
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestIRIWPoapsLL::thread0) }
                thread { actor(TestIRIWPoapsLL::thread1) }
                thread { actor(TestIRIWPoapsLL::thread2) }
                thread { actor(TestIRIWPoapsLL::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(TestIRIWPoapsLL::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }


    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testLinearisation() {
        class TestLinearisation {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val w = AtomicInteger(0)
            val z = AtomicInteger(0)
            fun thread0(): Int {
                val t = x.getAcquire() + y.getPlain()
                if (t == 2) {
                    w.setRelease(1)
                }
                return t
            }
            fun thread1(): Int {
                val r0 = w.getOpaque()
                if (r0 != 0) {
                    z.setOpaque(1)
                }
                return r0
            }
            fun thread2(): Int {
                val r1 = z.getOpaque()
                if (r1 != 0) {
                    y.setPlain(1)
                    x.setRelease(1)
                }
                return r1
            }
            fun post(): List<Int> {
                return listOf(w.get() ,x.get(), y.get(), z.get())
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestLinearisation::thread0) }
                thread { actor(TestLinearisation::thread1) }
                thread { actor(TestLinearisation::thread2) }
            }
            post { actor(TestLinearisation::post) }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2,1,1,1,1))
        litmusTest(TestLinearisation::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            listOf(getValue<Int>(results.parallelResults[0][0]!!)) + getValue<List<Int>>(results.postResults[0]!!)
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testLinearisation2() {
        class TestLinearisation2 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val w = AtomicInteger(0)
            val z = AtomicInteger(0)
            fun thread0(): Int {
                var t = x.getAcquire()
                val r0 = t + y.getPlain()
                if (r0 == 2) {
                    w.setRelease(1)
                }
                return r0
            }
            fun thread1(): Int {
                val r0 = w.getOpaque()
                if (r0 != 0) {
                    z.setOpaque(1)
                }
                return r0
            }
            fun thread2(): Int {
                val r1 = z.getOpaque()
                if (r1 != 0) {
                    y.setPlain(1)
                    x.setRelease(1)
                }
                return r1
            }
            fun post(): List<Int> {
                return listOf(w.get() ,x.get(), y.get(), z.get())
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestLinearisation2::thread0) }
                thread { actor(TestLinearisation2::thread1) }
                thread { actor(TestLinearisation2::thread2) }
            }
            post { actor(TestLinearisation2::post) }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2,1,1,1,1))
        litmusTest(TestLinearisation2::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            listOf(getValue<Int>(results.parallelResults[0][0]!!)) + getValue<List<Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testMpRelacq() {
        class TestMpRelacq {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setPlain(1)
                y.setRelease(1)
            }
            fun thread1(): Pair<Int, Int> {
                val r0 = y.getAcquire()
                var r1 = -1
                if (r0 == 1) {
                    r1 = x.getPlain()
                }
                return r0 to r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestMpRelacq::thread0) }
                thread { actor(TestMpRelacq::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(TestMpRelacq::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
        }
    }

    // NOTE: this test is interesting because C11 forbids this behavior but JAM allows it
    @Test
    fun testMpRelacqRs() {
        class TestMpRelacqRs {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setPlain(1)
                y.setRelease(1)
                y.setOpaque(2)
            }
            fun thread1(): Pair<Int, Int> {
                var r1 = -1
                val r0 = y.getAcquire()
                if (r0 == 2) {
                    r1 = x.getPlain()
                }
                return r0 to r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestMpRelacqRs::thread0) }
                thread { actor(TestMpRelacqRs::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((2 to 0))
        litmusTest(TestMpRelacqRs::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testRoachmotel() {
        class TestRoachmotel {
            val a = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            fun thread0() {
                z.setRelease(1)
                a.setPlain(1)
            }
            fun thread1(): Triple<Int, Int, Int> {
                val r0 = x.getOpaque()
                var r1 = 0
                var r2 = 0
                if (r0 != 0) {
                    r1 = z.getAcquire()
                    r2 = a.getPlain()
                    if (r2 != 0) {
                        y.setOpaque(1)
                    }
                }
                return Triple(r0, r1, r2)
            }
            fun thread2(): Int {
                val r3 = y.getOpaque()
                if (r3 != 0) {
                    x.setOpaque(1)
                }
                return r3
            }
            fun post(): List<Int> {
                return listOf(a.get(), z.get(), x.get(), y.get())
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestRoachmotel::thread0) }
                thread { actor(TestRoachmotel::thread1) }
                thread { actor(TestRoachmotel::thread2) }
            }
            post { actor(TestRoachmotel::post) }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,1,1,1))
        litmusTest(TestRoachmotel::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            getValue<List<Int>>(results.postResults[0]!!)
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testRoachmotel2() {
        class TestRoachmotel2 {
            val a = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            fun thread0() {
                a.setPlain(1)
                z.setRelease(1)
            }
            fun thread1(): Triple<Int, Int, Int> {
                val r0 = x.getOpaque()
                var r1 = 0
                var r2 = 0
                if (r0 != 0) {
                    r1 = z.getAcquire()
                    r2 = a.getPlain()
                    if (r2 != 0) {
                        y.setOpaque(1)
                    }
                }
                return Triple(r0, r1, r2)
            }
            fun thread2(): Int {
                val r3 = y.getOpaque()
                if (r3 != 0) {
                    x.setOpaque(1)
                }
                return r3
            }
            fun post(): List<Int> {
                return listOf(a.get(), z.get(), x.get(), y.get())
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestRoachmotel2::thread0) }
                thread { actor(TestRoachmotel2::thread1) }
                thread { actor(TestRoachmotel2::thread2) }
            }
            post { actor(TestRoachmotel2::post) }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,1,1,1))
        litmusTest(TestRoachmotel2::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            getValue<List<Int>>(results.postResults[0]!!)
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testRseqWeak() {
        class TestRseqWeak {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(2)
            }
            fun thread1() {
                y.setPlain(1)
                x.setRelease(1)
                x.setOpaque(3)
            }
            fun thread2(): Pair<Int, Int> {
                val r0 = x.getAcquire()
                var r1 = 0
                if (r0 == 3) {
                    r1 = y.getPlain()
                }
                return r0 to r1
            }
            fun post(): Pair<Int, Int> {
                return x.get() to y.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestRseqWeak::thread0) }
                thread { actor(TestRseqWeak::thread1) }
                thread { actor(TestRseqWeak::thread2) }
            }
            post { actor(TestRseqWeak::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((3 to 1))
        litmusTest(TestRseqWeak::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testRseqWeak2() {
        class TestRseqWeak2 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                y.setPlain(1)
                x.setRelease(1)
                x.setOpaque(3)
            }
            fun thread1(): Pair<Int, Int> {
                val r0 = x.getAcquire()
                var r1 = 0
                if (r0 == 3) {
                    r1 = y.getPlain()
                }
                return r0 to r1
            }
            fun post(): Pair<Int, Int> {
                return x.get() to y.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestRseqWeak2::thread0) }
                thread { actor(TestRseqWeak2::thread1) }
            }
            post { actor(TestRseqWeak2::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((3 to 1))
        litmusTest(TestRseqWeak2::class.java, testScenario, assertSame(expectedOutcomes, UNKNOWN)) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testTotalco() {
        class TestTotalco {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                val x2 = x.getOpaque()
                x.setOpaque(1)
                return x2
            }
            fun thread1(): Int {
                val x5 = y.getAcquire()
                x.setOpaque(2)
                return x5
            }
            fun thread2(): Int {
                val x5 = x.getAcquire()
                y.setOpaque(1)
                return x5
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestTotalco::thread0) }
                thread { actor(TestTotalco::thread1) }
                thread { actor(TestTotalco::thread2) }
            }
        }
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1,1,1))
        litmusTest(TestTotalco::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            Triple(
                getValue<Int>(results.parallelResults[0][0]!!),
                getValue<Int>(results.parallelResults[1][0]!!),
                getValue<Int>(results.parallelResults[2][0]!!)
            )
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Test
    fun testWWRRWWRRWsilpPoaaWsilpPoaa() {
        class TestWWRR {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setRelease(1)
                x.setRelease(2)
            }
            fun thread1(): Pair<Int, Int> {
                val x0 = x.getAcquire()
                val x2 = y.getAcquire()
                return x0 to x2
            }
            fun thread2() {
                y.setRelease(1)
                y.setRelease(2)
            }
            fun thread3(): Pair<Int, Int> {
                val x0 = y.getAcquire()
                val x2 = x.getAcquire()
                return x0 to x2
            }
            fun post(): Pair<Int, Int> {
                return x.get() to y.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestWWRR::thread0) }
                thread { actor(TestWWRR::thread1) }
                thread { actor(TestWWRR::thread2) }
                thread { actor(TestWWRR::thread3) }
            }
            post { actor(TestWWRR::post) }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(
            listOf(2,2,2,0,2,0)
        )
        litmusTest(TestWWRR::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            val post = getValue<Pair<Int, Int>>(results.postResults[0]!!)
            listOf(post.first, post.second, t1.first, t1.second, t3.first, t3.second)
        }
    }


    // We probably need more tests for fences
    @Test
    fun testMpFences() {
        class TestClass {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)

            fun thread0() {
                x.setPlain(1)
                VarHandle.releaseFence()
                y.setOpaque(1)
            }

            fun thread1(): Pair<Int, Int> {
                val r0 = y.getOpaque()
                VarHandle.acquireFence()
                var r1 = - 1
                if (r0 == 1) {
                   r1 = x.getPlain()
                }
                return r0 to r1
            }
        }

        val testScenario = scenario {
            parallel {
                thread { actor(TestClass::thread0) }
                thread { actor(TestClass::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(TestClass::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testA4() {
        class TestA4Volatile {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                x.set(1)
                return y.get()
            }
            fun thread1(): Int {
                y.set(1)
                return x.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestA4Volatile::thread0) }
                thread { actor(TestA4Volatile::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((0 to 0))
        litmusTest(TestA4Volatile::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            val r1 = getValue<Int>(results.parallelResults[0][0]!!)
            val r2 = getValue<Int>(results.parallelResults[1][0]!!)
            r1 to r2
        }
    }

    @Test
    fun testA4Reorder() {
        class TestA4VolatileReorder {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                val r1 = y.get()
                x.set(1)
                return r1
            }
            fun thread1(): Int {
                y.set(1)
                return x.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestA4VolatileReorder::thread0) }
                thread { actor(TestA4VolatileReorder::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((0 to 0))
        litmusTest(TestA4VolatileReorder::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val r1 = getValue<Int>(results.parallelResults[0][0]!!)
            val r2 = getValue<Int>(results.parallelResults[1][0]!!)
            r1 to r2
        }
    }

    @Test
    fun test2Plus2W() {
        class Test2Plus2WVolatile {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                x.set(1)
                y.set(2)
                return y.getOpaque()
            }
            fun thread1(): Int {
                y.set(1)
                x.set(2)
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(Test2Plus2WVolatile::thread0) }
                thread { actor(Test2Plus2WVolatile::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(Test2Plus2WVolatile::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            val r1_0 = getValue<Int>(results.parallelResults[0][0]!!)
            val r1_1 = getValue<Int>(results.parallelResults[1][0]!!)
            r1_0 to r1_1
        }
    }

    @Test
    fun testCppMemIriwRelacq() {
        class TestCppMemIriwRelacq {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.set(1)
            }
            fun thread1() {
                y.set(1)
            }
            fun thread2(): Pair<Int, Int> {
                val r1 = x.getAcquire()
                val r2 = y.getAcquire()
                return r1 to r2
            }
            fun thread3(): Pair<Int, Int> {
                val r3 = y.getAcquire()
                val r4 = x.getAcquire()
                return r3 to r4
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCppMemIriwRelacq::thread0) }
                thread { actor(TestCppMemIriwRelacq::thread1) }
                thread { actor(TestCppMemIriwRelacq::thread2) }
                thread { actor(TestCppMemIriwRelacq::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(TestCppMemIriwRelacq::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    //TODO: There are is no possible write of value 2 to y. I assume this is some thing air behaviour.
    @Test
    fun testCppMemScAtomics() {
        class TestCppMemScAtomics {
            val x = AtomicInteger(2)
            val y = AtomicInteger(0)
            fun thread0() {
                x.set(3)
            }
            fun thread1() {
                if (x.get() == 3) {
                    y.setPlain(1)
                }
            }
            fun post(): Int {
                return y.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCppMemScAtomics::thread0) }
                thread { actor(TestCppMemScAtomics::thread1) }
            }
            post { actor(TestCppMemScAtomics::post) }
        }
        val expectedOutcomes: Set<Int> = setOf(2)
        litmusTest(TestCppMemScAtomics::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            getValue<Int>(results.postResults[0]!!)
        }
    }

    //
    @Test
    fun testFig6() {
        class TestFig6 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
                x.set(2)
                y.set(1)
            }
            fun thread1() {
                x.setOpaque(3)
                y.set(2)
            }
            fun thread2(): Int {
                y.set(3)
                return x.get()
            }
            fun thread3(): List<Int> {
                val s1 = x.getOpaque()
                val s2 = x.getOpaque()
                val s3 = x.getOpaque()
                val t1 = y.getOpaque()
                val t2 = y.getOpaque()
                val t3 = y.getOpaque()
                return listOf(s1, s2, s3, t1, t2, t3)
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestFig6::thread0) }
                thread { actor(TestFig6::thread1) }
                thread { actor(TestFig6::thread2) }
                thread { actor(TestFig6::thread3) }
            }
        }
        // TODO: These tests timed out in JAM19. See if JAM21 actually gets a result for them
        // For C11 this never happens
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 1, 1, 2, 2, 3, 3))
        litmusTest(TestFig6::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            val r = getValue<Int>(results.parallelResults[2][0]!!)
            val t3 = getValue<List<Int>>(results.parallelResults[3][0]!!)
            listOf(r, t3[0], t3[1], t3[2], t3[3], t3[4], t3[5])
        }
    }

    //TODO: Same issues as Fig6
    @Test
    fun testFig6Translated() {
        class TestFig6Translated {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
                x.set(2)
                y.set(1)
            }
            fun thread1() {
                x.set(3)
                y.set(2)
            }
            fun thread2(): Int {
                y.set(3)
                return x.get()
            }
            fun thread3(): List<Int> {
                val s1 = x.getOpaque()
                val s2 = x.getOpaque()
                val s3 = x.getOpaque()
                val t1 = y.getOpaque()
                val t2 = y.getOpaque()
                val t3 = y.getOpaque()
                return listOf(s1, s2, s3, t1, t2, t3)
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestFig6Translated::thread0) }
                thread { actor(TestFig6Translated::thread1) }
                thread { actor(TestFig6Translated::thread2) }
                thread { actor(TestFig6Translated::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 1, 1, 2, 2, 3, 3))
        litmusTest(TestFig6Translated::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            val r = getValue<Int>(results.parallelResults[2][0]!!)
            val t3 = getValue<List<Int>>(results.parallelResults[3][0]!!)
            listOf(r, t3[0], t3[1], t3[2], t3[3], t3[4], t3[5])
        }
    }

    @Test
    fun testIriwAcqSc() {
        class TestIriwAcqSc {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.set(1)
            }
            fun thread1() {
                y.set(1)
            }
            fun thread2(): Pair<Int, Int> {
                val r1 = x.getAcquire()
                val r2 = y.get()
                return r1 to r2
            }
            fun thread3(): Pair<Int, Int> {
                val r3 = y.getAcquire()
                val r4 = x.get()
                return r3 to r4
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestIriwAcqSc::thread0) }
                thread { actor(TestIriwAcqSc::thread1) }
                thread { actor(TestIriwAcqSc::thread2) }
                thread { actor(TestIriwAcqSc::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(TestIriwAcqSc::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    @Test
    fun testIriwScRlxAcq() {
        class TestIriwScRlxAcq {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.set(1)
                x.setOpaque(2)
            }
            fun thread1() {
                y.set(1)
                y.setOpaque(2)
            }
            fun thread2(): Pair<Int, Int> {
                val r1 = x.getAcquire()
                val r2 = y.getAcquire()
                return r1 to r2
            }
            fun thread3(): Pair<Int, Int> {
                val r3 = y.getAcquire()
                val r4 = x.getAcquire()
                return r3 to r4
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestIriwScRlxAcq::thread0) }
                thread { actor(TestIriwScRlxAcq::thread1) }
                thread { actor(TestIriwScRlxAcq::thread2) }
                thread { actor(TestIriwScRlxAcq::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2, 0, 2, 0))
        litmusTest(TestIriwScRlxAcq::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    @Test
    fun testIriwSc() {
        class TestIriwSc {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.set(1)
            }
            fun thread1() {
                y.set(1)
            }
            fun thread2(): Pair<Int, Int> {
                val r1 = x.get()
                val r2 = y.get()
                return r1 to r2
            }
            fun thread3(): Pair<Int, Int> {
                val r3 = y.get()
                val r4 = x.get()
                return r3 to r4
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestIriwSc::thread0) }
                thread { actor(TestIriwSc::thread1) }
                thread { actor(TestIriwSc::thread2) }
                thread { actor(TestIriwSc::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(TestIriwSc::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    @Test
    fun testMpSc() {
        class TestMpSc {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setPlain(1)
                y.set(1)
            }
            fun thread1(): Pair<Int, Int> {
                val r0 = y.get()
                var r1 = -1
                if (r0 == 1) {
                    r1 = x.getPlain()
                }
                return r0 to r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestMpSc::thread0) }
                thread { actor(TestMpSc::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(TestMpSc::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testRaNonGlobal() {
        class TestRaNonLocal {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                y.set(2)
                return x.get()
            }
            fun thread1() {
                x.set(1)
            }
            fun thread2(): Int {
                val r0 = x.get()
                y.set(1)
                return r0
            }
            fun thread3(): Pair<Int, Int> {
                val r0 = y.get()
                val r1 = y.get()
                return r0 to r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestRaNonLocal::thread0) }
                thread { actor(TestRaNonLocal::thread1) }
                thread { actor(TestRaNonLocal::thread2) }
                thread { actor(TestRaNonLocal::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(0, 1, 1, 2))
        //TODO: find matching thingy
        litmusTest(TestRaNonLocal::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val t0 = getValue<Int>(results.parallelResults[0][0]!!)
            val t2 = getValue<Int>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t0, t2, t3.first, t3.second)
        }
    }

    @Test
    fun testReadWriteSc() {
        class TestReadWriteSc {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            fun thread0(): Int {
                x.set(1)
                return y.get()
            }
            fun thread1() {
                y.set(1)
            }
            fun thread2(): Int {
                val r1 = y.get()
                z.set(1)
                return r1
            }
            fun thread3(): Int {
                z.set(2)
                return x.get()
            }
            fun thread4(): Pair<Int, Int> {
                val r1 = z.get()
                val r2 = z.get()
                return r1 to r2
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestReadWriteSc::thread0) }
                thread { actor(TestReadWriteSc::thread1) }
                thread { actor(TestReadWriteSc::thread2) }
                thread { actor(TestReadWriteSc::thread3) }
                thread { actor(TestReadWriteSc::thread4) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(0, 1, 0, 1, 2))
        //TODO: find matching thingy
        litmusTest(TestReadWriteSc::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val t0 = getValue<Int>(results.parallelResults[0][0]!!)
            val t2 = getValue<Int>(results.parallelResults[2][0]!!)
            val t3 = getValue<Int>(results.parallelResults[3][0]!!)
            val t4 = getValue<Pair<Int, Int>>(results.parallelResults[4][0]!!)
            listOf(t0, t2, t3, t4.first, t4.second)
        }
    }

    // TODO: rmw is a bit not-working
    @Ignore
    @Test
    fun testZ6U() {
        class TestZ6U {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.set(1)
                y.setRelease(1)
            }
            fun thread1(): Pair<Int, Int> {
                val r1 = y.getAndAdd(1)
                val r2 = y.getOpaque()
                return r1 to r2
            }
            fun thread2(): Int {
                y.set(3)
                return x.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestZ6U::thread0) }
                thread { actor(TestZ6U::thread1) }
                thread { actor(TestZ6U::thread2) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 3, 0))
        litmusTest(TestZ6U::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            val t2 = getValue<Int>(results.parallelResults[2][0]!!)
            listOf(t1.first, t1.second, t2)
        }
    }

    //TODO: CAS
    @Ignore
    @Test
    fun testA3v2() {
        class TestA3v2 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                y.setPlain(1)
                x.setRelease(1)
            }
            fun thread1(): Int {
                val r0 = x.compareAndExchangeAcquire(1, 2)
                var r1 = -1
                if (r0 == 1) {
                    r1 = y.getPlain()
                }
                return r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestA3v2::thread0) }
                thread { actor(TestA3v2::thread1) }
            }
        }
        val expectedOutcomes: Set<Int> = setOf(1)
        litmusTest(TestA3v2::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            getValue<Int>(results.parallelResults[1][0]!!)
        }
    }

    // TODO: CASE
    @Ignore
    @Test
    fun testCp() {
        class TestCp {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val t = p.compareAndExchangeAcquire(1, 2)
                    q.setPlain(1)
                    if (t == 1) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCp::thread0) }
                thread { actor(TestCp::thread1) }
            }
            post { actor(TestCp::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCp::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    //TODO: CAS
    @Ignore
    @Test
    fun testCpReorder() {
        class TestCpReorder {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    q.setPlain(1)
                    val t = p.compareAndExchangeAcquire(1, 2)
                    if (t == 1) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCpReorder::thread0) }
                thread { actor(TestCpReorder::thread1) }
            }
            post { actor(TestCpReorder::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCpReorder::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }


    // TOOD: CAS
    @Ignore
    @Test
    fun testCpq() {
        class TestCpq {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val t = p.compareAndExchangeAcquire(1, 2)
                    val u = q.compareAndExchangeAcquire(0, 1)
                    if (t == 1) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCpq::thread0) }
                thread { actor(TestCpq::thread1) }
            }
            post { actor(TestCpq::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCpq::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    // TODO: CAS
    @Ignore
    @Test
    fun testCpqReorder() {
        class TestCpqReorder {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val u = q.compareAndExchangeAcquire(0, 1)
                    val t = p.compareAndExchangeAcquire(1, 2)
                    if (t == 1) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCpqReorder::thread0) }
                thread { actor(TestCpqReorder::thread1) }
            }
            post { actor(TestCpqReorder::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCpqReorder::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    //TODO: CAS
    @Ignore
    @Test
    fun testCq() {
        class TestCq {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val t = p.getPlain()
                    val u = q.compareAndExchangeAcquire(0, 1)
                    if (t != 0) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCq::thread0) }
                thread { actor(TestCq::thread1) }
            }
            post { actor(TestCq::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCq::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    //TODO: Cas
    @Ignore
    @Test
    fun testCqReorder() {
        class TestCqReorder {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val u = q.compareAndExchangeAcquire(0, 1)
                    val t = p.getPlain()
                    if (t != 0) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCqReorder::thread0) }
                thread { actor(TestCqReorder::thread1) }
            }
            post { actor(TestCqReorder::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCqReorder::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testPPOCA() {
        class TestPPOCA {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
                VarHandle.fullFence()
                y.setOpaque(1)
            }
            fun thread1(): Triple<Int, Int, Int> {
                val x0 = y.getOpaque()
                var x4 = 0
                var x6 = 0
                if (x0 == 0) {
                    z.setOpaque(1)
                    x4 = z.getOpaque()
                    x6 = x.getOpaque()
                }
                return Triple(x0, x4, x6)
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestPPOCA::thread0) }
                thread { actor(TestPPOCA::thread1) }
            }
        }
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 0))
        litmusTest(TestPPOCA::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            getValue<Triple<Int, Int, Int>>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testSBMfence() {
        class TestSBMfence {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                x.setOpaque(1)
                VarHandle.fullFence()
                return y.getOpaque()
            }
            fun thread1(): Int {
                y.setOpaque(1)
                VarHandle.fullFence()
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestSBMfence::thread0) }
                thread { actor(TestSBMfence::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((0 to 0))
        litmusTest(TestSBMfence::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            val eax0 = getValue<Int>(results.parallelResults[0][0]!!)
            val eax1 = getValue<Int>(results.parallelResults[1][0]!!)
            eax0 to eax1
        }
    }

    @Test
    fun testWRWC() {
        class TestWRWC {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
                z.setRelease(1)
            }
            fun thread1(): Pair<Int, Int> {
                val r1 = z.getAcquire()
                VarHandle.fullFence()
                val r2 = y.getOpaque()
                return r1 to r2
            }
            fun thread2(): Int {
                y.setOpaque(1)
                VarHandle.fullFence()
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestWRWC::thread0) }
                thread { actor(TestWRWC::thread1) }
                thread { actor(TestWRWC::thread2) }
            }
        }
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 0, 0))
        litmusTest(TestWRWC::class.java, testScenario, assertNever(expectedOutcomes)) { results ->
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            val t2 = getValue<Int>(results.parallelResults[2][0]!!)
            Triple(t1.first, t1.second, t2)
        }
    }

    @Test
    fun testRWCSyncs() {
        class TestRWCSyncs {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
            }
            fun thread1(): Pair<Int, Int> {
                val r1 = x.getOpaque()
                VarHandle.fullFence() // TODO: full fence here.
                val r2 = y.getOpaque()
                return r1 to r2
            }
            fun thread2(): Int {
                y.setOpaque(1)
                VarHandle.fullFence()
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestRWCSyncs::thread0) }
                thread { actor(TestRWCSyncs::thread1) }
                thread { actor(TestRWCSyncs::thread2) }
            }
        }
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1,0,0))
        litmusTest(TestRWCSyncs::class.java, testScenario, assertNever(forbiddenOutcomes)) { results ->
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            val r1 = getValue<Int>(results.parallelResults[2][0]!!)
            Triple(t1.first, t1.second, r1)
        }
    }

    @Test
    fun testX002() {
        class TestX002 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                x.setOpaque(1)
                VarHandle.fullFence()
                return y.getOpaque()
            }
            fun thread1(): Pair<Int, Int> {
                y.setOpaque(1)
                val eax = y.getOpaque()
                val ebx = x.getOpaque()
                return eax to ebx
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestX002::thread0) }
                thread { actor(TestX002::thread1) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(0, 1, 0))
        litmusTest(TestX002::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val eax0 = getValue<Int>(results.parallelResults[0][0]!!)
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            listOf(eax0, t1.first, t1.second)
        }
    }

    @Test
    fun testX005() {
        class TestX005 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                x.setOpaque(1)
                VarHandle.fullFence()
                return y.getOpaque()
            }
            fun thread1(): Int {
                y.setOpaque(1)
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestX005::thread0) }
                thread { actor(TestX005::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((0 to 0))
        litmusTest(TestX005::class.java, testScenario, assertSometimes(expectedOutcomes)) { results ->
            val eax0 = getValue<Int>(results.parallelResults[0][0]!!)
            val eax1 = getValue<Int>(results.parallelResults[1][0]!!)
            eax0 to eax1
        }
    }
}
