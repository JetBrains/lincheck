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

package org.jetbrains.kotlinx.lincheck.test.guide

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.util.*
import java.util.concurrent.atomic.*
import kotlin.NoSuchElementException

class Stack<T> {
    private val top  = AtomicReference<Node<T>?>(null)
    private val _size = AtomicInteger(0)

    fun push(value: T) {
        while (true) {
            val cur = top.get()
            val newTop = Node(cur, value)
            if (top.compareAndSet(cur, newTop)) { // try to add
                _size.incrementAndGet() // <-- INCREMENT SIZE
                return
            }
        }
    }

    fun pop(): T? {
        while (true) {
            val cur = top.get() ?: return null // is stack empty?
            if (top.compareAndSet(cur, cur.next)) { // try to retrieve
                _size.decrementAndGet() // <-- DECREMENT SIZE
                return cur.value
            }
        }
    }

    override fun toString() =
        buildString {
            append("[")
            var node = top.get()
            while (node != null) {
                append(node.value)
                node = node.next
                if (node != null) append(",")
            }
            append("]")
        }

    val size: Int get() = _size.get()
}
class Node<T>(val next: Node<T>?, val value: T)

@Param(name = "value", gen = IntGen::class, conf = "1:10") // values are ints in 1..10 range
class StackTest : VerifierState() {
    private val s = Stack<Int>()

    @Operation fun push(@Param(name = "value") value: Int) = s.push(value)
    @Operation(handleExceptionsAsResult = [NoSuchElementException::class])
    fun pop() = s.pop()
    @Operation fun size() = s.size

    @StateRepresentation
    fun stackreperesentation() = s.toString()

    override fun extractState(): List<Int> {
        val elements = mutableListOf<Int>()
        while(s.size != 0) {
            elements.add(s.pop()!!)
        }
        return elements
    }

    class SequentialStack {
        val s = LinkedList<Int>()

        fun push(x: Int) = s.push(x)
        fun pop() = s.pop()
        fun size() = s.size
    }

    // @Test TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun stressTest() = StressOptions()
        .sequentialSpecification(SequentialStack::class.java)
        .check(this::class)

    // @Test TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun modelCheckingTest() {
        ModelCheckingOptions()
            .actorsBefore(5)
            .actorsAfter(5)
            .threads(2).actorsPerThread(2)
            .check(this::class)
    }
}