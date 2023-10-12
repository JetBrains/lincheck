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

package org.jetbrains.kotlinx.lincheck.test

import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.EventStructureOptions
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*

@Param(name = "value", gen = IntGen::class, conf = "1:5")
class LockFreeLinkedListTest : AbstractLincheckTest() {

    @OptIn(InternalCoroutinesApi::class)
    class Node(val value: Int): LockFreeLinkedListNode()

    private val q: LockFreeLinkedListHead = LockFreeLinkedListHead()

    @Operation
    fun addLast(@Param(name = "value") value: Int) {
        q.addLast(Node(value))
    }

    @Operation
    fun addLastIfNotSame(@Param(name = "value") value: Int) {
        q.addLastIfPrev(Node(value)) { !it.isSame(value) }
    }

    @Operation
    fun removeFirst(): Int? {
        val node = q.removeFirstOrNull() ?: return null
        return (node as Node).value
    }

    @Operation
    fun removeFirstOrPeekIfNotSame(@Param(name = "value") value: Int): Int? {
        val node = q.removeFirstIfIsInstanceOfOrPeekIf<Node> { !it.isSame(value) } ?: return null
        return node.value
    }

    private fun Any.isSame(value: Int) = this is Node && this.value == value

    override fun extractState(): Any {
        val elements = ArrayList<Int>()
        q.forEach<Node> { elements.add(it.value) }
        return elements
    }

    override fun ModelCheckingOptions.customize() {
        checkObstructionFreedom()
    }

    override fun EventStructureOptions.customize() {
        checkObstructionFreedom()
    }

}