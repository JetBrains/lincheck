/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package tests.custom.set

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LockFreeSet {
    private val head = Node(Int.MIN_VALUE, null, true) // dummy node

    fun add(key: Int): Boolean {
        var node = head

        while (true) {
            while (true) {
                node = node.next.get() ?: break
                if (node.key == key) {
                    return if (node.isDeleted.get())
                        node.isDeleted.compareAndSet(true, false)
                    else
                        false
                }
            }

            val newNode = Node(key, null, false)
            if (node.next.compareAndSet(null, newNode))
                return true
        }
    }

    fun remove(key: Int): Boolean {
        var node = head
        while (true) {
            node = node.next.get() ?: break
            if (node.key == key) {
                return if (node.isDeleted.get())
                    false
                else
                    node.isDeleted.compareAndSet(false, true)
            }
        }

        return false
    }

    fun contains(key: Int): Boolean {
        var node = head
        while (true) {
            node = node.next.get() ?: break
            if (node.key == key)
                return !node.isDeleted.get()
        }

        return false
    }

    //incorrect operation implementation, however the minimal needed wrong execution sequence is quite large
    fun snapshot(): List<Int> {
        while (true) {
            val firstSnapshot = doSnapshot()
            val secondSnapshot = doSnapshot()

            if (firstSnapshot == secondSnapshot)
                return firstSnapshot
        }
    }

    private fun doSnapshot(): List<Int> {
        var snapshot = mutableListOf<Int>()

        var node = head

        while (true) {
            node = node.next.get() ?: break
            if (!node.isDeleted.get())
                snapshot.add(node.key)
        }

        return snapshot
    }

    private inner class Node(val key: Int, next: Node?, initialMark: Boolean) {
        // marked if is deleted
        val next: AtomicReference<Node?> = AtomicReference(next)
        val isDeleted = AtomicBoolean(initialMark)
    }
}