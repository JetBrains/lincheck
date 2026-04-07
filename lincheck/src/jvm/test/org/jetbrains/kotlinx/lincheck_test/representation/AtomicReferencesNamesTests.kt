/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import java.util.concurrent.atomic.*

class AtomicReferencesNamesTest : BaseTraceRepresentationTest("atomic_references_names_trace") {

    private val atomicReference = AtomicReference(Node(1))
    private val atomicInteger = AtomicInteger(0)
    private val atomicLong = AtomicLong(0L)
    private val atomicBoolean = AtomicBoolean(true)

    private val atomicReferenceArray = AtomicReferenceArray(arrayOf(Node(1)))
    private val atomicIntegerArray = AtomicIntegerArray(intArrayOf(0))
    private val atomicLongArray = AtomicLongArray(longArrayOf(0L))

    override fun operation() {
        atomicReference.compareAndSet(atomicReference.get(), Node(2))
        atomicReference.set(Node(3))

        atomicInteger.compareAndSet(atomicInteger.get(), 2)
        atomicInteger.set(3)

        atomicLong.compareAndSet(atomicLong.get(), 2)
        atomicLong.set(3)

        atomicBoolean.compareAndSet(atomicBoolean.get(), true)
        atomicBoolean.set(false)

        atomicReferenceArray.compareAndSet(0, atomicReferenceArray.get(0), Node(2))
        atomicReferenceArray.set(0, Node(3))

        atomicIntegerArray.compareAndSet(0, atomicIntegerArray.get(0), 1)
        atomicIntegerArray.set(0, 2)

        atomicLongArray.compareAndSet(0, atomicLongArray.get(0), 1)
        atomicLongArray.set(0, 2)

        staticValue.compareAndSet(0, 2)
        staticValue.set(0)

        staticArray.compareAndSet(1, 0, 1)
    }

    private data class Node(val name: Int)

    companion object {
        @JvmStatic
        private val staticValue = AtomicInteger(0)
        @JvmStatic
        val staticArray = AtomicIntegerArray(3)
    }
}

class AtomicReferencesFromMultipleFieldsTest : BaseTraceRepresentationTest("atomic_references_name_two_fields_trace") {

    private var atomicReference1: AtomicReference<Node>
    private var atomicReference2: AtomicReference<Node>

    init {
        val ref = AtomicReference(Node(1))
        atomicReference1 = ref
        atomicReference2 = ref
    }

    override fun operation() {
        atomicReference1.compareAndSet(atomicReference2.get(), Node(2))
    }

    private data class Node(val name: Int)

}


