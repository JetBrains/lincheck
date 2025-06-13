/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.enumerateObjects
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.atomicfu.AtomicIntArray
import org.junit.Assert
import org.junit.Test

/**
 * Checks invariants and restrictions on [enumerateObjects] method.
 */
class ObjectTraverserTest {

    @Test
    fun `should not traverse class and classLoader recursively while enumerating objects`() {
        val myObject = object : Any() {
            var clazz: Class<*>? = this::class.java
            var classLoader: ClassLoader? = this::class.java.classLoader
            var integer: Int = 10
        }
        val objectEnumeration = enumerateObjects(myObject)

        Assert.assertTrue(objectEnumeration.keys.none { it is Class<*> || it is ClassLoader })
    }

    @Test
    fun `should handle cyclic dependencies`() {
        val objectA = object : Any() {
            var B: Any? = null
        }
        val objectB = object : Any() {
            val A: Any = objectA
        }
        objectA.B = objectB
        val objectEnumeration = enumerateObjects(objectA)

        Assert.assertTrue(objectEnumeration.keys.size == 2 && objectEnumeration.keys.containsAll(listOf(objectA, objectB)))
    }

    @Test
    fun `should traverse reference-array elements for java arrays`() {
        val o1 = Optional.of(1)
        val o2 = Optional.of(2)
        val o3 = Optional.of(3)
        val myObject = object : Any() {
            val array = arrayOf(o1, o2, o3)
        }
        val objectEnumeration = enumerateObjects(myObject)

        Assert.assertTrue(
            objectEnumeration.size == 8 &&
            objectEnumeration.keys.containsAll(
                listOf(myObject, myObject.array, *myObject.array, o1.get(), o2.get(), o3.get())
            )
        )
    }

    @Test
    fun `should traverse array elements for atomic arrays`() {
        val myObject = object : Any() {
            val array = AtomicReferenceArray(arrayOf(1, 2, 3))
        }
        val objectEnumeration = enumerateObjects(myObject)

        Assert.assertTrue(
            objectEnumeration.size == 5 &&
            objectEnumeration.keys.containsAll(
                listOf(myObject, myObject.array, myObject.array[0], myObject.array[1], myObject.array[2])
            )
        )
    }

    @Test
    fun `should traverse array elements for atomicfu arrays`() {
        val arraySize = 3
        val myObject = object : Any() {
            val array = AtomicIntArray(arraySize)
            init {
                for (i in 0 until arraySize) {
                    array[i].value = i + 1
                }
            }
        }
        val objectEnumeration = enumerateObjects(myObject)

        Assert.assertTrue(
            objectEnumeration.size == 5 &&
            objectEnumeration.keys.containsAll(
                listOf(
                    myObject,
                    // atomicfu transformers are insane and don't allow compile direct reference to `myObject.array`
                    myObject.javaClass.getDeclaredField("array").apply { setAccessible(true) }.get(myObject),
                    1, 2, 3,
                )
            )
        )
    }

    @Test
    @Suppress("UNUSED_VARIABLE")
    fun `should jump through atomic refs`() {
        val myObject = object : Any() {
            val javaRef = AtomicReference<AtomicReference<Int>>(AtomicReference(1))
            val atomicFURef: kotlinx.atomicfu.AtomicRef<AtomicReference<Any>?> = kotlinx.atomicfu.atomic(null)
        }
        myObject.atomicFURef.value = AtomicReference<Any>(2)

        val objectEnumeration = enumerateObjects(myObject)
        Assert.assertTrue(
            objectEnumeration.size == 3 &&
            objectEnumeration.keys.containsAll(
                listOf(myObject, 1, 2)
            )
        )
    }
}